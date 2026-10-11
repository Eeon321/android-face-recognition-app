# Shipping policy — pushing and releasing this code

Rules for getting code from a working tree to GitHub, and for turning a commit into an
installable release. Written for this project, but the gates are generic.

Everything marked **[verified]** was executed and observed on this repository. Everything
marked **[policy]** is a rule to follow that has not yet needed to fire. Anything I could
not confirm is labelled as such rather than presented as fact.

---

## 0. The short version

1. Never let a secret, a signing key, or a signing-key **location** enter a commit.
2. Scan **history**, not just the working tree, before the first push of a branch.
3. `git push` fast-forward only. `--force-with-lease` with an **explicit** expected value,
   never bare `--force`.
4. Verify an artifact's **certificate** before shipping it. A "release" build can be
   silently debug-signed.
5. Every shipped artifact gets an **annotated tag** and a **monotonic `versionCode`**.
6. Releases are GitHub Releases with checksums — never committed binaries.

---

## 1. Definitions

| Term | Meaning here |
|---|---|
| **Push** | Moving commits to `origin`. Publishes history; assume it is permanent. |
| **Ship** | Producing a signed artifact a third party can install. |
| **Release** | Tagged commit + published artifact + release notes + checksum. |

---

## 2. Gate 1 — before committing

### 2.1 Secrets never enter the repository

- **Never commit**: keystores (`.jks`/`.keystore`), password files, `.dpapi` blobs,
  `local.properties`, API tokens, service-account JSON, `.env`.
- `.gitignore` covers these **[verified]**, but `.gitignore` is a *convenience*, not a
  control: it does nothing once a file was committed, and `git add -f` defeats it.
- **Keep signing-material paths out of tracked files too.** A comment naming the keystore's
  absolute path is a pointer telling anyone who can read the repo where to look. This rule
  exists because that exact comment was committed here, then scrubbed **[verified]**.
- Never paste a secret into a commit message, PR description, code comment, test fixture,
  or documentation example. Not even "an old one".

Check the **staged** diff before every commit:

```bash
git diff --cached | grep -nEi "password|secret|token|api[_-]?key|BEGIN .*PRIVATE KEY|ghp_|github_pat_"
```

Legitimate hits will be *identifiers* (`storePassword keystoreProperties["storePassword"]`),
not literals. The decisive question is always: **is there a literal value?**

```bash
# Literal assignment to a password-ish key = stop.
git diff --cached | grep -nEi "(password|secret|token)[[:space:]]*[=:][[:space:]]*[\"'][^\"']{6,}[\"']"
```

### 2.2 Line endings are part of correctness

`.gitattributes` pins `gradlew` to LF and `gradlew.bat` to CRLF **[verified]**. Without it,
line endings depend on each clone's `core.autocrlf`, and one CRLF `gradlew` breaks CI and
every non-Windows clone with `bad interpreter: /usr/bin/env bash^M` while the committer's
own machine keeps working. Verify a fresh checkout when touching wrappers:

```bash
file gradlew gradlew.bat     # gradlew: LF   gradlew.bat: CRLF
```

### 2.3 Binaries stay out

`*.apk`, `*.aab`, `*.jar` (except `gradle-wrapper.jar`), screenshots and profiling traces
are ignored **[verified]**. Artifacts are distributed as GitHub Releases (§7), never as
repo content — a committed binary never shrinks the clone and can never be deleted from
history.

---

## 3. Gate 2 — before pushing

### 3.1 Scan the whole branch, not the tip

The working tree is the *least* contaminated place to look. Scrubbing a file in a later
commit does **not** remove it from earlier commits:

```bash
# Every path that ever appeared in any commit being pushed:
git rev-list --all | while read c; do git ls-tree -r --name-only "$c"; done | sort -u \
  | grep -Ei "keystore|\.jks|\.dpapi|local\.properties|\.idea|\.iml|password"

# Every blob, for a specific known value (e.g. after removing a secret):
git rev-list --all --objects | git cat-file --batch-check='%(objecttype) %(objectname)' \
  | awk '$1=="blob"{print $2}' | while read b; do git cat-file -p "$b" | grep -l "<the-value>" >/dev/null && echo "leak: $b"; done
```

**Before the first push is the only cheap moment to fix history.** After it, the old
commits may remain reachable via direct commit URLs, forks, mirrors and caches.

### 3.2 Authorship must not leak a personal address

