package io.github.gcbond.openhc.platform.usb

/** 插在车机上的设备从有线投屏的角度看处于哪种状态。 */
enum class PhoneUsbMode {
    /** 不是华为 / 荣耀设备，不理。 */
    NOT_A_PHONE,

    /** 普通状态：可以发 carInfo 让它切成 USB 网卡。 */
    NORMAL,

    /** 开着 USB 调试：手机不响应 carInfo，要请用户在手机上关掉 USB 调试。 */
    USB_DEBUGGING,

    /** 已切成 USB 网卡（CDC NCM）。 */
    NCM,

    /** 华为 / 荣耀设备，但产品号不认识（例如别的 USB 模式），不发 carInfo。识别放宽后 [HuaweiPhone.classify] 不再返回它，值保留给老的使用方。 */
    UNSUPPORTED,
}

/**
 * 识别华为手机的 USB 状态。规则都来自实测：
 * - 厂商号：华为 0x12D1、荣耀 0x339B；
 * - 普通状态的产品号实测是 0x107D / 0x107E / 0x107F（接口为 MTP、大容量存储等）；系统更新会带出
 *   新的产品号（在安卓 6.0 的老车机上接新手机就遇到了），认不出来就只能一直「等待插线」，
 *   所以普通状态不再按产品号卡：除下面两种状态外都按普通状态处理，发给认不出的设备 carInfo
 *   也只是这次插入无效，没有别的副作用；
 * - 手机开着 USB 调试时产品号是 0x5000，这时发 carInfo 手机不理；
 * - 手机收到 carInfo 后重新枚举成 USB 网卡：多出 CDC NCM 控制口（类 2 / 子类 13）和 CDC 数据口（类 10），
 *   产品号不变。第一次连某台车机时，手机上会弹连接确认，用户点了「连接」才切。
 */
object HuaweiPhone {
    const val VENDOR_HUAWEI = 0x12D1
    const val VENDOR_HONOR = 0x339B

    /** 实测过的普通状态产品号，仅作参考；识别已不按它卡（见 [classify]）。 */
    val NORMAL_PRODUCT_IDS: Set<Int> = setOf(0x107D, 0x107E, 0x107F)
    const val PRODUCT_USB_DEBUGGING = 0x5000

    const val CLASS_COMM = 2
    const val SUBCLASS_NCM = 13
    const val CLASS_CDC_DATA = 10

    fun isHuaweiVendor(vendorId: Int): Boolean = vendorId == VENDOR_HUAWEI || vendorId == VENDOR_HONOR

    fun classify(device: UsbDeviceDescription): PhoneUsbMode {
        if (!isHuaweiVendor(device.vendorId)) return PhoneUsbMode.NOT_A_PHONE
        if (hasNcmInterfaces(device)) return PhoneUsbMode.NCM
        if (device.productId == PRODUCT_USB_DEBUGGING) return PhoneUsbMode.USB_DEBUGGING
        return PhoneUsbMode.NORMAL
    }

    /** 有 CDC NCM 控制口和 CDC 数据口。 */
    fun hasNcmInterfaces(device: UsbDeviceDescription): Boolean =
        device.interfaces.any { it.interfaceClass == CLASS_COMM && it.interfaceSubclass == SUBCLASS_NCM } &&
            device.interfaces.any { it.interfaceClass == CLASS_CDC_DATA }
}
