package com.greatwall.callring.platform.update

import com.greatwall.callring.application.api.AppVersionInfo
import com.greatwall.callring.application.api.UpdatePhase
import org.junit.Assert.*
import org.junit.Test

class UpdateContractTest {
    private val apk = ByteArray(4096) { (it % 251).toByte() }
    private val manifest = parseUpdateManifest(manifestJson(apk))
    private val release = parseLatestRelease(releaseJson(apkSize = apk.size.toLong()))
    private val installed = InstalledApp(CALLRING_PACKAGE, "0.3.0", 3)

    @Test fun parsesReleaseAndManifestWithStableFields() {
        assertEquals("v0.4.0", release.tagName)
        assertEquals(2, release.assets.size)
        assertEquals("更新说明", release.notes)
        assertNotNull(release.publishedAtEpochMillis)
        assertEquals(1, manifest.schemaVersion)
        assertEquals(4, manifest.versionCode)
        assertEquals(29, manifest.minSdk)
        assertEquals(sha256Hex(apk), manifest.sha256)
    }

    @Test fun updateAvailableOnlyWhenRemoteVersionCodeIsHigher() {
        val available = evaluateCheck(release, manifest, installed, 33)
        assertEquals(UpdatePhase.UPDATE_AVAILABLE, available.phase)
        assertEquals(4L, available.latest?.versionCode)
        assertEquals("https://example.test/callring-0.4.0.apk", available.latest?.downloadUrl)

        val sameCode = evaluateCheck(release, manifest, InstalledApp(CALLRING_PACKAGE, "9.9.9", 4), 33)
        assertEquals(UpdatePhase.UP_TO_DATE, sameCode.phase)
        assertNull(sameCode.latest)

        val higherInstalled = evaluateCheck(release, manifest, InstalledApp(CALLRING_PACKAGE, "0.2.0", 5), 33)
        assertEquals(UpdatePhase.UP_TO_DATE, higherInstalled.phase)
    }

    @Test fun tagAssetNameAndApplicationIdMustMatchTheManifest() {
        fun assertNoInstall(release: GitHubRelease = this.release, manifest: UpdateManifest = this.manifest) {
            val result = evaluateCheck(release, manifest, installed, 33)
            assertEquals(UpdatePhase.NO_INSTALLABLE, result.phase)
            assertEquals(UpdateReasons.NO_INSTALLABLE, result.failureReason)
        }
        assertNoInstall(release = parseLatestRelease(releaseJson(tagName = "v0.4")))
        assertNoInstall(manifest = manifest.copy(assetName = "callring-0.4.0-unsigned.apk"))
        assertNoInstall(manifest = manifest.copy(applicationId = "com.example.other"))
        assertNoInstall(release = parseLatestRelease(releaseJson(includeApk = false)))
        assertNoInstall(release = parseLatestRelease(releaseJson(apkSize = 123)))
        assertNoInstall(release = parseLatestRelease(releaseJson(draft = true)))
        assertNoInstall(release = parseLatestRelease(releaseJson(prerelease = true)))
    }

    @Test fun minSdkAboveDeviceIsUnsupportedInsteadOfDownloadable() {
        val result = evaluateCheck(release, manifest.copy(minSdk = 34), installed, 33)
        assertEquals(UpdatePhase.UNSUPPORTED_DEVICE, result.phase)
        assertEquals(UpdateReasons.UNSUPPORTED_DEVICE, result.failureReason)
        assertNull(result.latest)
    }

    @Test fun manifestRejectsUnsafeOrIncompleteData() {
        fun rejected(json: String) = assertThrows(UpdateFormatException::class.java) { parseUpdateManifest(json) }
        rejected(manifestJson(apk, assetName = "../escape.apk"))
        rejected(manifestJson(apk, shaOverride = "not-a-sha"))
        rejected(manifestJson(apk, versionCode = 0))
        rejected("{}")
        rejected("not json")
    }

    @Test fun sha256MatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".toByteArray()))
    }

    @Test fun persistedStateRoundTripsWithoutBusinessData() {
        val state = PersistedUpdateState(
            etag = "etag-1",
            phase = UpdatePhase.VERIFIED,
            failureReason = null,
            detail = "sig",
            checkedAtEpochMillis = 1234,
            latest = evaluateCheck(release, manifest, installed, 33).latest,
            downloadedBytes = apk.size.toLong(),
            totalBytes = apk.size.toLong(),
            fileName = "callring-0.4.0.apk",
            verified = true,
        )
        assertEquals(state, decodeUpdateState(encodeUpdateState(state)))
        assertNull(decodeUpdateState("broken"))
    }

    @Test fun versionInfoOnlyUsesVersionCodeForComparison() {
        // Same versionCode with a larger remote versionName is not an update.
        val same = evaluateCheck(release, manifest.copy(versionName = "0.4.0"), InstalledApp(CALLRING_PACKAGE, "0.1.0", 4), 33)
        assertEquals(UpdatePhase.UP_TO_DATE, same.phase)
        assertEquals(AppVersionInfo("0.3.0", 3).versionCode, installed.versionCode)
    }
}
