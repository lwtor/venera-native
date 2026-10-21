# ADR-0016: Installed source settings and transient login credentials

- Status: Accepted
- Date: 2026-10-21
- Related task: S3-02A1

## Context

The upstream source contract lets each script declare settings controls and optional account methods. The installed-source page needs to expose those declarations without adding source-specific UI. Settings may contain private values, while account passwords must not become part of the source package or persistent settings.

## Decision

- Read only declared settings metadata and render supported `input`, `select`, `switch`, and `callback` controls in the installed source configuration dialog.
- Persist setting values per source using Android Keystore-backed AES-GCM encryption. Source IDs scope the encrypted records; uninstall removes that source's private settings.
- Send account and password only as transient arguments to the declared `account.login` call. Never write the password to source data, settings, logs, or saved UI state.
- Keep source runtime login state (including tokens held by the source script) in memory. The user may need to log in again after the app process restarts. Persisting session tokens requires a separate, explicit credential-storage design.
- Surface login, logout, callback, and settings outcomes inside the open configuration dialog because the underlying screen is covered while the dialog is visible.

## Consequences

- Installed sources can supply their own controls and account behavior without per-source native UI.
- A settings change reloads the source runtime so the next call observes the new values; this can clear an in-memory session and require a new login.
- Password login is available only when the source declares the method. Browser/WebView login, cookie import, and durable sessions remain future work.
- The encrypted settings store depends on the device's Android Keystore key and is not portable across backup/restore or devices.

## References

- [Venera comic source guide](https://github.com/venera-app/venera/blob/master/doc/comic_source.md)
- [Official Picacg source example](https://raw.githubusercontent.com/venera-app/venera-configs/main/picacg.js)
