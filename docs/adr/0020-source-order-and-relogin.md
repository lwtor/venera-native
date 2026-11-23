# ADR-0020: Installed source order and remembered re-login credentials

- Status: Accepted
- Date: 2026-11-23
- Related task: U-12

## Context

The installed source list should be scannable without opening a dialog, and users need its order to match the source grouping in aggregate search. Account validation can run while settings are visible. The previous login flow intentionally discarded password arguments, which made re-login require typing credentials again.

## Decisions

- Keep installed sources in one list. Rows have collapsed and expanded states; expanded content contains enable, update, remove, account controls, and source-declared settings.
- A long press selects a source for order adjustment. Explicit up/down actions persist the reordered source IDs in the installed package index. The repository emits its existing revision signal so consumers reload the source order.
- Expanding an account-capable source starts one validation request in the background. Concurrent checks are deduplicated per source. A manual check during an automatic check joins that request and shows loading only on the manual check control. Invalid or failed checks are marked on the collapsed row.
- After a successful password login, store the account and password in a separate, source-scoped record encrypted with the existing Android Keystore AES-GCM key and authenticated data purpose. Do not place credentials in source setting overrides, exported script `saveData`, backups, logs, or Compose saved state. Re-login reads the encrypted record only inside the repository and sends it as transient arguments to `account.login`.
- A user can replace saved credentials by submitting another successful login. Explicit logout and source uninstall clear the saved credentials. Existing installs without saved credentials require one interactive login to seed the encrypted record.
- Failure to persist a credential record must not override a successful `account.login` result. The UI reports that login succeeded but automatic re-login credentials were not saved.

## Consequences

- Source order survives app restarts and is inherited by aggregate search because it uses the ordered repository list.
- Validation does not block access to expanded settings. Sources without validation support remain unverified and do not get a false failure warning.
- The password is now retained encrypted on-device until logout, credential replacement, uninstall, app data removal, or Android Keystore loss. It is device-bound and is not backed up or migrated.
- A malicious or compromised source can still receive login arguments when the user explicitly requests password login, as required by the source protocol. Credentials are not supplied on ordinary source calls.
