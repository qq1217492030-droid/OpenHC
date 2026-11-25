package io.github.gcbond.openhc.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 车机能不能用某种连接方式的判断（[DeviceCapabilities.summarize]）：不引用安卓类，按系统版本和查到的东西算。 */
class DeviceCapabilitiesTest {

    private val ok = DeviceCapabilities.Field.OK

    /** 安卓 6.0（API 23）的车机：系统没有 VPN 授权页，也开不了本地热点。 */
    @Test
    fun marshmallowCarWithNoVpnPageCanUseNothing() {
        val s = DeviceCapabilities.summarize(
            sdkInt = 23,
            vpnPageAvailable = false,
            bluetoothAddressKnown = true,
            bluetoothField = ok,
        )
        assertFalse(s.wired.usable)
        assertTrue(s.wired.reason, s.wired.reason.contains("VPN 授权页"))
        // 热点安卓 8.0 起才有：无线说清是这个原因，别让人以为是蓝牙的问题。
        assertFalse(s.wireless.usable)
        assertTrue(s.wireless.reason, s.wireless.reason.contains("热点"))
        assertEquals(DeviceCapabilities.WirelessPremise.NO_HOTSPOT, s.wirelessPremise)
        assertTrue(s.nothingUsable)
    }

    /**
     * 安卓 6.0 车机、没有授权页、但应用能自己完成 VPN 授权（平台同一把密钥签名的变体）：
     * 有线照样可用，别把这样的车机判成投不了屏。
     */
    @Test
    fun selfAuthorizableVpnKeepsWiredUsableWithoutThePage() {
        val s = DeviceCapabilities.summarize(
            sdkInt = 23,
            vpnPageAvailable = false,
            bluetoothAddressKnown = true,
            bluetoothField = ok,
            vpnSelfAuthorizable = true,
        )
        assertTrue(s.wired.usable)
        assertTrue(s.wired.reason.isEmpty())
        assertFalse(s.wireless.usable)
        assertFalse(s.nothingUsable)
    }

    /** 安卓 10 车机、有授权页：有线照常可用，不管有没有蓝牙。 */
    @Test
    fun wiredOnlyNeedsTheVpnPage() {
        val s = DeviceCapabilities.summarize(
            sdkInt = 29,
            vpnPageAvailable = true,
            bluetoothAddressKnown = null,
            bluetoothField = DeviceCapabilities.Field.MISSING,
        )
        assertTrue(s.wired.usable)
        assertTrue(s.wired.reason.isEmpty())
        assertFalse(s.wireless.usable)
        assertFalse(s.nothingUsable)
    }

    /** 无线要热点、蓝牙和车机地址三样：缺哪样说哪样，都齐了才算能用。 */
    @Test
    fun wirelessNeedsHotspotBluetoothAndAddress() {
        val base = DeviceCapabilities.summarize(29, true, true, ok)
        assertTrue(base.wireless.usable)
        assertTrue(base.wireless.reason.isEmpty())
        assertEquals(DeviceCapabilities.WirelessPremise.NONE, base.wirelessPremise)

        val noAddress = DeviceCapabilities.summarize(29, true, false, ok)
        assertFalse(noAddress.wireless.usable)
        assertTrue(noAddress.wireless.reason, noAddress.wireless.reason.contains("蓝牙地址"))
        assertEquals(DeviceCapabilities.WirelessPremise.NO_ADDRESS, noAddress.wirelessPremise)

        val bluetoothOff = DeviceCapabilities.summarize(29, true, true, DeviceCapabilities.Field.DISABLED)
        assertFalse(bluetoothOff.wireless.usable)
        assertTrue(bluetoothOff.wireless.reason, bluetoothOff.wireless.reason.contains("蓝牙"))
        assertEquals(DeviceCapabilities.WirelessPremise.BLUETOOTH_DISABLED, bluetoothOff.wirelessPremise)

        val noBluetooth = DeviceCapabilities.summarize(29, true, true, DeviceCapabilities.Field.MISSING)
        assertFalse(noBluetooth.wireless.usable)
        assertEquals(DeviceCapabilities.WirelessPremise.NO_BLUETOOTH, noBluetooth.wirelessPremise)

        // 没查地址（null）时不挡无线：地址在开始无线连接时才用得上。
        val unknownAddress = DeviceCapabilities.summarize(29, true, null, ok)
        assertTrue(unknownAddress.wireless.usable)
    }

    /** 没查授权页时按系统版本估：安卓 7.0 起一般都有。 */
    @Test
    fun vpnPageGuessFollowsSdk() {
        assertTrue(DeviceCapabilities.summarize(24, null, null, ok).wired.usable)
        assertFalse(DeviceCapabilities.summarize(23, null, null, ok).wired.usable)
    }

    /** 缺的权限只作提示，不把无线判成不能用。 */
    @Test
    fun missingPermissionsDoNotDisableWireless() {
        val s = DeviceCapabilities.summarize(
            sdkInt = 33,
            vpnPageAvailable = true,
            bluetoothAddressKnown = true,
            bluetoothField = ok,
            missingWirelessPermissions = listOf("android.permission.NEARBY_WIFI_DEVICES"),
        )
        assertTrue(s.wireless.usable)
        assertEquals("NEARBY_WIFI_DEVICES", s.missingPermissionNames())
    }
}