Commit metadata is immutable and is scraped from public repos out of `.patch`/`.diff`
endpoints and the API. GitHub's "Keep my email addresses private" setting does **not**
rewrite existing commits.

```bash
git log --format='%an <%ae> | %cn <%ce>' | sort -u
```

Use the account's noreply address (`<digits>+<login>@users.noreply.github.com`, shown at
Settings → Emails). Set it repo-locally so future commits are right **[verified]**.

If it is wrong, rewrite **before** pushing. `git filter-branch --env-filter` pitfalls that
cost real time here:

- Quote the filter with **single** quotes. `"$(cat script.sh)"` lets the outer shell expand
  `$GIT_AUTHOR_EMAIL` to empty first, and git then rewrites **nothing while exiting 0**.
- It also rewrites your **remote-tracking** ref, so a bare `--force-with-lease` then
  compares against a SHA that never existed on the remote — a guard that silently guards
  nothing. Always pass the explicit expected value (§4).
- Afterwards, delete `refs/original/*`, expire the reflog and `git gc --prune=now`, then
  confirm the old objects are gone with `git cat-file -e <old-sha>`.

Prove a rewrite changed only metadata:

```bash
# Capture before and after; the tree hash sequence must match position by position.
git rev-list --reverse HEAD | while read c; do git rev-parse "$c^{tree}"; done | md5sum
```

### 3.3 Build and test green

```bash
./gradlew clean assembleDebug assembleRelease bundleRelease --console=plain
```

**[known environment quirk]** `./gradlew connectedDebugAndroidTest` fails here with
*"Failed to receive the UTP test results"* — AGP 8.1.4 result transport vs an Android 16
device. That is **not** a test failure. Run the suite directly and require `OK (n tests)`:

```bash
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w "com.example.facerecognition.test/androidx.test.runner.AndroidJUnitRunner"
```

The instrumentation APK must match the **signature** of the installed app: installing the
release app then a debug-signed test APK makes instrumentation fail to attach. Uninstall,
install the debug app, then the test APK.

### 3.4 Never push blind

```bash
git status --porcelain            # empty means nothing was silently left out
git log --oneline origin/main..HEAD   # exactly the commits you intend to publish
git diff --stat origin/main..HEAD     # and their scope
```

---

## 4. Gate 3 — push rules

| Rule | Why |
|---|---|
| Fast-forward only | A non-fast-forward push rewrites shared history. |
| Never bare `--force` | It overwrites other people's work without noticing. |
| `--force-with-lease=<branch>:<sha>` with the **observed** remote SHA | The lease needs the real remote value; a rewritten remote-tracking ref makes it a no-op. |

```bash
git ls-remote origin main            # read the ACTUAL remote position first
git push --force-with-lease=main:<that-sha> origin main
```

Pull with rebase or merge, not `pull --rebase` on published commits you don't own. Never
`git push --all --tags` reflexively — push the specific branch, and tags deliberately (§7).

---

## 5. Gate 4 — verify what you are about to ship

**The trap:** when the release key cannot be resolved, this project deliberately falls back
to **debug signing** and prints
`No release signing key resolved: producing a debug-signed release build.`
The APK still builds, installs and looks finished — but users who receive it can
**never** be upgraded to the real key. **[verified]** both branches were exercised, and a
stranger's anonymous clone produced `CN=Android Debug`, i.e. the fallback cannot silently
use your key.

```bash
build-tools/34.0.0/apksigner.bat verify --print-certs app/build/outputs/apk/release/app-release.apk
```

| Certificate DN | Meaning |
|---|---|
| `CN=Face Recognition App, …` | Correct — signed with the release key. |
| `CN=Android Debug` | **Do not ship.** Resolve the key and rebuild. |

Record the expected SHA-256 certificate digest and compare it every release rather than
eyeballing the DN. For an `.aab`, the signature lives in `META-INF/` inside the bundle:

```bash
unzip -l app/build/outputs/bundle/release/app-release.aab | grep META-INF
```

Also confirm the artifact is current — a green `BUILD SUCCESSFUL in 5s` usually means tasks
were **up to date**, so you re-verified a stale file. Force real work with `clean` before
signing checks. **[verified]** — this exact false confirmation happened here once.

Full signing mechanics, DPAPI scope and limits: [RELEASE_SIGNING.md](RELEASE_SIGNING.md).

---

## 6. Gate 5 — version policy

### 6.1 SemVer for `versionName`

`MAJOR.MINOR.PATCH`, bumped **at the commit that makes the change**, never edited afterwards:

