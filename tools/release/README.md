# Release tooling (`tools/release/`)

Local, fail-fast helpers for the app-update release pipeline described in
[`docs/superpowers/specs/2026-10-19-app-update-design.md`](../../docs/superpowers/specs/2026-10-19-app-update-design.md)
§三/§六/§七 and plan P3 S11. They never upload anything and never read the network;
CI orchestration lives in `.github/workflows/release.yml`.

| Script | Purpose |
| --- | --- |
| `version_tool.py` | Validate or write the single version source `version.properties` (format, monotonic `versionCode`, tag = `v<versionName>`). |
| `generate_release_metadata.py` | Stage the signed APK as `callring-<versionName>.apk` and generate `update.json` + `SHA256SUMS.txt` from that one APK. |
| `verify_release.py` | Verify a staged release directory: tag, required attachments, APK ⇔ `update.json` ⇔ `SHA256SUMS.txt` ⇔ `version.properties`, APK package/version/minSdk, debuggable/backendQa rejection, optional signer fingerprint, zipalign and `/releases/latest` tag. |
| `selftest.py` | Build a valid fixture from the local release APK and prove that every required failure sample returns a non-zero exit code. |
| `check_workflows.py` | Guard that `.github/workflows/*.yml` only reference existing `tools/release/` files, registered subcommands and supported `--flags` (runs in both workflows before any build). |
| `apk_info.py` | Shared helper: `aapt2 dump badging` parsing, SHA-256/size, build-tools discovery (`ANDROID_HOME`/`ANDROID_SDK_ROOT`, or `AAPT2`/`APKSIGNER`/`ZIPALIGN`, or `--aapt2`/`--apksigner`/`--zipalign`). |

Exit codes are `0` on success and non-zero on any violation; messages are printed as
`[FAIL] <reason>` on stderr. Python 3.8+ is required (no third-party packages).

## Version source

`version.properties` at the repository root is the only version source; Gradle
(`root build.gradle` → `app/build.gradle`) reads it too.

```powershell
# Validate the current file (format only)
python tools/release/version_tool.py check

# Prepare a release: versionCode must exceed the highest published one
python tools/release/version_tool.py set --code 3 --name 0.3.0 --published-max 2

# Release gate: parsed tag must be v<versionName>
python tools/release/version_tool.py check --published-max 2 --tag v0.3.0
```

Rejected inputs include two-part names (`0.3`), `v`-prefixed names (`v0.3.0`),
names that go backwards versus `--published-name`, and non-increasing `versionCode`
(including the same code as `--published-max`).

## Local release dry run

```powershell
.\gradlew.bat :app:assembleRelease --console=plain
$stage = Join-Path $env:TEMP 'callring-release'
Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
python tools/release/generate_release_metadata.py `
    --apk app/build/outputs/apk/release/app-release.apk --out-dir $stage
python tools/release/verify_release.py --release-dir $stage --tag v0.3.0 --published-max 2 `
    --expected-cert-sha256 5a901cad57f5590483f362e4752ca8f4a56197340b71d2632ded0195bf8a24b4 `
    --check-alignment
```

The staged directory then contains exactly the three Release attachments:
`callring-<versionName>.apk`, `update.json`, `SHA256SUMS.txt`.

## Failure-sample self test

Requires a local release APK (`:app:assembleRelease`). Covered samples: valid
fixture (exit 0), wrong tag, missing APK/`update.json`, `update.json` digest
mismatch, `SHA256SUMS.txt` digest mismatch, size mismatch, package mismatch,
backendQa `versionName`, unexpected extra file, non-increasing `versionCode`,
debuggable APK, plus `version_tool.py` format/monotonicity samples.

```powershell
python tools/release/selftest.py
```

## Workflow reference guard

`.github/workflows/*.yml` calls these scripts directly, so a rename or flag change
would only fail on the first CI run. `check_workflows.py` statically verifies that
every referenced `tools/release/` path exists (globs included), that each called
script has the requested argparse subcommand and that every `--flag` appears as a
literal in that script.

```powershell
python tools/release/check_workflows.py
```

CI (`.github/workflows/release.yml`) only runs the generation/verification steps
after it has confirmed that `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`
and `KEY_PASSWORD` are configured; missing Secrets abort the job before Gradle
runs. The upload/publish job is skipped unless the `publish` input is checked.
