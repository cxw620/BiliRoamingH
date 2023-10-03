package me.iacn.biliroaming.hook.moss

import android.net.Uri
import com.google.protobuf.any
import me.iacn.biliroaming.*
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.network.BiliRoamingApi
import me.iacn.biliroaming.utils.*
import me.iacn.biliroaming.utils.UposReplaceHelper.enableUposReplace
import me.iacn.biliroaming.utils.UposReplaceHelper.isPCdnUpos
import me.iacn.biliroaming.utils.UposReplaceHelper.replaceUpos
import me.iacn.biliroaming.utils.UposReplaceHelper.videoUposBackups
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

object BangumiUtils {
    const val MAX_FNVAL = 16 or 64 or 128 or 256 or 512 or 1024 or 2048
    const val PGC_ANY_MODEL_TYPE_URL =
        "type.googleapis.com/bilibili.app.playerunite.pgcanymodel.PGCAnyModel"
    private val codecMap =
        mapOf(CodeType.CODE264 to 7, CodeType.CODE265 to 12, CodeType.CODEAV1 to 13)
    val supportedPlayArcIndices = arrayOf(
        1, // FILPCONF
        2, // CASTCONF
        3, // FEEDBACK
        4, // SUBTITLE
        5, // PLAYBACKRATE
        6, // TIMEUP
        7, // PLAYBACKMODE
        8, // SCALEMODE
        9, // BACKGROUNDPLAY
        10, // LIKE
        12, // COIN
        14, // SHARE
        15, // SCREENSHOT
        16, // LOCKSCREEN
        17, // RECOMMEND
        18, // PLAYBACKSPEED
        19, // DEFINITION
        20, // SELECTIONS
        21, // NEXT
        22, // EDITDM
        23, // SMALLWINDOW
        25, // OUTERDM
        26, // INNERDM
        29, // COLORFILTER
        34, // RECORDSCREEN
    )

    val defaultQn: Int?
        get() = instance.playerSettingHelperClass?.callStaticMethodAs<Int>(
            instance.getDefaultQn()
        )

    private val allowMiniPlay = sPrefs.getBoolean("allow_mini_play", false)

    fun getThaiSeason(
        seasonId: String, reqEpId: Long
    ): Pair<Lazy<JSONObject>, Lazy<JSONObject>> {
        val season = lazy {
            BiliRoamingApi.getSeason(
                mapOf("season_id" to seasonId, "ep_id" to reqEpId.toString()), true
            )?.toJSONObject()?.optJSONObject("result")
                ?: throw BiliRoamingApi.CustomServerException(mapOf("解析服务器错误" to "无法获取剧集信息"))
        }
        val ep = lazy {
            season.value.let { s ->
                s.optJSONArray("modules").orEmpty().asSequence<JSONObject>().flatMap {
                    it.optJSONObject("data")?.optJSONArray("episodes").orEmpty()
                        .asSequence<JSONObject>()
                }.let { es ->
                    es.firstOrNull { if (reqEpId != 0L) it.optLong("id") == reqEpId else true }
                } ?: s.optJSONObject("new_ep")?.apply { put("status", 2L) }
            }
                ?: throw BiliRoamingApi.CustomServerException(mapOf("解析服务器错误" to "无法获取剧集信息"))
        }
        return season to ep
    }

    fun needProxy(response: Any): Boolean {
        if (!response.callMethodAs<Boolean>("hasVideoInfo")) return true

        val viewInfo = response.callMethod("getViewInfo")

        if (viewInfo?.callMethod("getDialog")
                ?.callMethodAs<String>("getType") == "area_limit"
        ) return true

        if (viewInfo?.callMethod("getEndPage")?.callMethod("getDialog")
                ?.callMethodAs<String>("getType") == "area_limit"
        ) return true

        sPrefs.getString("cn_server_accessKey", null) ?: return false
        val business = response.callMethod("getBusiness")
        if (business?.callMethodAs<Boolean>("getIsPreview") == true) return true
        if (viewInfo?.callMethod("getDialog")?.callMethodAs<String>("getType")
                ?.let { it != "" } == true
        ) return true
        return viewInfo?.callMethod("getEndPage")?.callMethod("getDialog")
            ?.callMethodAs<String>("getType")?.let { it != "" } == true
    }


