# Release process

## Versioning

The app uses `MAJOR.MINOR.PATCH` Semantic Versioning. Fixes and maintenance increment PATCH, compatible features increment MINOR, and incompatible data or behavior changes increment MAJOR. Every commit after 1.0.0 increments the version, including documentation-only commits. `versionCode` is `MAJOR * 1,000,000 + MINOR * 1,000 + PATCH`; releases use matching `vMAJOR.MINOR.PATCH` tags.

## Signing

Release signing is separate from the checked-in shared Debug key. Create a private release key with JDK 17 `keytool` and keep both the keystore and `keystore.properties` outside version control. The Gradle build reads these properties:

```properties
storeFile=signing/venera-native-release.keystore
storePassword=<private store password>
keyAlias=venera-native-release
keyPassword=<private key password>
```

`*.keystore` and `keystore.properties` are ignored by Git. Back up the Release keystore and credentials in a secure password manager or encrypted offline storage; losing the signing identity prevents in-place updates of this application. Never use the shared Debug key to sign a public release. A Release APK cannot update an install signed by the Debug key; uninstalling would remove app data, so keep the Debug install until you have separately backed it up.

Build the signed APK from the repository root:

```powershell
.\gradlew.bat :app:assembleRelease
```

The artifact is `app/build/outputs/apk/release/app-release.apk`. Verify it with Android SDK Build Tools `apksigner verify --print-certs` and inspect its `versionName`, `versionCode`, and application ID before publishing.

## Release checklist

1. Update app version and this repository's status/implementation documents in the same task commit.
2. Build `:app:assembleDebug` and `:app:assembleRelease`; run release-stage tests and lint required by `AGENTS.md`.
3. Check `git diff --check`, review the staged diff, and confirm no credentials, user data, or build artifacts are staged.
4. Commit with a Conventional Commit message and push the branch and matching annotated version tag.
5. Create a GitHub Release for the tag, attach the signed APK named `venera-native-<version>.apk`, and include concise release notes.

The project license and resolved runtime dependency inventory are included in the APK at `assets/licenses/` and linked from the repository README.
