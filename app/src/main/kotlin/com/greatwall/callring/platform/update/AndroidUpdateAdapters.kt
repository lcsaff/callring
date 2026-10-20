package com.greatwall.callring.platform.update

import com.greatwall.callring.contracts.runtime.*
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.greatwall.callring.contracts.runtime.AppReleaseInfo
import com.greatwall.callring.contracts.runtime.AppVersionInfo
import java.io.File

internal data class UpdateBuildInfo(
    val current: AppVersionInfo,
    val packageName: String,
    val updatable: Boolean,
    val deviceSdk: Int,
)

internal data class ApkInspection(
    val packageName: String,
    val versionCode: Long,
    val minSdk: Int,
    val certificateSha256: String?,
)

internal fun interface ApkInspector {
    fun inspect(file: File): ApkInspection?
}

internal fun interface InstalledCertificateReader {
    fun sha256(): String?
}

/** 安装器是唯一触碰系统安装界面的地方；测试注入 fake，不执行真实安装。 */
internal interface UpdateInstaller {
    fun canInstall(): Boolean
    fun openInstallPermission()
    fun launch(apk: File): Boolean
}

/** 安装前完整校验：大小、摘要、包名、versionCode、minSdk 与签名证书。返回 null 表示通过。 */
internal fun verifyUpdateFile(
    file: File,
    latest: AppReleaseInfo,
    currentPackage: String,
    deviceSdk: Int,
    inspector: ApkInspector,
    installedCertificate: InstalledCertificateReader,
): String? {
    if (!file.isFile || file.length() != latest.sizeBytes) return UpdateReasons.VERIFY_FAILED
    if (!sha256File(file).equals(latest.sha256, ignoreCase = true)) return UpdateReasons.VERIFY_FAILED
    val inspection = inspector.inspect(file) ?: return UpdateReasons.VERIFY_FAILED
    if (inspection.packageName != currentPackage) return UpdateReasons.VERIFY_FAILED
    if (inspection.versionCode != latest.versionCode) return UpdateReasons.VERIFY_FAILED
    if (inspection.minSdk > deviceSdk) return UpdateReasons.UNSUPPORTED_DEVICE
    val installed = installedCertificate.sha256() ?: return UpdateReasons.VERIFY_FAILED
    val archive = inspection.certificateSha256 ?: return UpdateReasons.VERIFY_FAILED
    if (!archive.equals(installed, ignoreCase = true)) return UpdateReasons.VERIFY_FAILED
    return null
}

internal fun defaultUpdateBuildInfo(context: Context): UpdateBuildInfo {
    val manager = context.packageManager
    val info = manager.getPackageInfo(context.packageName, 0)
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val packageName = context.packageName
    val updatable = packageName == CALLRING_PACKAGE && !debuggable && !packageName.endsWith(".backendqa")
    return UpdateBuildInfo(
        current = AppVersionInfo(info.versionName.orEmpty(), info.longVersionCode),
        packageName = packageName,
        updatable = updatable,
        deviceSdk = android.os.Build.VERSION.SDK_INT,
    )
}

internal class AndroidApkInspector(private val manager: PackageManager) : ApkInspector {
    override fun inspect(file: File): ApkInspection? = runCatching {
        val info = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return null
        val certificate = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let(::certificateSha256)
        ApkInspection(
            packageName = info.packageName,
            versionCode = info.longVersionCode,
            minSdk = info.applicationInfo?.minSdkVersion ?: 0,
            certificateSha256 = certificate,
        )
    }.getOrNull()
}

internal class AndroidInstalledCertificate(
    private val manager: PackageManager,
    private val packageName: String,
) : InstalledCertificateReader {
    override fun sha256(): String? = runCatching {
        val info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let(::certificateSha256)
    }.getOrNull()
}

internal class AndroidUpdateInstaller(private val context: Context) : UpdateInstaller {
    override fun canInstall(): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    override fun openInstallPermission() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun launch(apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        true
    }.getOrDefault(false)
}