    fun PlayViewReply.toErrorReply(message: String) = copy {
        viewInfo = viewInfo.copy {
            if (endPage.hasDialog()) {
                dialog = endPage.dialog
            }
            dialog = dialog.copy {
                msg = "获取播放地址失败"
                title = title.copy {
                    text = message
                    if (!hasTextColor()) textColor = "#ffffff"
                }
                image = image.copy {
                    url =
                        "https://i0.hdslb.com/bfs/album/08d5ce2fef8da8adf91024db4a69919b8d02fd5c.png"
                }
                if (!hasCode()) code = 6002003
                if (!hasStyle()) style = "horizontal_image"
                if (!hasType()) type = "area_limit"
            }
            clearEndPage()
        }
        clearVideoInfo()
    }


    fun showPlayerError(response: Any, message: String) = runCatchingOrNull {
        val serializedResponse = response.callMethodAs<ByteArray>("toByteArray")
        val newRes = PlayViewReply.parseFrom(serializedResponse).toErrorReply(message)
        response.javaClass.callStaticMethod("parseFrom", newRes.toByteArray())
    } ?: response


    fun VideoInfoKt.Dsl.fixDownloadProto(checkBaseUrl: Boolean = false) {
        var audioId = 0
        var setted = false
        val checkConnection = fun(url: String) = runCatchingOrNull {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.connect()
            connection.responseCode == HttpURLConnection.HTTP_OK
        } ?: false
        val streams = streamList.map { s ->
            if (s.streamInfo.quality != quality || setted) {
                s.copy { clearContent() }
            } else {
                audioId = s.dashVideo.audioId
                setted = true
                if (checkBaseUrl) {
                    s.copy {
                        dashVideo = dashVideo.copy {
                            if (!checkConnection(baseUrl)) backupUrl.find { checkConnection(it) }
                                ?.let {
                                    baseUrl = it
                                }
                        }
                    }
                } else s
            }
        }
        val audio = (dashAudio.find {
            it.id == audioId
        } ?: dashAudio.first()).let { a ->
            if (checkBaseUrl) {
                a.copy {
                    if (!checkConnection(baseUrl)) backupUrl.find { checkConnection(it) }?.let {
                        baseUrl = it
                    }
                }
            } else a
        }
        streamList.clear()
        dashAudio.clear()
        streamList += streams
        dashAudio += audio
    }

    fun fixDownloadProto(response: Any) = runCatchingOrNull {
        val serializedResponse = response.callMethodAs<ByteArray>("toByteArray")
        val newRes = PlayViewReply.parseFrom(serializedResponse).copy {
            videoInfo = videoInfo.copy { fixDownloadProto() }
        }.toByteArray()
        response.javaClass.callStaticMethod("parseFrom", newRes)
    } ?: response


    fun needForceProxy(response: Any): Boolean {
        sPrefs.getString("cn_server_accessKey", null) ?: return false
        val serializedResponse = response.callMethodAs<ByteArray>("toByteArray")
        return PlayViewReply.parseFrom(serializedResponse).business.isPreview
    }

    fun reconstructQuery(
        req: PlayViewReq, response: Any, thaiEp: Lazy<JSONObject>
    ): String? {
        val episodeInfo by lazy {
            response.callMethodOrNull("getBusiness")?.callMethodOrNull("getEpisodeInfo")
        }
        // CANNOT use reflection for compatibility with Xpatch
        return Uri.Builder().run {
            appendQueryParameter("ep_id", req.epId.let {
                if (it != 0L) it else episodeInfo?.callMethodOrNullAs<Int>("getEpId") ?: 0
            }.let {
                if (it != 0) it else thaiEp.value.optLong("id")
            }.toString())
            appendQueryParameter("cid", req.cid.let {
                if (it != 0L) it else episodeInfo?.callMethodOrNullAs<Long>("getCid") ?: 0
            }.let {
                if (it != 0L) it else thaiEp.value.optLong("id")
            }.toString())
            appendQueryParameter("qn", req.qn.toString())
            appendQueryParameter("fnver", req.fnver.toString())
            appendQueryParameter("fnval", req.fnval.toString())
            appendQueryParameter("force_host", req.forceHost.toString())
            appendQueryParameter("fourk", if (req.fourk) "1" else "0")
            build()
        }.query
    }

