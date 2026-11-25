package io.github.gcbond.openhc.startup

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.gcbond.openhc.connection.BluetoothAddressPolicy
import io.github.gcbond.openhc.connection.DeviceInfo
import io.github.gcbond.openhc.platform.bluetooth.BluetoothStatus
import io.github.gcbond.openhc.platform.net.VpnConsent
import io.github.gcbond.openhc.wireless.WirelessNetworkPlan
import io.github.gcbond.openhc.wireless.WirelessPermissions

/**
 * 车机上哪些连接方式能用：只看系统版本和系统里装了什么，不试跑。纯逻辑（[summarize]），由 JVM 单元测试覆盖；
 * 首页的提示和设置页的「这台车机」一组都读它。
 *
 * 各条的依据：
 * - 有线连接要先拿到 VPN 授权：系统里有授权页（`com.android.vpndialogs`）时由它给；授权页被裁掉的
 *   车机上，拿得到系统免确认授权权限（签名级 CONTROL_VPN，平台同一把密钥签名的变体）的应用能自己办。
 *   两样都没有时，有线连接开通不了。
 * - 无线连接要系统能开本地热点：这个接口安卓 8.0 起才有，安卓 6.0 的车机开不了。
 * - 无线还要求：有蓝牙且没被系统禁用；读得到车机的蓝牙地址（二维码里的地址手机要拿去直接连车机蓝牙）。
 *
 * 缺的权限、蓝牙没打开不在这里算「用不了」：那些车主能补，无线连接自己会提示。
 */
object DeviceCapabilities {

    /** 车机蓝牙适配器的现状。 */
    enum class Field(val text: String) {
        OK("正常"),
        MISSING("车机上没有蓝牙"),

        /** 有蓝牙，但开着的时候被系统（企业策略等）禁用了。 */
        DISABLED("蓝牙被系统禁用"),
        UNKNOWN("查不出来"),
    }

    /** 一种连接方式能不能用；不能用时 [reason] 是给车主看的一句话。 */
    data class Capability(val usable: Boolean, val reason: String) {
        companion object {
            fun ok() = Capability(true, "")
        }
    }

    /** 无线连接的各种前提，和「能不能用」分开：前提不满足时无线连不上，但不会让人以为系统不行。 */
    enum class WirelessPremise { NONE, NO_HOTSPOT, NO_BLUETOOTH, BLUETOOTH_DISABLED, NO_ADDRESS }

    data class Summary(
        val sdkInt: Int,
        val wired: Capability,
        val wireless: Capability,
        /** 无线用不了是因为哪一条；能用时为 [WirelessPremise.NONE]。 */
        val wirelessPremise: WirelessPremise,
        /** 系统里读不读得到车机蓝牙地址；null 表示没查。 */
        val bluetoothAddressKnown: Boolean?,
        val bluetoothField: Field,
        val missingWirelessPermissions: List<String>,
    ) {
        /** 有线、无线都用不了：这台车机上本应用投不了屏。 */
        val nothingUsable: Boolean get() = !wired.usable && !wireless.usable

        /** 无线缺的权限（只留名字的最后一段，给提示看）。 */
        fun missingPermissionNames(): String =
            if (missingWirelessPermissions.isEmpty()) {
                "无"
            } else {
                missingWirelessPermissions.joinToString("、") { it.substringAfterLast('.') }
            }
    }

