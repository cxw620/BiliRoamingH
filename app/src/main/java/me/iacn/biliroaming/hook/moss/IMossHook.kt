package me.iacn.biliroaming.hook.moss

interface IMossHook {
    data class MossData(
        val originalReq: Any,
        val originalReply: Any? = null,
        val replyClass: Class<*>? = null,
        val thisObject: Any,
        val throwable: Throwable?,
        val handler: Any? = null
    )

    val shouldHook: Boolean

    /**
     * Setting if target req method to hook is blocking moss method or not,
     * default true.
     */
    val hookBlocking: Boolean

    /**
     * Setting if target req method to hook is async moss method or not,
     * default false.
     */
    val hookAsync: Boolean

    /**
     * Moss Method Name, like `view`
     */
    val mossMethodName: String

    /**
     * Moss ReqType.
     * Should be simply string like `com.bapis.bilibili.app.viewunite.v1.ViewReq`,
     */
    val reqTypeString: String?

    /**
     * When hooking blocking method, it's equal to `hookBeforeMethod`.
     * When hooking async method, it's equal to doing sth before
     * create and set proxy instance.
     *
     * @return custom reply (and prevents the call to the original method,
     * including async method) or null
     *
     */
    fun MossData.hookBefore(): Any?

    /**
     * When hooking blocking method, it's equal to `hookAfterMethod`.
     * When hooking async method, it's equal to doing sth in proxy instance.
     *
     * @return custom reply or null (modify original reply)
     */
    fun MossData.hookAfter(): Any?
}
