package io.github.gcbond.openhc.platform.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「系统里没有 VPN 授权页」这条路上的两处约定：
 * - 授权页所在的应用名就是 adb 命令里要写的那一个（docs/ANDROID6.md 照它写）；
 * - 提示文字只有一份：界面上的提示和有线会话结束的原因是同一句。
 */
class VpnPageMissingTest {

    @Test
    fun dialogPackageIsTheOneAdbRefersTo() {
        // adb shell appops set io.github.gcbond.openhc ACTIVATE_VPN com.android.vpndialogs:
        assertEquals("com.android.vpndialogs", VpnConsent.VPN_DIALOGS_PACKAGE)
    }

    @Test
    fun reasonSaysWhatIsMissing() {
        assertEquals("系统里没有 VPN 授权页", VpnPageMissing.REASON)
    }
}