- **MAJOR** — incompatible API/behaviour for consumers, a breaking UI/data-format change,
  or dropping a supported Android version.
- **MINOR** — backwards-compatible feature.
- **PATCH** — fix only.

### 6.2 `versionCode` is monotonic and never reused

Android refuses to install a lower `versionCode` over a higher one, and stores require
strictly increasing values. Current baseline: `versionCode 1`, `versionName "1.0"`
**[verified]** — the next ship bumps to `2` / the next SemVer.

Rules:

- Bump **before** building the artifact you intend to ship, in the same commit.
- Never decrement, never reuse, never skip-and-abandon a number you already distributed.
- Keep it decoupled from `versionName`: two patch releases can share a `versionName` fix
  while codes differ.
- `CHANGELOG.md` records every shipped version: added / changed / fixed / removed /
  security, newest first. Not yet present **[policy]**.

### 6.3 Platform SDK policy

`compileSdk`/`targetSdk` 34, `minSdk` 26 **[verified]**.

- Raising `targetSdk` is a **behavioural** change, not a version-bump detail: it re-arms
  permission prompts, foreground-service type requirements, and background restrictions.
  Re-test permissions and every service start path when it moves.
- Stores impose minimum target-API deadlines that move each year. **Check the current
  published policy rather than trusting any number written in this file.**
- Raising `minSdk` strands users on old devices — that is a **MAJOR**, and needs a note.

### 6.4 Supported versions

State it explicitly once you publish anything: latest release gets fixes; the previous
minor gets security fixes; older is unsupported. Without a written policy, "supported"
silently means "whatever I feel like patching".

---

## 7. Gate 6 — cutting a release

No tags exist yet **[verified: 0 tags]**. The first release establishes the convention.

```bash
# 1. Clean tree, green build, certificate verified (§5)
git status --porcelain                      # must be empty

# 2. Version bump committed on its own, so the diff is auditable
#    edit: versionCode, versionName, CHANGELOG.md
git commit -am "Release v1.0.1 (versionCode 2)"

# 3. Annotated tag (not lightweight) — carries tagger, date and message
git tag -a v1.0.1 -m "v1.0.1 — <what changed>"

# 4. Push the commit, then the tag deliberately
git push origin main
git push origin v1.0.1
```

Then build the release artifacts from the **tagged commit** and publish them as a GitHub
Release with release notes, attaching the `.aab` (store) and `.apk` (direct install):

```bash
./gradlew clean assembleRelease bundleRelease
sha256sum app/build/outputs/apk/release/app-release.apk \
          app/build/outputs/bundle/release/app-release.aab | tee SHA256SUMS.txt
```

Publish `SHA256SUMS.txt` beside the artifacts and verify after download:
`sha256sum -c SHA256SUMS.txt`. A checksum posted in the same channel as the file proves
integrity, not authenticity; for authenticity, sign the release.

Never attach artifacts to a commit that is not the tagged one, and never edit a tag that
has been published — tag forward instead.

---

## 8. Repository-side controls

| Control | Where | Status |
|---|---|---|
| 2FA on the account | Settings → Password and authentication | **[verified by owner, not measurable here]** — the API was rate-limited; a passkey/security key beats SMS. |
| Secret scanning + **push protection** | Settings → Code security and analysis | Owner-reported as enabled; **confirm in the Security tab**, availability differs public/private. |
| Dependabot alerts + security updates | same page | Same — verify, don't assume. |
| Account recovery codes, backup email | Settings | **[policy]** |
| **Passphrase on the SSH key** | `ssh-keygen -p -f ~/.ssh/github_ed25519 -Z aes256-ctr` | **[policy — outstanding gap]** A passphrase-less key means any process running as you can push attacker-chosen code to a repo others clone **and build**. The key stays valid on GitHub; nothing needs re-adding. |
| Branch protection on `main` (require PR, require checks) | Settings → Branches | **[policy]** Free for public repos. Direct pushes to `main` are currently unguarded. |

**[known environment quirk]** `api.github.com` repeatedly returned `HTTP 403` here —
unauthenticated rate limiting on the local IP, not a permission problem. Anything the API
would confirm must be checked in the web UI.

---

## 9. Incident response — act fast, assume permanence

### 9.1 A secret was pushed

1. **Revoke/rotate the secret first.** Removing it from history is cleanup; the credential
   is already burned.