    fun reconstructResponse(
        req: PlayViewReq,
        response: Any,
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
        val newRes = PlayViewReply.parseFrom(serializedResponse).copy {
            playConf = playAbilityConf {
                dislikeDisable = true
                likeDisable = true
                elecDisable = true
                freyaEnterDisable = true
                freyaFullDisable = true
            }
            videoInfo = jsonContent.toVideoInfo(req.preferCodecType, isDownload)
            fixBusinessProto(thaiSeason, thaiEp, jsonContent)
            viewInfo = viewInfo {}
        }.toByteArray()
        response.javaClass.callStaticMethod("parseFrom", newRes)
    }.onFailure { Log.e(it) }.getOrDefault(response)


    fun PlayViewReplyKt.Dsl.fixBusinessProto(
        thaiSeason: Lazy<JSONObject>, thaiEp: Lazy<JSONObject>, jsonContent: JSONObject
    ) {
        if (hasBusiness()) {
            business = business.copy {
                isPreview = jsonContent.optInt("is_preview", 0) == 1
                episodeInfo = episodeInfo.copy {
                    seasonInfo = seasonInfo.copy {
                        rights = seasonRights {
                            canWatch = 1
                        }
                    }
                }
                if (allowMiniPlay) {
                    inlineType = 1 // InlineType.TYPE_WHOLE
                }
            }
        } else {
            // thai
            business = businessInfo {
                val season = thaiSeason.value
                val episode = thaiEp.value
                isPreview = jsonContent.optInt("is_preview", 0) == 1
                episodeInfo = episodeInfo {
                    epId = episode.optInt("id")
                    cid = episode.optLong("id")
                    aid = season.optLong("season_id")
                    epStatus = episode.optLong("status")
                    cover = episode.optString("cover")
                    title = episode.optString("title")
                    seasonInfo = seasonInfo {
                        seasonId = season.optInt("season_id")
                        seasonType = season.optInt("type")
                        seasonStatus = season.optInt("status")
                        cover = season.optString("cover")
                        title = season.optString("title")
                        rights = seasonRights {
                            canWatch = 1
                        }
                    }
                }
                if (allowMiniPlay) {
                    inlineType = 1 // InlineType.TYPE_WHOLE
                }
            }
        }
    }

