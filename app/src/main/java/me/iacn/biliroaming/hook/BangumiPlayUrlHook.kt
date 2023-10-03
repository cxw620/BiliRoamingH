package me.iacn.biliroaming.hook

import android.net.Uri
import me.iacn.biliroaming.*
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.hook.BangumiSeasonHook.Companion.lastSeasonInfo
import me.iacn.biliroaming.hook.moss.BangumiUtils.defaultQn
import me.iacn.biliroaming.hook.moss.BangumiUtils.fixDownloadProto
import me.iacn.biliroaming.hook.moss.BangumiUtils.getThaiSeason
import me.iacn.biliroaming.hook.moss.BangumiUtils.needForceProxy
import me.iacn.biliroaming.hook.moss.BangumiUtils.needProxy
import me.iacn.biliroaming.hook.moss.BangumiUtils.purifyViewInfo
import me.iacn.biliroaming.hook.moss.BangumiUtils.reconstructQuery
import me.iacn.biliroaming.hook.moss.BangumiUtils.reconstructResponse
import me.iacn.biliroaming.hook.moss.BangumiUtils.showPlayerError
import me.iacn.biliroaming.network.BiliRoamingApi.CustomServerException
import me.iacn.biliroaming.network.BiliRoamingApi.getPlayUrl
import me.iacn.biliroaming.utils.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by iAcn on 2019/3/29
 * Email i@iacn.me
 */
class BangumiPlayUrlHook(classLoader: ClassLoader) : BaseHook(classLoader) {

    companion object {
        // DASH, HDR, 4K, DOBLY AUDO, DOBLY VISION, 8K, AV1
        const val MAX_FNVAL = 16 or 64 or 128 or 256 or 512 or 1024 or 2048
        const val FAIL_CODE = -404
        val qnApplied = AtomicBoolean(false)
    }

