package me.iacn.biliroaming.hook

import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.BuildConfig
import me.iacn.biliroaming.abSourceOrNull
import me.iacn.biliroaming.configSourceOrNull
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.from
import me.iacn.biliroaming.utils.hookAfterAllMethods

class BlConfigHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val abtestManualMap = mapOf<String, Any>()

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
