package me.iacn.biliroaming.utils

import de.robv.android.xposed.XC_MethodHook
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import java.lang.reflect.Method
import me.iacn.biliroaming.BuildConfig
import me.iacn.biliroaming.hook.moss.IMossHook
import java.lang.reflect.Proxy

abstract class MossHookHelper(val mClassLoader: ClassLoader) {
    companion object {
        private val methodHookers = ArrayList<IMossHook>()
    }
    
    fun IMossHook.addHook() = methodHookers.add(this)

    /**
     * Moss Class Name
     * Should be simply string like `com.bapis.bilibili.app.viewunite.v1.ViewMoss`,
     */
    abstract val mossClassName: String

    /**
     * Moss Class
     * Should be Class<*> like `instance.viewUniteMossClass`
     */
    open val mossClass: Class<*>? by Weak { mossClassName from mClassLoader }

    private fun IMossHook.hookAsyncMethod() {
        if (!shouldHook) return
        mossClass?.hookBeforeMethod(
            mossMethodName, reqTypeString, instance.mossResponseHandlerClass,
        ) { param ->
            val mossData =
                IMossHook.MossData(param.args[0], null, null, param.thisObject, param.throwable)
            mossData.hookBefore()?.let {
                param.args[1].callMethod("onNext", it)
                return@hookBeforeMethod
            }
            val originalHandler = param.args[1]
            param.args[1] = Proxy.newProxyInstance(
                originalHandler.javaClass.classLoader, arrayOf(instance.mossResponseHandlerClass)
            ) { _, m, args ->
                when (m.name) {
                    "onNext" -> {
                        val mossData = IMossHook.MossData(
                            param.args[0],
                            args[0],
                            args[0]?.javaClass,
                            param.thisObject,
                            param.throwable
                        )
                        mossData.hookAfter()?.let {
                            args[0] = it
                            if (BuildConfig.DEBUG) {
                                Log.d("$mossClassName#$mossMethodName new Response\n${args[0]}\n######")
                            }
                        }
                        m(originalHandler, *args)
                    }

                    "onError" -> {
                        val mossData =
                            IMossHook.MossData(
                                param.args[0],
                                null,
                                null,
                                param.thisObject,
                                param.throwable
                            )
                        val newResponse = mossData.hookAfter()
                        if (BuildConfig.DEBUG) {
                            Log.d("$mossClassName#$mossMethodName new Response\n$newResponse\n######")
                        }
                        if (newResponse == null) {
                            m(originalHandler, *args)
                        } else {
                            originalHandler.callMethod("onNext", newResponse)
                            originalHandler.callMethod("onCompleted")
                        }
                    }

                    else -> {
                        if (args == null) {
                            m(originalHandler)
                        } else {
                            m(originalHandler, *args)
                        }
                    }
                }
            }
        }
    }

    private fun IMossHook.hookBlockingMethod() {
        if (!shouldHook) return
        mossClass?.hookMethod(mossMethodName, reqTypeString, object : XC_MethodHook() {
            val hookerBefore: Hooker = { param ->
                val mossData = IMossHook.MossData(
                    param.args[0],
                    null,
                    (param.method as Method).returnType,
                    param.thisObject,
                    param.throwable
                )
                mossData.hookBefore()?.let {
                    param.result = it
                    if (BuildConfig.DEBUG) {
                        Log.d("$mossClassName#$mossMethodName new Response\n${param.result}\n######")
                    }
                }
            }
            val hookerAfter: Hooker = { param ->
                val mossData = IMossHook.MossData(
                    param.args[0],
                    param.result,
                    (param.method as Method).returnType,
                    param.thisObject,
                    param.throwable
                )
                mossData.hookAfter()?.let {
                    param.result = it
                    if (BuildConfig.DEBUG) {
                        Log.d("$mossClassName#$mossMethodName new Response\n${param.result}\n######")
                    }
                }
            }

            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) =
                param.callHooker(hookerBefore)

            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) =
                param.callHooker(hookerAfter)
        })
    }

    protected fun IMossHook.MossData.hasNetworkException() =
        instance.networkExceptionClass?.isInstance(this.throwable) == true

    open fun hook() {
        if (mossClass == null) {
            Log.e("MossHook: $mossClassName not found!")
            return
        }
        if (methodHookers.none { it.shouldHook }) return
        methodHookers.forEach {
            if (it.hookAsync) it.hookAsyncMethod()
            if (it.hookBlocking) it.hookBlockingMethod()
        }
    }
}
