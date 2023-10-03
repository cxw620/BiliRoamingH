package me.iacn.biliroaming.hook.moss

import android.net.Uri
import com.google.protobuf.any
import me.iacn.biliroaming.*
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.hook.BangumiPlayUrlHook.Companion.qnApplied
import me.iacn.biliroaming.hook.moss.BangumiUtils.MAX_FNVAL
import me.iacn.biliroaming.hook.moss.BangumiUtils.PGC_ANY_MODEL_TYPE_URL
import me.iacn.biliroaming.hook.moss.BangumiUtils.fixBusinessProto
import me.iacn.biliroaming.hook.moss.BangumiUtils.fixDownloadProto
import me.iacn.biliroaming.hook.moss.BangumiUtils.getThaiSeason
import me.iacn.biliroaming.hook.moss.BangumiUtils.purifyViewInfo
import me.iacn.biliroaming.hook.moss.BangumiUtils.supportedPlayArcIndices
import me.iacn.biliroaming.hook.moss.BangumiUtils.toErrorReply
import me.iacn.biliroaming.hook.moss.BangumiUtils.toVideoInfo
import me.iacn.biliroaming.network.BiliRoamingApi.CustomServerException
import me.iacn.biliroaming.network.BiliRoamingApi.getPlayUrl
import me.iacn.biliroaming.utils.*
import org.json.JSONObject

class PlayerUniteV1(classLoader: ClassLoader) : MossHookHelper(classLoader) {
    override val mossClassName = "com.bapis.bilibili.app.playerunite.v1.PlayerMoss"

    private val viewHooker = object : IMossHook {
        override val shouldHook = sPrefs.getBoolean("main_func", false)
        override val hookBlocking = true
        override val hookAsync = true
        override val mossMethodName = "playViewUnite"
        override val reqTypeString = "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq"

        private val halfScreenQuality = sPrefs.getString("half_screen_quality", "0")?.toInt() ?: 0
        private val fullScreenQuality = sPrefs.getString("full_screen_quality", "0")?.toInt() ?: 0

        private val defaultQn: Int?
            get() = instance.playerSettingHelperClass?.callStaticMethodAs<Int>(instance.getDefaultQn())

        private var isDownload = false
        private val allowDownload = sPrefs.getBoolean("allow_download", false)
        private val fixDownload = sPrefs.getBoolean("fix_download", false)

        private val blockBangumiPageAds = sPrefs.getBoolean("block_view_page_ads", false)

        override fun IMossHook.MossData.hookBefore(): Any? {
            this.originalReq.callMethod("getVod")?.apply {
                isDownload = allowDownload && callMethodAs<Int>("getDownload") >= 1
                if (isDownload) {
                    if (!fixDownload || callMethodAs<Int>("getFnval") <= 1) {
                        callMethod("setFnval", MAX_FNVAL)
                        callMethod("setFourk", true)
                    }
                    callMethod("setDownload", 0)
                } else if (halfScreenQuality != 0 || fullScreenQuality != 0) {
                    // unlock available quality limit, allow quality up to 8K
                    callMethod("setFnval", MAX_FNVAL)
                    callMethod("setFourk", true)
                    if (halfScreenQuality != 0 && qnApplied.compareAndSet(
                            false, true
                        )
                    ) {
                        if (halfScreenQuality != 1) {
                            callMethod("setQn", halfScreenQuality)
                        } else {
                            // follow full screen quality
                            defaultQn?.let { callMethod("setQn", it) }
                        }
                    }
                }
            }
            return null
        }

        override fun IMossHook.MossData.hookAfter(): Any? {
            val response = originalReply
                ?: ("com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReply" on mClassLoader).new()

            val supplementAny = response.callMethod("getSupplement") ?: return null

            // Only handle pgc video
            if (originalReply != null && supplementAny.callMethodAs<String>("getTypeUrl") != PGC_ANY_MODEL_TYPE_URL) return null

            val extraContent =
                this.originalReq.callMethodAs<Map<String, String>>("getExtraContentMap")
            val seasonId = extraContent.getOrDefault("season_id", "0")
            val reqEpId = extraContent.getOrDefault("ep_id", "0").toLong()
            if (seasonId == "0" && reqEpId == 0L) return null

            val supplement =
                supplementAny.callMethod("getValue")?.callMethodAs<ByteArray>("toByteArray")
                    ?.let { PlayViewReply.parseFrom(it) } ?: playViewReply {}

            return if (needProxyUnite(response, supplement)) {
                try {
                    val serializedRequest = this.originalReq.callMethodAs<ByteArray>("toByteArray")
                    val req = PlayViewUniteReq.parseFrom(serializedRequest)
                    val (thaiSeason, thaiEp) = getThaiSeason(seasonId, reqEpId)
                    val content = getPlayUrl(reconstructQueryUnite(req, supplement, thaiEp))
                    content?.let {
                        Log.toast("已从代理服务器获取播放地址\n如加载缓慢或黑屏，可去漫游设置中测速并设置 UPOS")
                        reconstructResponseUnite(
                            req, response, supplement, it, isDownload, thaiSeason, thaiEp
                        )
                    }
                        ?: throw CustomServerException(mapOf("未知错误" to "请检查哔哩漫游设置中解析服务器设置。"))
                } catch (e: CustomServerException) {
                    Log.toast("请求解析服务器发生错误: ${e.message}", alsoLog = true)
                    showPlayerErrorUnite(
                        response, supplement, "请求解析服务器发生错误", e.message
                    )
                }
            } else if (isDownload) {
                fixDownloadProtoUnite(response)
            } else if (blockBangumiPageAds) {
                purifyViewInfo(response, supplement)
            } else null
        }
    }