    /**
     * @param vpnPageAvailable 系统里有没有 VPN 授权页（[VpnConsent.canShowPage]）。传 null（没查）时按系统版本估：
     *   安卓 7.0 及以后一般都有，6.0 及以前的车机系统常常被裁掉了。
     * @param vpnSelfAuthorizable 应用能不能自己完成 VPN 授权（[VpnConsent.canSelfAuthorize]）：
     *   授权页被裁掉时靠它补，有授权页的车机上用不着。
     * @param bluetoothAddressKnown 读不读得到车机蓝牙地址；null 表示没查，无线照常算能用。
     * @param missingWirelessPermissions 还没拿到、但车主补得上的权限，只作提示，不影响 [Capability.usable]。
     */
    fun summarize(
        sdkInt: Int,
        vpnPageAvailable: Boolean?,
        bluetoothAddressKnown: Boolean?,
        bluetoothField: Field,
        missingWirelessPermissions: List<String> = emptyList(),
        vpnSelfAuthorizable: Boolean = false,
    ): Summary {
        val hasPage = vpnPageAvailable ?: guessedVpnPage(sdkInt)
        val wired = if (hasPage || vpnSelfAuthorizable) {
            Capability.ok()
        } else {
            Capability(false, "车机系统里没有 VPN 授权页，数据线连接开通不了")
        }
        val premise = wirelessPremise(sdkInt, bluetoothAddressKnown, bluetoothField)
        val wireless = when (premise) {
            WirelessPremise.NONE -> Capability.ok()
            WirelessPremise.NO_HOTSPOT -> Capability(false, "这个安卓版本开不了本地热点（安卓 8.0 起才有），无线连接用不了")
            WirelessPremise.NO_BLUETOOTH -> Capability(false, "车机上没有蓝牙，无线连接用不了")
            WirelessPremise.BLUETOOTH_DISABLED -> Capability(false, "车机蓝牙被系统禁用了，无线连接用不了")
            WirelessPremise.NO_ADDRESS -> Capability(false, "读不到车机的蓝牙地址，无线连不上；请在设置里手动填上车机蓝牙地址")
        }
        return Summary(
            sdkInt = sdkInt,
            wired = wired,
            wireless = wireless,
            wirelessPremise = premise,
            bluetoothAddressKnown = bluetoothAddressKnown,
            bluetoothField = bluetoothField,
            missingWirelessPermissions = missingWirelessPermissions,
        )
    }

    private fun wirelessPremise(sdkInt: Int, addressKnown: Boolean?, field: Field): WirelessPremise = when {
        !WirelessNetworkPlan.hotspotSupported(sdkInt) -> WirelessPremise.NO_HOTSPOT
        field == Field.MISSING -> WirelessPremise.NO_BLUETOOTH
        field == Field.DISABLED -> WirelessPremise.BLUETOOTH_DISABLED
        addressKnown == false -> WirelessPremise.NO_ADDRESS
        else -> WirelessPremise.NONE
    }

    /** 没查 [VpnConsent.canShowPage] 时估一下：安卓 7.0 及以后一般都有授权页。 */
    fun guessedVpnPage(sdkInt: Int): Boolean = sdkInt >= 24

    /**
     * 设置页要不要专门列出「哪种连接方式用得了」：系统版本低到开不了本地热点（安卓 7.0 及以前），
     * 或者系统里没有 VPN 授权页。正常车机不显示这一组，免得占地方。
     */
    fun worthExplaining(context: Context): Boolean =
        !WirelessNetworkPlan.hotspotSupported(Build.VERSION.SDK_INT) || !VpnConsent.canShowPage(context)

    /** 从系统里读出 [summarize] 要的一切；查蓝牙出错都按 [Field.UNKNOWN] 处理，不抛。 */
    fun read(context: Context): Summary {
        val missing = WirelessPermissions.missing(Build.VERSION.SDK_INT) { isGranted(context, it) }
        return summarize(
            sdkInt = Build.VERSION.SDK_INT,
            vpnPageAvailable = VpnConsent.canShowPage(context),
            bluetoothAddressKnown = BluetoothAddressPolicy.normalize(DeviceInfo.systemBluetoothAddress()) != null,
            bluetoothField = fieldStatus(bluetoothAdapter(context)),
            missingWirelessPermissions = missing,
            vpnSelfAuthorizable = VpnConsent.canSelfAuthorize(context),
        )
    }

    /**
     * 本机的经典蓝牙适配器。安卓 12 起取它要「附近的设备」权限，没有时会抛；这时退回不带参数的旧写法
     * （安卓 11 及以前一直可用），都取不到就按「没有蓝牙」处理。
     */
    @Suppress("DEPRECATION")
    private fun bluetoothAdapter(context: Context): BluetoothAdapter? = try {
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    } catch (e: SecurityException) {
        null
    } ?: try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (e: RuntimeException) {
        null
    }

    private fun fieldStatus(adapter: BluetoothAdapter?): Field = when (BluetoothStatus.state(adapter)) {
        BluetoothStatus.State.UNSUPPORTED -> Field.MISSING
        BluetoothStatus.State.OFF -> Field.DISABLED
        BluetoothStatus.State.ON -> Field.OK
    }

    private fun isGranted(context: Context, permission: String): Boolean = try {
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    } catch (e: RuntimeException) {
        false
    }
}
