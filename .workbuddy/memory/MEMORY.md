# OpenHC 项目长期笔记

## 项目本质
- OpenHC：装在安卓车机端的 HiCar 接收端（开源，GPL-3.0），让华为/荣耀手机有线或无线投屏到车机。
- 模块：core:*（纯 Kotlin 协议层，禁 android.*）、core:session（编排）、platform（安卓适配：usb/net/media/bluetooth/wifi）、app（Compose 界面）、tools。
- 有线链路：手机切 USB 网卡（NCM/NTB16）→ platform.net 抢占接口、应答 ARP → IP 包经 VpnService 的 TUN 进系统 TCP 栈。**VpnService 授权是有线连接的硬前提**。

## 本机环境
- 只有 JDK 11，无 Android SDK；项目要 JDK 17 + SDK 36，本机无法跑 gradlew。改动靠静态核查。
- 沙箱不能直连网络（curl 报 exit 35），抓 AOSP 源码用 WebFetch（raw.githubusercontent 可用）。

## 用户车机（2026-11 起）
- GM p358 平台（上汽通用），TI Jacinto 6，Android 6.0.1（SDK 23），armeabi-v7a。
- 系统整包用 AOSP 公开 testkey 签名（framework-res 指纹 C8:A2:E9:BC:...:2A:B8），OTA 同键；刷机包分析报告在 D:/a/GM_refreshpkg_p358/车机系统安装包分析报告.md。
- 车机缺 com.android.vpndialogs（VPN 授权页），无法 ADB；无线不可行（安卓 8.0 才有 startLocalOnlyHotspot）→ 目标是有线。
- 车机自带注入应用「应用管家」（system UID、平台签名、HTTP 9898），可作 U 盘外的装包途径（项目代码里不得引用厂商应用类名——项目贡献规则）。

## 解决方案（已实现，待真机验证）
- 平台 testkey 签名的 OpenHC 变体 + 隐藏 API `VpnService.prepareAndAuthorize()` 自授权 VPN（免弹窗/免 ADB/root）；机制经 AOSP marshmallow-release 源码逐行核实（授权=AppOps OP_ACTIVATE_VPN，服务端查 CONTROL_VPN）。
- 代码入口：platform/net/VpnConsent.ensureAuthorized；构建入口：local.properties 的 openhc.platformSigning；工具：tools/platform-key/。
- 工作区曾有未提交的 0.2.8 半成品（docs/ANDROID6.md 与 VpnPageMissingTest 引用未实现的符号），2026-11-25 已补完。