    private val playArcHooker = object : IMossHook {
        override val shouldHook = sPrefs.getBoolean("play_arc_conf", false)
        override val hookBlocking = true
        override val hookAsync = true
        override val mossMethodName = "playViewUnite"
        override val reqTypeString = "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq"

        private val supportedArcConf =
            "com.bapis.bilibili.playershared.ArcConf".from(mClassLoader)?.new()?.apply {
                callMethod("setDisabled", false)
                callMethod("setIsSupport", true)
            }

        private fun Any.modifyPlayArcConf() =
            callMethod("getPlayArcConf")?.callMethodAs<LinkedHashMap<Int, Any?>>("internalGetMutableArcConfs")
                ?.run {
                    // CASTCONF,BACKGROUNDPLAY,SMALLWINDOW,LISTEN
                    intArrayOf(2, 9, 23, 36).forEach { this[it] = supportedArcConf }
                }

        override fun IMossHook.MossData.hookBefore() = null

        override fun IMossHook.MossData.hookAfter(): Any? {
            originalReply?.modifyPlayArcConf()
            return null
        }
    }


    override fun hook() {
        viewHooker.addHook()
        playArcHooker.addHook()
        super.hook()
    }

    private fun needProxyUnite(response: Any, supplement: PlayViewReply): Boolean {
        if (!response.callMethodAs<Boolean>("hasVodInfo")) return true

        val viewInfo = supplement.viewInfo
        if (viewInfo.dialog.type == "area_limit") return true
        if (viewInfo.endPage.dialog.type == "area_limit") return true

        sPrefs.getString("cn_server_accessKey", null) ?: return false
        if (supplement.business.isPreview) return true
        if (viewInfo.dialog.type.isNotEmpty()) return true
        return viewInfo.endPage.dialog.type.isNotEmpty()
    }

    private fun reconstructQueryUnite(
        req: PlayViewUniteReq, supplement: PlayViewReply, thaiEp: Lazy<JSONObject>
    ): String? {
        val episodeInfo = supplement.business.episodeInfo
        // CANNOT use reflection for compatibility with Xpatch
        return Uri.Builder().run {
            appendQueryParameter("ep_id", req.extraContentMap["ep_id"].let {
                if (!it.isNullOrEmpty() && it != "0") it.toLong() else episodeInfo.epId
            }.let {
                if (it != 0) it else thaiEp.value.optLong("id")
            }.toString())
            appendQueryParameter("cid", req.vod.cid.let {
                if (it != 0L) it else episodeInfo.cid
            }.let {
                if (it != 0L) it else thaiEp.value.optLong("id")
            }.toString())
            appendQueryParameter("qn", req.vod.qn.toString())
            appendQueryParameter("fnver", req.vod.fnver.toString())
            appendQueryParameter("fnval", req.vod.fnval.toString())
            appendQueryParameter("force_host", req.vod.forceHost.toString())
            appendQueryParameter("fourk", if (req.vod.fourk) "1" else "0")
            build()
        }.query
    }