    fun JSONObject.toVideoInfo(preferCodec: CodeType, isDownload: Boolean) = videoInfo {
        val qualityList = optJSONArray("accept_quality")?.asSequence<Int>()?.toList().orEmpty()
        val type = optString("type")
        val videoCodecId = optInt("video_codecid")
        val formatMap = HashMap<Int, JSONObject>()
        for (format in optJSONArray("support_formats").orEmpty()) {
            formatMap[format.optInt("quality")] = format
        }

        timelength = optLong("timelength")
        videoCodecid = videoCodecId
        quality = optInt("quality")
        format = optString("format")

        if (type == "DASH") {
            val audioIds = ArrayList<Int>()
            for (audio in optJSONObject("dash")?.optJSONArray("audio").orEmpty()) {
                dashAudio += dashItem {
                    audio.run {
                        baseUrl = optString("base_url")
                        id = optInt("id")
                        audioIds.add(id)
                        md5 = optString("md5")
                        size = optLong("size")
                        codecid = optInt("codecid")
                        bandwidth = optInt("bandwidth")
                        for (bk in optJSONArray("backup_url").orEmpty()
                            .asSequence<String>()) backupUrl += bk
                    }
                }
            }
            var bestMatchQn = quality
            var minDeltaQn = Int.MAX_VALUE
            val preferCodecId = codecMap[preferCodec] ?: videoCodecId
            val videos =
                optJSONObject("dash")?.optJSONArray("video")?.asSequence<JSONObject>()?.toList()
                    .orEmpty()
            val availableQns = videos.map { it.optInt("id") }.toSet()
            val preferVideos = videos.filter { it.optInt("codecid") == preferCodecId }
                .takeIf { l -> l.map { it.optInt("id") }.containsAll(availableQns) }
                ?: videos.filter { it.optInt("codecid") == videoCodecId }
            preferVideos.forEach { video ->
                streamList += stream {
                    dashVideo = dashVideo {
                        video.run {
                            baseUrl = optString("base_url")
                            backupUrl += optJSONArray("backup_url").orEmpty().asSequence<String>()
                                .toList()
                            bandwidth = optInt("bandwidth")
                            codecid = optInt("codecid")
                            md5 = optString("md5")
                            size = optLong("size")
                        }
                        // Not knowing the extract matching,
                        // just use the largest id
                        audioId = audioIds.maxOrNull() ?: audioIds[0]
                        noRexcode = optInt("no_rexcode") != 0
                    }
                    streamInfo = streamInfo {
                        quality = video.optInt("id")
                        val deltaQn = abs(quality - this@videoInfo.quality)
                        if (deltaQn < minDeltaQn) {
                            bestMatchQn = quality
                            minDeltaQn = deltaQn
                        }
                        intact = true
                        attribute = 0
                        formatMap[quality]?.let { fmt ->
                            reconstructFormat(fmt)
                        }
                    }
                }
            }
            quality = bestMatchQn
        } else if (type == "FLV" || type == "MP4") {
            qualityList.forEach { quality ->
                streamList += stream {
                    streamInfo = streamInfo {
                        this.quality = quality
                        intact = true
                        attribute = 0
                        formatMap[quality]?.let { fmt ->
                            reconstructFormat(fmt)
                        }
                    }

                    if (quality == optInt("quality")) {
                        segmentVideo = segmentVideo {
                            for (seg in optJSONArray("durl").orEmpty()) {
                                segment += responseUrl {
                                    seg.run {
                                        length = optLong("length")
                                        backupUrl += optJSONArray("backup_url").orEmpty()
                                            .asSequence<String>().toList()
                                        md5 = optString("md5")
                                        order = optInt("order")
                                        size = optLong("size")
                                        url = optString("url")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        reconstructVideoInfoUpos(isDownload)
        if (isDownload) {
            fixDownloadProto(true)
        }
    }

    private fun StreamInfoKt.Dsl.reconstructFormat(fmt: JSONObject) = fmt.run {
        description = optString("description")
        format = optString("format")
        needVip = optBoolean("need_vip", false)
        needLogin = optBoolean("need_login", false)
        newDescription = optString("new_description")
        superscript = optString("superscript")
        displayDesc = optString("display_desc")
    }

    fun purifyViewInfo(response: Any, supplement: PlayViewReply? = null) = runCatching {
        supplement?.copy {
            playExtConf = playExtConf.copy { clearFreyaConfig() }
            viewInfo = viewInfo.copy {
                clearAnimation()
                clearCouponInfo()
                if (endPage.dialog.type != "pay") clearEndPage()
                clearHighDefinitionTrialInfo()
                clearPayTip()
                if (popWin.buttonList.all { it.actionType != "pay" }) clearPopWin()
                if (toast.button.actionType != "pay") clearToast()
                if (tryWatchPromptBar.buttonList.all { it.actionType != "pay" }) clearTryWatchPromptBar()
                extToast.clear()
            }
        }?.let {
            val serializedResponse = response.callMethodAs<ByteArray>("toByteArray")
            val newRes = PlayViewUniteReply.parseFrom(serializedResponse).copy {
                this.supplement = any {
                    typeUrl = PGC_ANY_MODEL_TYPE_URL
                    value = it.toByteString()
                }
                viewInfo = viewInfo.copy {
                    if (promptBar.buttonList.all { it.actionType != 1 }) clearPromptBar()
                    val newComprehensiveToast =  comprehensiveToast.filter { it.button.actionType != 1 }
                    comprehensiveToast.clear()
                    comprehensiveToast.addAll(newComprehensiveToast)
                }
            }.toByteArray()
            response.javaClass.callStaticMethod("parseFrom", newRes)
        } ?: run {
            response.callMethodOrNull("getPlayExtConf")?.callMethod("clearFreyaConfig")
            response.callMethod("getViewInfo")?.run {
                callMethodOrNull("clearAnimation")
                callMethod("clearCouponInfo")
                if (callMethod("getEndPage")?.callMethod("getDialog")
                        ?.callMethod("getType") != "pay"
                ) callMethod("clearEndPage")
                callMethod("clearHighDefinitionTrialInfo")
                callMethod("clearPayTip")
                if (callMethod("getPopWin")?.callMethodAs<List<Any>>("getButtonList")
                        ?.all { it.callMethod("getActionType") != "pay" } != false
                ) callMethod("clearPopWin")
                if (callMethod("getToast")?.callMethod("getButton")
                        ?.callMethod("getActionType") != "pay"
                ) callMethod("clearToast")
                if (callMethod("getTryWatchPromptBar")?.callMethodAs<List<Any>>("getButtonList")
                        ?.all { it.callMethod("getActionType") != "pay" } != false
                ) callMethod("clearTryWatchPromptBar")
                callMethodOrNullAs<LinkedHashMap<*, *>>("internalGetMutableExtToast")?.clear()
            }
            response
        }
    }.onFailure { Log.e(it) }.getOrDefault(response)

    private fun VideoInfoKt.Dsl.reconstructVideoInfoUpos(isDownload: Boolean = false) {
        if (!isDownload || !enableUposReplace) return
        val newStreamList = streamList.map { stream ->
            stream.copy { reconstructStreamUpos() }
        }
        val newDashAudio = dashAudio.map { dashItem ->
            dashItem.copy { reconstructDashItemUpos() }
        }
        streamList.clear()
        dashAudio.clear()
        streamList.addAll(newStreamList)
        dashAudio.addAll(newDashAudio)
    }

    private fun StreamKt.Dsl.reconstructStreamUpos() {
        if (hasDashVideo()) {
            dashVideo = dashVideo.copy {
                if (!hasBaseUrl()) return@copy
                val (newBaseUrl, newBackupUrl) = reconstructVideoInfoUpos(baseUrl, backupUrl)
                baseUrl = newBaseUrl
                backupUrl.clear()
                backupUrl.addAll(newBackupUrl)
            }
        } else if (hasSegmentVideo()) {
            segmentVideo = segmentVideo.copy {
                val newSegment = segment.map { responseUrl ->
                    responseUrl.copy {
                        val (newUrl, newBackupUrl) = reconstructVideoInfoUpos(url, backupUrl)
                        url = newUrl
                        backupUrl.clear()
                        backupUrl.addAll(newBackupUrl)
                    }
                }
                segment.clear()
                segment.addAll(newSegment)
            }
        }
    }

    private fun DashItemKt.Dsl.reconstructDashItemUpos() {
        if (!hasBaseUrl()) return
        val (newBaseUrl, newBackupUrl) = reconstructVideoInfoUpos(baseUrl, backupUrl)
        baseUrl = newBaseUrl
        backupUrl.clear()
        backupUrl.addAll(newBackupUrl)
    }

    private fun reconstructVideoInfoUpos(
        baseUrl: String, backupUrls: List<String>
    ): Pair<String, List<String>> {
        val filteredBackupUrls = backupUrls.filter { !it.isPCdnUpos() }
        val rawUrl = filteredBackupUrls.firstOrNull() ?: baseUrl
        return if (baseUrl.isPCdnUpos()) {
            if (filteredBackupUrls.isNotEmpty()) {
                rawUrl.replaceUpos() to listOf(
                    rawUrl.replaceUpos(videoUposBackups[0]), baseUrl
                )
            } else baseUrl to backupUrls
        } else {
            baseUrl.replaceUpos() to listOf(
                rawUrl.replaceUpos(videoUposBackups[0]), rawUrl.replaceUpos(videoUposBackups[1])
            )
        }
    }
}
