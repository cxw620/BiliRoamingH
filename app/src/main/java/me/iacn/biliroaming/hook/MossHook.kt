package me.iacn.biliroaming.hook

import me.iacn.biliroaming.utils.Log

class MossHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        Log.d("startHook: Moss")
    }
}
