package io.github.gcbond.openhc.platform.usb

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HuaweiPhoneTest {

    private fun iface(index: Int, id: Int, alt: Int, cls: Int, sub: Int, vararg endpoints: UsbEndpointDescription) =
        UsbInterfaceDescription(index, id, alt, cls, sub, 0, endpoints.toList())

    private fun endpoint(index: Int, address: Int, type: Int, maxPacket: Int = 512) =
        UsbEndpointDescription(index, address, type, maxPacket)

    /** 普通状态：MTP + 大容量存储（实测的接口组成，端点是合成的）。 */
    private fun normal(vendor: Int = 0x12D1, product: Int = 0x107E) = UsbDeviceDescription(
        vendor, product, 0,
        listOf(
            iface(0, 0, 0, 6, 1, endpoint(0, 0x81, 2), endpoint(1, 0x01, 2), endpoint(2, 0x82, 3, 28)),
            iface(1, 1, 0, 8, 6, endpoint(0, 0x83, 2), endpoint(1, 0x02, 2)),
        ),
    )

    /** USB 网卡状态：NCM 控制口 + 数据口两个备用设置（实测的组成）。 */
    private fun ncm(product: Int = 0x107E) = UsbDeviceDescription(
        0x12D1, product, 239,
        listOf(
            iface(0, 0, 0, 2, 13, endpoint(0, 0x82, 3, 16)),
            iface(1, 1, 0, 10, 0),
            iface(2, 1, 1, 10, 0, endpoint(0, 0x81, 2), endpoint(1, 0x01, 2)),
        ),
    )

    @Test
    fun classifiesPhoneStates() {
        assertEquals(PhoneUsbMode.NORMAL, HuaweiPhone.classify(normal()))
        assertEquals(PhoneUsbMode.NORMAL, HuaweiPhone.classify(normal(product = 0x107D)))
        assertEquals(PhoneUsbMode.NORMAL, HuaweiPhone.classify(normal(product = 0x107F)))
        assertEquals(PhoneUsbMode.NORMAL, HuaweiPhone.classify(normal(vendor = 0x339B)), "荣耀")
        assertEquals(PhoneUsbMode.USB_DEBUGGING, HuaweiPhone.classify(normal(product = 0x5000)))
        assertEquals(PhoneUsbMode.NORMAL, HuaweiPhone.classify(normal(product = 0x1234)), "产品号不认识也按普通状态处理：系统更新会带出新产品号")
        assertEquals(PhoneUsbMode.NOT_A_PHONE, HuaweiPhone.classify(normal(vendor = 0x1D6B)))
        assertEquals(PhoneUsbMode.NCM, HuaweiPhone.classify(ncm()), "切成网卡后产品号不变，看接口")
        assertEquals(PhoneUsbMode.NCM, HuaweiPhone.classify(ncm(product = 0x5000)))
    }

    @Test
    fun selectsNcmInterfacesAndEndpoints() {
        val selection = assertNotNull(NcmInterfaces.select(ncm()))
        assertEquals(0, selection.control.index)
        assertEquals(1, assertNotNull(selection.dataIdle).index, "数据口没有端点的备用 0")
        assertEquals(2, selection.dataActive.index)
        assertEquals(1, selection.dataActive.alternateSetting)
        assertEquals(0x81, selection.bulkIn.address)
        assertEquals(0x01, selection.bulkOut.address)
        assertEquals(0x82, assertNotNull(selection.notification).address)
    }

    @Test
    fun selectionNeedsControlAndBothBulkEndpoints() {
        assertNull(NcmInterfaces.select(normal()))
        val noOut = ncm().let { d ->
            d.copy(interfaces = d.interfaces.map { if (it.index == 2) it.copy(endpoints = it.endpoints.take(1)) else it })
        }
        assertNull(NcmInterfaces.select(noOut))
        val noControl = ncm().let { d -> d.copy(interfaces = d.interfaces.drop(1)) }
        assertNull(NcmInterfaces.select(noControl))
        val noIdle = ncm().let { d -> d.copy(interfaces = d.interfaces.filter { it.index != 1 }) }
        assertNull(assertNotNull(NcmInterfaces.select(noIdle)).dataIdle, "没有备用 0 时照样能用")
    }
}
