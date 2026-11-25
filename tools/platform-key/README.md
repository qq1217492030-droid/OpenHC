# tools/platform-key —— 用 AOSP 公开测试密钥给 OpenHC 签名

## 这是干什么的

有的车机系统整包用 **AOSP 公开测试密钥**（`test-keys`）签名——老车机的原厂包、第三方刷新包都不少见。
这类系统上，用同一把密钥签名的应用会被当成平台签名的应用：签名级权限自动授予，其中
**免用户确认的 VPN 授权**（`CONTROL_VPN`）正好补上它们缺 VPN 授权页、数据线连接开不了的短板
（为什么缺授权页就连不了，见 [`docs/ANDROID6.md`](../../docs/ANDROID6.md)）。

这个目录里的脚本把 AOSP 的 `platform.pk8` + `platform.x509.pem` 转成给 OpenHC 签名用的
PKCS12 密钥库。**密钥本身是公开的，谁都签得出同样的签名**——这既是它能用的原因，也是它的限制：
只在「系统真是用这把公开密钥签的」车机上有效，正常出厂（release 密钥）的车机上签了也没用，
一切照旧。

## 仓库里已经带了这份材料

`keys/` 下就是这三个文件，clone 下来就能直接用，不需要先准备任何东西：

| 文件 | 内容 |
|---|---|
| `keys/platform.pk8` | AOSP 平台测试私钥（DER 格式，公开） |
| `keys/platform.x509.pem` | 对应的证书（公开） |
| `keys/openhc-platform.p12` | 上面两个转好的 PKCS12（别名 `platform`，密码 `openhc`） |

用法：

```sh
./gradlew assembleRelease -PplatformSigning
```

不加 `-PplatformSigning` 时完全不影响普通构建（仍是调试密钥或你配的 `openhc.signing`）。
GitHub 上的自动化检查脚本 [check_public_tree.py](../check_public_tree.py) 对这三个路径单独放行，
其余任何 `.pk8` / `.pem` / `.p12` / 密钥库仍会被拦下——也就是说，**只有这把公开测试密钥可以进仓库**，
真凭据一律不行。

## 用之前先核对车机系统是不是测试密钥

两个来源任选其一核对：

- 看系统指纹里的 `ro.build.tags`：值是 `test-keys` 才有戏（`release-keys` 的车机用不上这套）；
- 对证书指纹：AOSP platform 测试密钥的 SHA-256 是
  `C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8`，
  用车机上 `/system/framework/framework-res.apk` 里的平台证书（或者厂商给的系统包分析结果）对得上才行。

## 生成密钥库

1. 拿两个文件（任选其一）：
   - 从本机已有的 AOSP 源码：`build/target/product/security/platform.pk8` 和 `platform.x509.pem`
     （安卓 10 起在 `build/make/target/product/security/`）；
   - 从 AOSP 镜像仓库下载（各分支里这两个文件内容一样，用固定分支最省事）：
     `https://raw.githubusercontent.com/aosp-mirror/platform_build/android-6.0.1_r81/target/product/security/platform.pk8`
     和同目录的 `platform.x509.pem`。
     注意这两个文件在 **`platform_build`** 仓库的 `target/product/security/` 下，不在 `platform_frameworks_base`；
     安卓 10 以后的分支在 `build/make/target/product/security/`。
2. 跑脚本（Windows 上在 Git Bash 里跑）：

   ```sh
   ./make-keystore.sh platform.pk8 platform.x509.pem
   ```

   脚本会先打印证书指纹让你核对，再生成 `openhc-platform.p12`（别名 `platform`，密码 `openhc`，
   都不是秘密，密钥本身是公开的）。

## 接进构建（用自己的密钥库时）

仓库自带的那份加 `-PplatformSigning` 就够用了；要换成你自己转出来的密钥库，在 OpenHC 目录的
`local.properties` 里写（路径指向你放配置文件的地方，文件不要进仓库）：

```properties
openhc.platformSigning=D:/keys/openhc-platform-signing.properties
```

那份配置文件里写五行（路径用绝对路径）：

```properties
storeFile=D:/keys/openhc-platform.p12
storeType=PKCS12
storePassword=openhc
keyAlias=platform
keyPassword=openhc
```

配好之后 `./gradlew assembleDebug`、`assembleRelease` 出的包都用它签（同时也可以配普通的
`openhc.signing`，两者互不影响，普通发布渠道照旧）。没配的机器上一切照旧，用调试密钥。

## 装到车机上

这类车机多半连不了 ADB，装包走车机自己的路子：APK 放 U 盘，用车机上的文件管理器或应用安装
工具装；装好第一次打开，把应用要的权限都允许。装上之后有线连接第一次点「开始」时，OpenHC 会
自己完成 VPN 授权（诊断日志里能看到「系统里有 VPN 授权页：否」和授权状态），不用再做任何配置。

升级安装认签名：换成这个变体后，之前装的调试包要先卸载再装（签名不同不能覆盖）。
