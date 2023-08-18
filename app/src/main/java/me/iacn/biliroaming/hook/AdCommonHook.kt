package me.iacn.biliroaming.hook

import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*

class AdCommonHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!sPrefs.getBoolean("remove_ad_extra", false)) return
        Log.d("startHook: AdCommonHook")
        instance.fakeIntl()?.let {
            instance.fakeIntlClass?.replaceMethod(it) { true }
        }
        "com.bilibili.adcommon.basic.model.SourceContent".from(mClassLoader)
            ?.replaceMethod("getIsAdLoc") { false }
        "com.bapis.bilibili.api.ticket.v1.TicketMoss".from(mClassLoader)?.replaceMethod(
            "getTicket", "com.bapis.bilibili.api.ticket.v1.GetTicketRequest"
        ) { null }
    }
}
