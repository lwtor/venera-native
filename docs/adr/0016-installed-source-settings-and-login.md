# ADR-0016: Installed source settings and transient login credentials

- Status: Accepted
- Date: 2026-10-21
- Related task: S3-02A1

## Context

The upstream source contract lets each script declare settings controls and optional account methods. The installed-source page needs to expose those declarations without adding source-specific UI. Settings may contain private values, while account passwords must not become part of the source package or persistent settings.

## Decision

- Read only declared settings metadata and render supported `input`, `select`, `switch`, and `callback` controls in the installed source configuration dialog.
- Persist setting values per source using Android Keystore-backed AES-GCM encryption. Source IDs scope the encrypted records; uninstall removes that source's private settings.
- Persist the source script's `saveData` object separately from setting overrides, encrypted with the same per-device Keystore key and source-scoped authenticated data. After each successful source call the runtime exports a bounded object snapshot; a rebuilt runtime receives it before the source script is instantiated.
- Send account and password only as transient arguments to the declared `account.login` call. Never write the password to source data, settings, logs, or saved UI state.
- Filter nested keys containing `password`, `passwd`, or `pwd` from exported data before host persistence. Source scripts still receive the original values in their live runtime, but these fields are not written to disk.
- Keep session tokens in the source script's own `saveData` state; persist that state only as encrypted host data. This allows sources such as Picacg to restore authentication after process death without persisting the password used to obtain the token.
- Surface login, logout, callback, and settings outcomes inside the open configuration dialog because the underlying screen is covered while the dialog is visible.

## Consequences

- Installed sources can supply their own controls and account behavior without per-source native UI.
- A settings change reloads the source runtime so the next call observes the new values; its encrypted `saveData` snapshot is restored, unless the source itself changes or clears it.
- Password login is available only when the source declares the method. Browser/WebView login, cookie import, and durable sessions remain future work.
- The encrypted settings/session store depends on the device's Android Keystore key and is not portable across backup/restore or devices.
- If source data contains a password under a field whose name does not identify it as a password, it cannot be reliably distinguished from a session token; the host filters common password field names and never stores login arguments directly.

## References

- [Venera comic source guide](https://github.com/venera-app/venera/blob/master/doc/comic_source.md)
- [Official Picacg source example](https://raw.githubusercontent.com/venera-app/venera-configs/main/picacg.js)
