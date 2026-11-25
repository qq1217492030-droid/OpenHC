package io.github.gcbond.openhc.platform.net

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import io.github.gcbond.openhc.platform.log.Log

/**
 * 有线连接前要先拿到 VPN 授权（[WiredVpnService] 建 TUN 需要）。授权是持久的，同意一次之后不再弹框，
 * 但别的 VPN 应用被用户启用后，本应用的授权会被取代，需要重新同意。
 *
 * 授权正常由系统授权页给（[VPN_DIALOGS_PACKAGE]，见 [canShowPage]）。有的车机把授权页整个裁掉了：
 * 这时剩下一条路是应用自己把授权办了——系统框架留了 `prepareAndAuthorize` 给持 `CONTROL_VPN`
 * （签名级）权限的应用免确认授权；用平台同一把密钥签名的车机变体正好拿得到这个权限，见 [ensureAuthorized]。
 *
 * 应用层用法（在开始有线会话之前）：
 * ```
 * when (val status = VpnConsent.ensureAuthorized(context)) {
 *     VpnConsent.Status.Granted -> 开始会话
 *     is VpnConsent.Status.NeedsUserConsent -> 用 Activity 拉起 status.intent，结果为 RESULT_OK 即同意
 *     is VpnConsent.Status.Unavailable -> 提示这台车机不能用有线连接
 * }
 * ```
 * 用 ComponentActivity 时可以 `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`
 * 拉起 `status.intent`；也可以直接用 [request]。
 */
object VpnConsent {

    private const val TAG = "OpenHC-Vpn"

    /** 系统授权页所在的应用；授权框就是它里面的 ConfirmDialog。 */
    const val VPN_DIALOGS_PACKAGE = "com.android.vpndialogs"

    /**
     * 免用户确认的授权通道要的权限（签名级）。这个权限不在公开 SDK 里，只能写字符串：
     * 拿不到它说明本应用不是用系统平台那把密钥签的，自授权这条路就不该走。
     */
    private const val CONTROL_VPN_PERMISSION = "android.permission.CONTROL_VPN"

    sealed class Status {
        /** 已授权。 */
        object Granted : Status()

        /** 需要用户在系统框里同意：用 Activity 拉起 [intent]，结果为 [Activity.RESULT_OK] 即同意。 */
        class NeedsUserConsent(val intent: Intent) : Status()

        /** 系统不让用 VPN（例如设备策略禁止、正处于别的 VPN 的锁定模式）。 */
        class Unavailable(val reason: String) : Status()
    }

    fun check(context: Context): Status = try {
        val intent = VpnService.prepare(context)
        if (intent == null) Status.Granted else Status.NeedsUserConsent(intent)
    } catch (e: RuntimeException) {
        Status.Unavailable("${e.javaClass.simpleName}: ${e.message}")
    }

    fun isGranted(context: Context): Boolean = check(context) === Status.Granted

    /**
     * 把授权办妥（[check] 的加强版）：已授权直接通过；系统里有授权页时原样返回「需要用户同意」，
     * 由界面拉起系统框；授权页被裁掉、但本应用拿得到 [CONTROL_VPN_PERMISSION] 时自己把授权写进系统
     * （用户什么都不用点）；两样都不行才报 [VpnPageMissing.REASON]。
     */
    fun ensureAuthorized(context: Context): Status {
        val first = check(context)
        if (first !is Status.NeedsUserConsent) return first
        return when (VpnConsentFlow.decide(pageAvailable = pageResolvable(context), selfAuthorizable = canSelfAuthorize(context))) {
            VpnConsentFlow.Decision.ShowDialog -> first
            VpnConsentFlow.Decision.SelfAuthorize -> {
                val failure = selfAuthorize(context)
                when {
                    isGranted(context) -> Status.Granted
                    failure != null -> Status.Unavailable("${VpnPageMissing.REASON}，自动授权没有成：$failure")
                    else -> Status.Unavailable(VpnPageMissing.REASON)
                }
            }
            VpnConsentFlow.Decision.GiveUp -> Status.Unavailable(VpnPageMissing.REASON)
        }
    }

    /** 授权办妥了吗（[ensureAuthorized] 的布尔版）：开机静默连接这类没有界面的场合用它选路。 */
    fun isAuthorized(context: Context): Boolean = ensureAuthorized(context) === Status.Granted

    /** 系统里的授权页用得了：已授权就用不着它，剩下的看它的授权框在不在系统里。 */
    fun canShowPage(context: Context): Boolean = isGranted(context) || pageResolvable(context)

    /**
     * 本应用能不能自己完成授权：持有 [CONTROL_VPN_PERMISSION]（签名级，用系统平台同一把密钥签名的
     * 车机变体才有）。查权限出错（有的系统对不认识的权限会抛）按「不能」算。
     */
    fun canSelfAuthorize(context: Context): Boolean = try {
        context.checkSelfPermission(CONTROL_VPN_PERMISSION) == PackageManager.PERMISSION_GRANTED
    } catch (e: RuntimeException) {
        false
    }

    private fun pageResolvable(context: Context): Boolean = try {
        val intent = Intent().setClassName(VPN_DIALOGS_PACKAGE, "$VPN_DIALOGS_PACKAGE.ConfirmDialog")
        context.packageManager.resolveActivity(intent, 0) != null
    } catch (e: RuntimeException) {
        false
    }

    /**
     * 自己完成授权：调系统留给特权应用的免确认入口（`prepareAndAuthorize`），它做的就是系统授权框里
     * 点「确定」同样的两步——把本应用登记成当前的 VPN 应用、往系统里写授权。这会顶替当前正在用的
     * 别的 VPN 应用，和授权框「确定」的行为一致。返回 null 表示调过了（成没成再查一次就知道）；
     * 否则是失败的简短说明。
     *
     * 反射是有意的：这个方法不在公开 SDK 里（和本模块别的隐藏 API 用法一样），目标安卓 6.0～15 都存在；
     * 不存在时按失败报，不影响其他路径。
     */
    @android.annotation.SuppressLint("PrivateApi")
    private fun selfAuthorize(context: Context): String? = try {
        VpnService::class.java
            .getDeclaredMethod("prepareAndAuthorize", Context::class.java)
            .apply { isAccessible = true }
            .invoke(null, context)
        null
    } catch (e: Exception) {
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        Log.w(TAG, "自授权没有成：${cause.javaClass.simpleName}: ${cause.message}")
        "${cause.javaClass.simpleName}: ${cause.message}"
    }

    /**
     * 已授权返回 [Status.Granted]；否则用 [activity] 拉起系统授权框（结果在 `onActivityResult(requestCode, …)`），
     * 返回 [Status.NeedsUserConsent]。车机系统里没有授权框时返回 [Status.Unavailable]。
     */
    fun request(activity: Activity, requestCode: Int): Status {
        val status = check(activity)
        if (status is Status.NeedsUserConsent) {
            try {
                activity.startActivityForResult(status.intent, requestCode)
            } catch (e: ActivityNotFoundException) {
                return Status.Unavailable(VpnPageMissing.REASON)
            }
        }
        return status
    }
}