2. Purge history, then force-push with an explicit lease (§3.2, §4).
3. Delete `refs/original/*`, expire the reflog, `git gc --prune=now`.
4. **Contact GitHub Support** — deleted commits and blobs can persist via cached commit
   URLs, forks and mirrors. Do not assume a rewrite is sufficient.
5. Check the Exposure notifications in the Security tab.

### 9.2 The signing key or its password is lost

Everything already installed **cannot be updated** — only republished under a new package
name, asking every user to reinstall. Keep an offline, encrypted backup of both the keystore
and the password, and verify the copy's checksum after making it:

```bash
sha256sum backup.jks path/to/live.jks      # must be identical
```

DPAPI blobs are bound to one Windows user on one machine: copying the `.dpapi` elsewhere
yields garbage, not the password. Migrate by decrypting and transferring the secret through
a password manager.

### 9.3 The repo was made public with something in it

Rotate credentials first (§9.1), then evaluate whether the history is salvageable or the
repository should be deleted and re-created — a re-created repo loses stars, forks and
issue history, which is why §3.1 comes before §4.

---

## 10. Hard "never" list

- **Never** `git add -f` a gitignored file without re-reading §2.1.
- **Never** commit keystores, passwords, DPAPI blobs, or their absolute paths.
- **Never** bare `--force`, and never `--force-with-lease` against a ref you know was
  rewritten locally.
- **Never** ship an artifact whose certificate you did not check (§5).
- **Never** commit build outputs, emulator screenshots, profiling traces, or `local.properties`.
- **Never** pipe a remote script into a shell (`curl … | bash`). Download, read, verify a
  published checksum, then run.
- **Never** recursively delete a Windows junction. `Remove-Item -Recurse`, `rm -rf` and
  `rmdir /s` can follow the link into its target. This machine has `C:\Users\<you>`
  junctions pointing at `D:` directories; if one must go, use bare `rmdir` or
  `[IO.Directory]::Delete($path)`, which unlink without touching the target.
- **Never** delete anything under the SDK, Gradle user home or `.android` to "fix" a build —
  diagnose instead; those caches are the expensive part.
- **Never** trust an exit code as proof. Verify the observable: `git ls-remote`, `apksigner
  verify`, tree hashes, `file`, byte counts. Several checks in this project produced
  *reassuring-looking wrong answers* — an HTML page scored as a "clean patch", a
  `--force-with-lease` aimed at a nonexistent SHA, and a filter that rewrote nothing while
  returning 0.

---

## 11. Pre-push / pre-ship checklist

```
PRE-COMMIT
[ ] staged diff scanned; no literal secret or signing-material path
[ ] .gitignore still covers artifacts/secrets; nothing force-added
[ ] gradlew LF / gradlew.bat CRLF unchanged

PRE-PUSH
[ ] history scanned for sensitive paths AND for the specific value, across all commits
[ ] author + committer email = noreply address
[ ] ./gradlew clean assembleDebug assembleRelease bundleRelease -> BUILD SUCCESSFUL
[ ] instrumentation tests report OK (n tests)
[ ] git log origin/main..HEAD contains exactly the intended commits
[ ] tree clean: git status --porcelain is empty

SHIP
[ ] versionCode bumped in the same commit as the build
[ ] versionName + CHANGELOG.md updated
[ ] clean build (not up-to-date) immediately before signing checks
[ ] apksigner DN/digest == release key, NOT CN=Android Debug
[ ] annotated tag pushed separately
[ ] artifacts + SHA256SUMS.txt published as a GitHub Release
[ ] checksum verified after download
```

---

## 12. What is *not* yet verified in this project

Stated so nobody mistakes a written rule for a tested one.

- **No release has ever been cut** — 0 tags, no CHANGELOG, no GitHub Release. §7 is
  convention awaiting its first application.
- **Overlay box alignment and real match/miss behaviour are unverified.** The AVD's
  synthetic camera never contains a face, so detection accuracy, registration through the
  UI, and the `FILL_CENTER` overlay transform were reasoned about but never observed. A
  physical camera device is required before any of this ships.
- **GitHub-side controls could not be confirmed programmatically** (API rate-limited); they
  rest on the owner's word plus the Security tab.
- **Play Store distribution has never happened**, so upload-key vs app-signing-key handling
  is documented intent, not practice. If this becomes a published app, generate a fresh
  upload key and use Play's app signing — do not publish with a key that also lives only on
  one workstation.
- Branch protection, CI, and Dependabot-driven updates are unconfigured, so "a green build
  is required before merge" is currently a human habit, not an enforced gate.
