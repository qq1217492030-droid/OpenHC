package io.github.gcbond.openhc.platform.net

/**
 * 「VPN 授权这一关怎么过」的决策（纯逻辑，JVM 单元测试覆盖）。前提是还没授权（已授权的情况调用方先拦掉）：
 * - 系统里有授权页：走系统框，让用户自己点同意——这是普通车机的正路；
 * - 授权页被裁掉、但应用拿得到系统的免确认授权权限（签名级 CONTROL_VPN）：自己把授权办了；
 * - 两样都没有：放弃，用 [VpnPageMissing.REASON] 说明原因。
 */
internal object VpnConsentFlow {

    sealed class Decision {
        /** 系统里有授权页：拉起系统框让用户同意。 */
        object ShowDialog : Decision()

        /** 没有授权页、应用能自己授权：不惊动用户，直接把授权办妥。 */
        object SelfAuthorize : Decision()

        /** 两样都没有：放弃。 */
        object GiveUp : Decision()
    }

    fun decide(pageAvailable: Boolean, selfAuthorizable: Boolean): Decision = when {
        pageAvailable -> Decision.ShowDialog
        selfAuthorizable -> Decision.SelfAuthorize
        else -> Decision.GiveUp
    }
}