    private fun showPlayerErrorUnite(
        response: Any,
        supplement: PlayViewReply,
        message: String,
        subMessage: String,
        isBlockingReq: Boolean = false
    ) = runCatchingOrNull {
        val serializedResponse = response.callMethodAs<ByteArray>("toByteArray")
        val newRes = PlayViewUniteReply.parseFrom(serializedResponse).copy {
            this.supplement = any {
                val supplementMessage = if (isBlockingReq) {
                    message
                } else {
                    message + "\n" + subMessage
                }
                typeUrl = PGC_ANY_MODEL_TYPE_URL
                value = supplement.toErrorReply(supplementMessage).toByteString()
            }
            viewInfo = viewInfo.toErrorReply(message, subMessage)
            clearVodInfo()
        }.toByteArray()
        response.javaClass.callStaticMethod("parseFrom", newRes)
    } ?: response

    private fun fixDownloadProtoUnite(response: Any) = runCatchingOrNull {
        val serializedResponse = response.callMethodAs<ByteArray>("toByteArray")
        val newRes = PlayViewUniteReply.parseFrom(serializedResponse).copy {
            vodInfo = vodInfo.copy { fixDownloadProto() }
        }.toByteArray()
        response.javaClass.callStaticMethod("parseFrom", newRes)
    } ?: response

    private fun reconstructResponseUnite(
        req: PlayViewUniteReq,
        response: Any,
        supplement: PlayViewReply,
        content: String,
        isDownload: Boolean,
        thaiSeason: Lazy<JSONObject>,
        thaiEp: Lazy<JSONObject>
    ) = runCatching {
        var jsonContent = content.toJSONObject()
        if (jsonContent.has("result")) {
            // For kghost server
            val result = jsonContent.opt("result")
            if (result != null && result !is String) {
                jsonContent = jsonContent.getJSONObject("result")
            }
        }
        val serializedResponse = response.callMethodAs<ByteArray>("toByteArray")
        val newRes = PlayViewUniteReply.parseFrom(serializedResponse).copy {
            vodInfo = jsonContent.toVideoInfo(req.vod.preferCodeType, isDownload)
            val newSupplement = supplement.copy {
                fixBusinessProto(thaiSeason, thaiEp, jsonContent)
                viewInfo = viewInfo {}
            }
            this.supplement = any {
                typeUrl = PGC_ANY_MODEL_TYPE_URL
                value = newSupplement.toByteString()
            }
            playArcConf = playArcConf {
                val supportedConf = arcConf { isSupport = true }
                supportedPlayArcIndices.forEach { arcConf[it] = supportedConf }
            }
            if (!hasPlayArc()) {
                playArc = playArc {
                    val episode = thaiEp.value
                    aid = episode.optLong("aid")
                    cid = episode.optLong("cid")
                    videoType = BizType.BIZ_TYPE_PGC
                    episode.optJSONObject("dimension")?.run {
                        dimension = dimension {
                            width = optLong("width")
                            height = optLong("height")
                            rotate = optLong("rotate")
                        }
                    }
                }
            }
        }.toByteArray()
        response.javaClass.callStaticMethod("parseFrom", newRes)
    }.onFailure { Log.e(it) }.getOrDefault(response)

    private fun PlaysharedViewInfo.toErrorReply(message: String, subMessage: String) = copy {
        val startPlayingDialog = dialogMap["start_playing"] ?: playsharedDialog {}
        dialogMap.put("start_playing", startPlayingDialog.copy {
            backgroundInfo = backgroundInfo.copy {
                drawableBitmapUrl =
                    "http://i0.hdslb.com/bfs/bangumi/e42bfa7427456c03562a64ac747be55203e24993.png"
                effects = 2 // Effects::HALF_ALPHA
            }
            title = title.copy {
                text = message
                if (!hasTextColor()) textColor = "#ffffff"
            }
            subtitle = subtitle.copy {
                text = subMessage
                if (!hasTextColor()) textColor = "#ffffff"
            }
            // use GuideStyle::VERTICAL_TEXT, for HORIZONTAL_IMAGE cannot show error details
            styleType = 2
            limitActionType = 1 // SHOW_LIMIT_DIALOG
        })
    }
}
