package me.iacn.biliroaming.hook

import me.iacn.biliroaming.hook.moss.PlayerUniteV1
import me.iacn.biliroaming.utils.Log

class MossHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        Log.d("startHook: Moss")
        PlayerUniteV1(mClassLoader).hook()
    }
}
