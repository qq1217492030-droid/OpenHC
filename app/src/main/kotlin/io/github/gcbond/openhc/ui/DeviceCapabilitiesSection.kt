package io.github.gcbond.openhc.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.startup.DeviceCapabilities

/**
 * 设置页「这台车机」一组：系统版本、数据线连接能不能用、无线连接能不能用，不能用的写清原因。
 *
 * 专门为裁剪过系统的车机准备（例如安卓 6.0 的比亚迪车机）：这种车机上 VPN 授权页被裁掉、也开不了本地热点，
 * 两种连接方式都用不了，界面得明说，别让车主反复点「开始」找原因。只在 [DeviceCapabilities.worthExplaining] 时显示。
 */
@Composable
internal fun DeviceCapabilitiesSection(summary: DeviceCapabilities.Summary) {
    val unusableHint = stringResource(R.string.settings_device_unusable_hint)
    SettingsGroup(stringResource(R.string.settings_device_title)) {
        GroupRow {
            RowDescription(stringResource(R.string.settings_device_system, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
        }
        GroupRow(spacing = 4.dp) {
            RowTitle(stringResource(R.string.settings_device_wired))
            capabilityLine(summary.wired, unusableHint)
        }
        GroupRow(spacing = 4.dp) {
            RowTitle(stringResource(R.string.settings_device_wireless))
            capabilityLine(summary.wireless, unusableHint)
            if (summary.wireless.usable && summary.missingWirelessPermissions.isNotEmpty()) {
                RowNote(stringResource(R.string.settings_device_permissions_missing, summary.missingPermissionNames()), warn = false)
            }
        }
        if (summary.nothingUsable) {
            GroupRow {
                RowNote(stringResource(R.string.settings_device_nothing))
            }
        }
    }
}

/** 能用写「可以」，不能用写原因加一句「这是车机系统决定的」。 */
@Composable
private fun capabilityLine(capability: DeviceCapabilities.Capability, unusableHint: String) {
    if (capability.usable) {
        RowDescription(stringResource(R.string.settings_device_usable))
    } else {
        RowNote(capability.reason)
        RowDescription(unusableHint)
    }
}