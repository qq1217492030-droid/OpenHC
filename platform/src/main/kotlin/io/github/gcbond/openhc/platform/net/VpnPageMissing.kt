package io.github.gcbond.openhc.platform.net

/**
 * 系统里没有 VPN 授权页（授权页是系统组件 `com.android.vpndialogs`，有的车机把它整个裁掉了）时
 * 用的统一说法：界面提示、有线会话的结束原因、诊断日志里都是这一句，见 [VpnPageMissing.REASON]。
 */
internal object VpnPageMissing {

    const val REASON = "系统里没有 VPN 授权页"
}
