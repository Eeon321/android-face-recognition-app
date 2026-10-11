# Release signing

How this project signs release artifacts, and what to check before distributing one.

## Design: no secret is ever stored in the repository

| What | Where | Committed? |
|---|---|---|
| Keystore (`.jks`) | a directory **outside** this repository | no |
| Keystore password | a DPAPI blob (current-user scope) outside this repository | no |
| Non-secret settings (`storeFile`, `keyAlias`, `passwordFile`) | `keystore.properties` in the repo root | no (git-ignored) |

`keystore.properties` intentionally contains **no password**. That is the point: it is
the kind of file that gets pasted into an issue, shared with a teammate, or accidentally
force-added with `git add -f`, so it was made safe to do all three. The literal paths are
kept out of tracked files too — a committed location for signing material is a pointer
that tells anyone who can read the repo where to look.

## Password resolution order (`app/build.gradle`)

1. **`FACE_RELEASE_KEYSTORE_PASSWORD` environment variable** — for CI, where nothing
   touches disk.
2. **DPAPI blob** named by `passwordFile` in `keystore.properties`, protected with
   `DataProtectionScope.CurrentUser`. Only the Windows account that created it can
   decrypt it, on that machine.
3. **Neither available → debug signing**, with a printed notice:
   `No release signing key resolved: producing a debug-signed release build.`

### Why DPAPI and not a plaintext file

A plaintext password under a directory inheriting the default Windows ACL was readable by
`BUILTIN\Users` and `NT AUTHORITY\Authenticated Users` — i.e. by any local account on the
machine, and by ordinary backups and directory listings. DPAPI current-user scope closes
all of those.

**Honest limits of DPAPI — read these before trusting it:**

- It does **not** protect against a process already running as you. Anything you execute
  under your account can call `ProtectedData::Unprotect` just as the build does.
- It does **not** protect against an administrator of this machine.
- It is **bound to this user on this machine**: copying the `.dpapi` file to another
  computer yields garbage, not a password. To migrate, decrypt here and transfer the
  password through a password manager.
- It is not a hardware-backed keystore. For a production signing key, use an HSM or a
  cloud signing service.

## Verifying a release artifact's certificate — do this every time

The debug fallback is convenient and dangerous in the same way: it produces an
installable, well-formed release APK that is signed with the **debug** key. Check before
distributing:

```bash
build-tools/34.0.0/apksigner.bat verify --print-certs app/build/outputs/apk/release/app-release.apk
```

- Signed correctly: `DN: CN=Face Recognition App, ...`
- Silently debug-signed: `DN: C=US, O=Android, CN=Android Debug` ← **do not ship this**;
  users who install it can never be upgraded to the real key.

For an `.aab`, the signature lives in `META-INF/` inside the bundle:

```bash
unzip -l app/build/outputs/bundle/release/app-release.aab | grep META-INF
```

## Losing the key is unrecoverable

The keystore and password together are the only way to sign future versions of an
installed app. If either is lost, every existing installation is stranded — you would
have to publish under a new package name and ask everyone to reinstall. Keep an offline
backup of both, and verify the backup's checksum after copying it.

## A note on this app's signing key

This key was generated for local development with placeholder certificate details. If
this code ever becomes a real published app, generate a fresh upload key, register it
with Google Play's app signing, and store the signing key credential in Play's managed
keystore or an HSM rather than on a developer workstation.
