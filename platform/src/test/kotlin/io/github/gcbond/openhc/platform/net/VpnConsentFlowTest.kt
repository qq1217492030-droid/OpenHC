package io.github.gcbond.openhc.platform.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「VPN 授权这一关怎么过」的决策表（[VpnConsentFlow]）。前提是还没授权；授权页和自授权两样都有时
 * 走系统框（普通车机的正路），用户点一下比静默改系统更透明。
 */
class VpnConsentFlowTest {

    @Test
    fun withThePageAlwaysShowsTheDialog() {
        assertEquals(VpnConsentFlow.Decision.ShowDialog, VpnConsentFlow.decide(pageAvailable = true, selfAuthorizable = true))
        assertEquals(VpnConsentFlow.Decision.ShowDialog, VpnConsentFlow.decide(pageAvailable = true, selfAuthorizable = false))
    }

    /** 授权页被裁掉、应用拿得到免确认授权权限：自己把授权办了，不再让用户去点一个不存在的东西。 */
    @Test
    fun withoutThePageSelfAuthorizeWhenAllowed() {
        assertEquals(VpnConsentFlow.Decision.SelfAuthorize, VpnConsentFlow.decide(pageAvailable = false, selfAuthorizable = true))
    }

    /** 两样都没有：放弃，并且原因要说成「没有授权页」（界面上见得到的统一文案）。 */
    @Test
    fun withNeitherGivesUp() {
        assertEquals(VpnConsentFlow.Decision.GiveUp, VpnConsentFlow.decide(pageAvailable = false, selfAuthorizable = false))
        assertEquals("系统里没有 VPN 授权页", VpnPageMissing.REASON)
    }
}