    override fun startHook() {
        if (!sPrefs.getBoolean("main_func", false)) return
        Log.d("startHook: BangumiPlayUrl")
        val blockBangumiPageAds = sPrefs.getBoolean("block_view_page_ads", false)
        val halfScreenQuality = sPrefs.getString("half_screen_quality", "0")?.toInt() ?: 0
        val fullScreenQuality = sPrefs.getString("full_screen_quality", "0")?.toInt() ?: 0

        instance.signQueryName()?.let {
            instance.libBiliClass?.hookBeforeMethod(it, Map::class.java) { param ->
                @Suppress("UNCHECKED_CAST")
                val params = param.args[0] as MutableMap<String, String>
                if (sPrefs.getBoolean("allow_download", false) &&
                    params.containsKey("ep_id") && params.containsKey("dl")
                ) {
                    if (sPrefs.getBoolean("fix_download", false)) {
                        params["dl_fix"] = "1"
                        params["qn"] = "0"
                        if (params["fnval"] == "0" || params["fnval"] == "1")
                            params["fnval"] = MAX_FNVAL.toString()
                        params["fourk"] = "1"
                    }
                    params.remove("dl")
                }
            }
        }
        instance.retrofitResponseClass?.hookBeforeAllConstructors { param ->
            val url = getRetrofitUrl(param.args[0]) ?: return@hookBeforeAllConstructors
            val body = param.args[1] ?: return@hookBeforeAllConstructors
            val dataField =
                if (instance.generalResponseClass?.isInstance(body) == true) "data" else instance.responseDataField()
            if (!url.startsWith("https://api.bilibili.com/x/tv/playurl") || !lastSeasonInfo.containsKey(
                    "area"
                ) || lastSeasonInfo["area"] == "th" || body.getIntField("code") != FAIL_CODE
            ) return@hookBeforeAllConstructors
            val parsed = Uri.parse(url)
            val cid = parsed.getQueryParameter("cid")
            val fnval = parsed.getQueryParameter("fnval")
            val objectId = parsed.getQueryParameter("object_id")
            val qn = parsed.getQueryParameter("qn")
            val params =
                "cid=$cid&ep_id=$objectId&fnval=$fnval&fnver=0&fourk=1&platform=android&qn=$qn"
            val json = try {
                lastSeasonInfo["area"]?.let { lastArea ->
                    getPlayUrl(params, arrayOf(lastArea))
                }
            } catch (e: CustomServerException) {
                Log.toast("请求解析服务器发生错误: ${e.message}", alsoLog = true)
                return@hookBeforeAllConstructors
            } ?: run {
                Log.toast("获取播放地址失败")
                return@hookBeforeAllConstructors
            }
            Log.toast("已从代理服务器获取播放地址\n如加载缓慢或黑屏，可去漫游设置中测速并设置 UPOS")
            body.setObjectField(
                dataField, instance.fastJsonClass?.callStaticMethod(
                    instance.fastJsonParse(),
                    json,
                    instance.projectionPlayUrlClass
                )
            )
            body.setIntField("code", 0)
        }

        "com.bapis.bilibili.pgc.gateway.player.v1.PlayURLMoss".findClassOrNull(mClassLoader)?.run {
            var isDownload = false
            hookBeforeMethod(
                "playView",
                "com.bapis.bilibili.pgc.gateway.player.v1.PlayViewReq"
            ) { param ->
                val request = param.args[0]
                isDownload = sPrefs.getBoolean("allow_download", false)
                        && request.callMethodAs<Int>("getDownload") >= 1
                if (isDownload) {
                    if (!sPrefs.getBoolean("fix_download", false)
                        || request.callMethodAs<Int>("getFnval") <= 1
                    ) {
                        request.callMethod("setFnval", MAX_FNVAL)
                        request.callMethod("setFourk", true)
                    }
                    request.callMethod("setDownload", 0)
                } else if (halfScreenQuality == 1 || fullScreenQuality != 0) {
                    request.callMethod("setFnval", MAX_FNVAL)
                    request.callMethod("setFourk", true)
                    if (halfScreenQuality == 1 && qnApplied.compareAndSet(false, true)) {
                        defaultQn?.let { request.callMethod("setQn", it) }
                    }
                }
            }
            hookAfterMethod(
                "playView",
                "com.bapis.bilibili.pgc.gateway.player.v1.PlayViewReq"
            ) { param ->
                val request = param.args[0]
                val response = param.result
                if (!response.callMethodAs<Boolean>("hasVideoInfo")
                    || needForceProxy(response)
                ) {
                    try {
                        val serializedRequest = request.callMethodAs<ByteArray>("toByteArray")
                        val req = PlayViewReq.parseFrom(serializedRequest)
                        val seasonId = req.seasonId.toString().takeIf { it != "0" }
                            ?: lastSeasonInfo["season_id"] ?: "0"
                        val (thaiSeason, thaiEp) = getThaiSeason(seasonId, req.epId)
                        val content = getPlayUrl(reconstructQuery(req, response, thaiEp))
                        content?.let {
                            Log.toast("已从代理服务器获取播放地址\n如加载缓慢或黑屏，可去漫游设置中测速并设置 UPOS")
                            param.result = reconstructResponse(
                                req, response, it, isDownload, thaiSeason, thaiEp
                            )
                        } ?: run {
                            Log.toast("获取播放地址失败", alsoLog = true)
                        }
                    } catch (e: CustomServerException) {
                        param.result = showPlayerError(
                            response,
                            "请求解析服务器发生错误(点此查看更多)\n${e.message}"
                        )
                        Log.toast("请求解析服务器发生错误: ${e.message}", alsoLog = true)
                    }
                } else if (isDownload) {
                    param.result = fixDownloadProto(response)
                } else if (blockBangumiPageAds) {
                    param.result = purifyViewInfo(response)
                }
            }
        }
        "com.bapis.bilibili.pgc.gateway.player.v2.PlayURLMoss".findClassOrNull(mClassLoader)?.run {
            var isDownload = false
            hookBeforeMethod(
                "playView",
                "com.bapis.bilibili.pgc.gateway.player.v2.PlayViewReq"
            ) { param ->
                val request = param.args[0]
                // if getDownload == 1 -> flv download
                // if getDownload == 2 -> dash download
                // if qn == 0, we are querying available quality
                // else we are downloading
                // if fnval == 0 -> flv download
                // thus fix download will set qn = 0 and set fnval to max
                isDownload = sPrefs.getBoolean("allow_download", false)
                        && request.callMethodAs<Int>("getDownload") >= 1
                if (isDownload) {
                    if (!sPrefs.getBoolean("fix_download", false)
                        || request.callMethodAs<Int>("getFnval") <= 1
                    ) {
                        request.callMethod("setFnval", MAX_FNVAL)
                        request.callMethod("setFourk", true)
                    }
                    request.callMethod("setDownload", 0)
                } else if (halfScreenQuality == 1 || fullScreenQuality != 0) {
                    request.callMethod("setFnval", MAX_FNVAL)
                    request.callMethod("setFourk", true)
                    if (halfScreenQuality == 1 && qnApplied.compareAndSet(false, true)) {
                        defaultQn?.let { request.callMethod("setQn", it) }
                    }
                }
            }
            hookAfterMethod(
                "playView",
                "com.bapis.bilibili.pgc.gateway.player.v2.PlayViewReq"
            ) { param ->
                // th:
                // com.bilibili.lib.moss.api.BusinessException: 抱歉您所使用的平台不可观看！
                // com.bilibili.lib.moss.api.BusinessException: 啥都木有
                // connection err <- should skip because of cache:
                // throwable: com.bilibili.lib.moss.api.NetworkException
                if (instance.networkExceptionClass?.isInstance(param.throwable) == true)
                    return@hookAfterMethod
                val request = param.args[0]
                val response =
                    param.result ?: "com.bapis.bilibili.pgc.gateway.player.v2.PlayViewReply"
                        .on(mClassLoader).new()
                if (needProxy(response)) {
                    try {
                        val serializedRequest = request.callMethodAs<ByteArray>("toByteArray")
                        val req = PlayViewReq.parseFrom(serializedRequest)
                        val seasonId = req.seasonId.toString().takeIf { it != "0" }
                            ?: lastSeasonInfo["season_id"] ?: "0"
                        val (thaiSeason, thaiEp) = getThaiSeason(seasonId, req.epId)
                        val content = getPlayUrl(reconstructQuery(req, response, thaiEp))
                        content?.let {
                            Log.toast("已从代理服务器获取播放地址\n如加载缓慢或黑屏，可去漫游设置中测速并设置 UPOS")
                            param.result = reconstructResponse(
                                req, response, it, isDownload, thaiSeason, thaiEp
                            )
                        }
                            ?: throw CustomServerException(mapOf("未知错误" to "请检查哔哩漫游设置中解析服务器设置。"))
                    } catch (e: CustomServerException) {
                        param.result = showPlayerError(
                            response,
                            "请求解析服务器发生错误(点此查看更多)\n${e.message}"
                        )
                        Log.toast("请求解析服务器发生错误: ${e.message}", alsoLog = true)
                    }
                } else if (isDownload) {
                    param.result = fixDownloadProto(response)
                } else if (blockBangumiPageAds) {
                    param.result = purifyViewInfo(response)
                }
            }
        }
        instance.playURLMossClass?.hookBeforeMethod(
            "playView", instance.playViewReqClass
        ) { param ->
            val request = param.args[0]
            val isDownload = request.callMethodAs<Int>("getDownload") >= 1
            if (isDownload) return@hookBeforeMethod
            if (halfScreenQuality != 0 || fullScreenQuality != 0) {
                request.callMethod("setFnval", MAX_FNVAL)
                request.callMethod("setFourk", true)
                if (halfScreenQuality != 0 && qnApplied.compareAndSet(false, true)) {
                    if (halfScreenQuality != 1) {
                        request.callMethod("setQn", halfScreenQuality)
                    } else {
                        defaultQn?.let { request.callMethod("setQn", it) }
                    }
                }
            }
        }
    }
}
