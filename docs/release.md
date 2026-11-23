# Release process

## Versioning

The app uses `MAJOR.MINOR.PATCH` Semantic Versioning. Fixes and maintenance increment PATCH, compatible features increment MINOR, and incompatible data or behavior changes increment MAJOR. Every commit after 1.0.0 increments the version, including documentation-only commits. `versionCode` is `MAJOR * 1,000,000 + MINOR * 1,000 + PATCH`; releases use matching `vMAJOR.MINOR.PATCH` tags.

## Signing and restoring on a new computer

The project release signing identity is pinned in `signing/release-cert.sha256` (public certificate fingerprint). The checked-in Debug key is separate. The release private key must never be committed to this public repository. The new signing identity is not compatible with APKs signed using the previous release keys; plan data migration before installation.

An encrypted signing bundle and, at the owner's explicit request, its plaintext recovery password are stored in the owner's **private** `lwtor/private-vault` repository under `projects/venera-native/`. Anyone with read access to that repository can decrypt and use the signing key. Restrict access, enable account 2FA, and retain independent backups: repository deletion, account loss, or loss of the recovery password can prevent recovery. Never publish the vault or its contents.

On each trusted Mac or Windows computer, install Python 3, [GitHub CLI](https://cli.github.com/) and [GnuPG](https://gnupg.org/download/), then authenticate GitHub CLI with an account that can read the private vault:

```text
gh auth login
```

From this repository, restore without displaying credentials:

```powershell
# Windows
python tools/restore-signing-vault.py
```

```bash
# macOS
python3 tools/restore-signing-vault.py
```

The script checks that the vault is private, downloads and decrypts the bundle locally, and installs `signing/venera-native-release-vault.keystore` and `keystore-vault.properties`. Both are Git-ignored; it refuses to overwrite different existing files. The original `keystore.properties` and original release key are left untouched. Credentials are not printed. The vault properties take precedence for Release builds; missing or incorrect credentials fail the build.

Build on either platform:

```powershell
.\gradlew.bat --no-configuration-cache :app:assembleRelease
```

```bash
./gradlew --no-configuration-cache :app:assembleRelease
```

Before Release work, Gradle checks that the configured private key matches the pinned fingerprint. After `:app:assembleRelease`, it verifies the final APK using Android SDK `apksigner` and rejects any signer mismatch. The artifact is `app/build/outputs/apk/release/app-release.apk`; check the application ID and version before publication. Do not bypass either verification. Avoid Gradle configuration cache for signing builds because configuration can contain credentials.

## Release checklist

1. Update app version and this repository's status and relevant documentation in the same task commit.
2. Build `:app:assembleDebug` and `:app:assembleRelease`; run release-stage tests and lint required by `AGENTS.md`.
3. Check `git diff --check`, review the staged diff, and confirm no credentials, user data, or build artifacts are staged.
4. Commit with a Conventional Commit message and push the branch and matching annotated version tag.
5. Create a GitHub Release for the tag, attach the signed APK named `venera-native-v<version>-<versionCode>.apk` (e.g. `venera-native-v1.6.0-1006000.apk`), and include concise release notes. The in-app updater parses the `versionCode` from this exact asset name to decide whether an update is available (ADR-0017), so do not rename the artifact after upload; keep the tag matching `v<version>` as the fallback signal.

The project license and resolved runtime dependency inventory are included in the APK at `assets/licenses/` and linked from the repository README.
