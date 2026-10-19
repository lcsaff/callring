package com.greatwall.callring.platform.update

import com.greatwall.callring.application.api.AppReleaseInfo
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UpdateVerificationTest {
    private val bytes = "fake-apk-bytes".toByteArray()
    private val file = File.createTempFile("callring-update", ".apk").apply { writeBytes(bytes) }
    private val latest = AppReleaseInfo(
        versionName = "0.4.0",
        versionCode = 4,
        publishedAtEpochMillis = 0,
        notes = "",
        assetName = "callring-0.4.0.apk",
        sizeBytes = bytes.size.toLong(),
        sha256 = sha256Hex(bytes),
        downloadUrl = "https://example.test/callring-0.4.0.apk",
    )
    private val inspection = ApkInspection(CALLRING_PACKAGE, 4, 29, "cert-current")
    private val installed = InstalledCertificateReader { "cert-current" }

    private fun verify(
        release: AppReleaseInfo = latest,
        inspector: ApkInspector = ApkInspector { inspection },
        certificate: InstalledCertificateReader = installed,
        deviceSdk: Int = 33,
        currentPackage: String = CALLRING_PACKAGE,
    ) = verifyUpdateFile(file, release, currentPackage, deviceSdk, inspector, certificate)

    @Test fun matchingSizeDigestPackageVersionAndSignaturePasses() {
        assertNull(verify())
    }

    @Test fun truncatedFileFailsBeforeReadingCertificates() {
        assertEquals(UpdateReasons.VERIFY_FAILED, verify(release = latest.copy(sizeBytes = bytes.size + 1L)))
        assertEquals(UpdateReasons.VERIFY_FAILED, verify(release = latest.copy(sha256 = "0".repeat(64))))
    }

    @Test fun packageVersionAndSignatureMismatchesAreRejected() {
        assertEquals(UpdateReasons.VERIFY_FAILED, verify(inspector = ApkInspector { inspection.copy(packageName = "com.example.other") }))
        assertEquals(UpdateReasons.VERIFY_FAILED, verify(inspector = ApkInspector { inspection.copy(versionCode = 5) }))
        assertEquals(UpdateReasons.VERIFY_FAILED, verify(inspector = ApkInspector { inspection.copy(certificateSha256 = "cert-other") }))
        assertEquals(UpdateReasons.VERIFY_FAILED, verify(inspector = ApkInspector { inspection.copy(certificateSha256 = null) }))
        assertEquals(UpdateReasons.VERIFY_FAILED, verify(certificate = InstalledCertificateReader { null }))
    }

    @Test fun higherMinSdkAndDifferentCurrentPackageAreRejected() {
        assertEquals(UpdateReasons.UNSUPPORTED_DEVICE, verify(inspector = ApkInspector { inspection.copy(minSdk = 34) }))
        assertEquals(UpdateReasons.VERIFY_FAILED, verify(currentPackage = "com.example.other"))
    }

    @Test fun inspectionFailureIsAVerifyFailure() {
        assertEquals(UpdateReasons.VERIFY_FAILED, verify(inspector = ApkInspector { null }))
    }

    @Test fun missingFileIsAVerifyFailure() {
        val missing = File(file.parentFile, "missing-${System.nanoTime()}.apk")
        assertEquals(UpdateReasons.VERIFY_FAILED, verifyUpdateFile(missing, latest, CALLRING_PACKAGE, 33, ApkInspector { inspection }, installed))
    }

    @Test fun tempFileIsCleanedUp() {
        assertTrue(file.delete())
        assertFalse(file.exists())
    }
}
