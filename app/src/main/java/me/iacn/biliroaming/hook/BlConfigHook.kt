package me.iacn.biliroaming.hook

import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.BuildConfig
import me.iacn.biliroaming.abSourceOrNull
import me.iacn.biliroaming.configSourceOrNull
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.from
import me.iacn.biliroaming.utils.hookAfterAllMethods

class BlConfigHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val abtestManualMap = mapOf<String, Any>(
        // p2p 相关
        "ff_live_room_player_close_p2p" to true,
        "ijkplayer.enable_live_bilinet" to false,
        "ijkplayer.p2p_download" to false,
        "ijkplayer.story_p2p_download" to false,
        "ijkplayer.p2p_upload" to false,
        "ijkplayer.p2p_live_download_upload" to false,
        "ijkplayer.p2p_only_cdn_policy" to true,
        "ijkplayer.p2p_only_pcdn_enable" to false,
        "ijkplayer.p2p_mcdn_replace_enable" to true,
        "ijkplayer.p2p_convert_p2p_cache_time" to "20000",
        "ijkplayer.p2p_vod_player_cache_ms_switch_p2p" to "20000",
        "ijkplayer.p2p_convert_cdn_cache_time" to "10000",
        "ijkplayer.p2p_vod_player_cache_ms_switch_cdn" to "10000",
        // 信息上报相关
        "ff_open_collect_app_list" to false,
        "ff_push_event_track" to false,
        // 广告相关
        "ff_splash_enable_request_show" to false,
        // 其他
        "ff_game_ab_exp" to false,
        "grpc_dev_enable" to BuildConfig.DEBUG,
        // "ff_player_auto_start_service" to true,
        "disable_app_heartbeat_test" to true,
        // "ff_unite_player" to true
        // "ijkplayer.enable_dynamic_cache" to false,
        "ijkplayer.cdn_cache_limit" to false,
        "ff_unite_detail2" to true,
        "ff_use_new_main_search" to true
    )

    override fun startHook() {
        Log.d("startHook: BlConfigHook")
        instance.hookInfo.blConfig.abSourceOrNull?.let {
            "com.bilibili.lib.blconfig.internal.ABSource".from(mClassLoader)
                ?.hookAfterAllMethods(it.name) { param ->
                    Log.d("BlConfigHook com.bilibili.lib.blconfig.internal.ABSource#${it.name}: ${param.args[0]} original: ${param.args[1]}")
                    abtestManualMap[param.args[0] as String]?.let { manualValue ->
                        param.result = manualValue
                        Log.d("BlConfigHook com.bilibili.lib.blconfig.internal.ABSource#${it.name}: ${param.args[0]} original: ${param.args[1]} new ${param.result}")
                    }
                }
        }
        instance.hookInfo.blConfig.configSourceOrNull?.let {
            "com.bilibili.lib.blconfig.internal.ConfigSource".from(mClassLoader)
                ?.hookAfterAllMethods(it.name) { param ->
                    Log.d("BlConfigHook com.bilibili.lib.blconfig.internal.ConfigSource#${it.name}: ${param.args[0]} original: ${param.args[1]}")
                    abtestManualMap[param.args[0] as String]?.let { manualValue ->
                        param.result = manualValue
                        Log.d("BlConfigHook com.bilibili.lib.blconfig.internal.ConfigSource#${it.name}: ${param.args[0]} original: ${param.args[1]} new ${param.result}")
                    }
                }
        }
    }
}
