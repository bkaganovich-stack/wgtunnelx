# WG Tunnel X releases: setup and maintenance

[Русский](RELEASING.md) · **English**

## One-time setup (≈15 minutes)

### 1. Repository

Create a **regular** public repository named `wgtunnelx` (the *New repository* button, no README or license). Don't use the *Fork* button: scheduled GitHub Actions workflows are disabled by default in forks of public repositories ([docs](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows)), and all of the automation runs on a schedule.

```sh
git remote add origin https://github.com/<owner>/wgtunnelx.git
git push -u origin main
```

If the owner isn't `bkaganovich-stack`, change `updateRepository` in `wgtunnelx.properties`: that's where the app looks for updates.

### 2. Actions permissions

*Settings → Actions → General → Workflow permissions* → **Read and write permissions**. Without this, the workflows can't push merges and tags or create releases.

### 3. Signing key

Every release has to be signed with the same key, otherwise Android won't install an update over the existing app. Keep a copy of the key and its passwords outside GitHub.

```sh
keytool -genkeypair -v -keystore wgtunnelx.jks -alias wgtunnelx \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 wgtunnelx.jks > wgtunnelx.jks.b64     # macOS: base64 -i wgtunnelx.jks
```

*Settings → Secrets and variables → Actions → New repository secret*:

| Secret | Value |
| --- | --- |
| `KEYSTORE` | contents of `wgtunnelx.jks.b64` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | `wgtunnelx` |
| `SIGNING_KEY_PASSWORD` | key password |

By default keytool creates a PKCS12 keystore, where the key password is the same as the keystore password, even if you typed a different one when creating it. The build checks the secrets before compiling (`scripts/verify-keystore.sh`) and tells you which one is wrong.

### 4. `SYNC_TOKEN` for merges (recommended)

**Why.** The `x-upstream-release` workflow pushes upstream commits to `main`. If even one of them touches files in `.github/workflows` (upstream edits its workflows regularly), GitHub rejects the push made with the built-in `GITHUB_TOKEN`: such commits need the `workflows` permission, and `GITHUB_TOKEN` can't be given it. The release then fails at the *Push main* step and the workflow opens an issue. `SYNC_TOKEN` covers this case. Until it's set, the workflow uses `GITHUB_TOKEN`, and everything works up to the first upstream release of that kind.

**Creating the token.**

1. Open <https://github.com/settings/personal-access-tokens/new> (*avatar → Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token*).
2. **Token name:** `wgtunnelx-sync`. **Description:** for example, "WG Tunnel upstream merges".
3. **Resource owner:** your account (`bkaganovich-stack`).
4. **Expiration:** the longest period available. Put the expiry date in your calendar: after it, merges go back to depending on `GITHUB_TOKEN` (see "Renewal" below).
5. **Repository access:** *Only select repositories* → `wgtunnelx`. The token doesn't need access to any other repository.
6. **Permissions → Repository permissions:**
   - **Contents:** *Read and write*;
   - **Workflows:** *Read and write*;
   - **Metadata:** *Read-only* (GitHub turns it on by itself).

   Leave everything else at *No access*.
7. Click **Generate token** and copy the value (`github_pat_…`) right away: GitHub shows it only once.

**The secret.** In the `wgtunnelx` repository: *Settings → Secrets and variables → Actions → Repository secrets → New repository secret*. **Name:** `SYNC_TOKEN`, **Secret:** the token you copied. If the secret already exists, click the pencil next to it and paste the new value.

**How to check it.** There's no separate check: the token is only used when merging upstream. The sign that it works is that the *Push main* step in `x-upstream-release` passes even for releases where upstream changed its workflows.

**Renewal.** GitHub emails you when the token is about to expire. Open it under *Fine-grained tokens* → **Regenerate token**, then update the `SYNC_TOKEN` secret with the new value. If the token has already expired, `x-upstream-release` fails at its very first step (*checkout*) once a new upstream release comes out, and GitHub emails you about the failed run. No issue is opened in that case, because the workflow never gets as far as the merge. After updating the secret, run `x-upstream-release` by hand (*Actions → x-upstream-release → Run workflow*).

**Security.** The token works only on `wgtunnelx` and has only two permissions. If it leaks, revoke it (*Fine-grained tokens → the token → Delete*) and create a new one.

### 5. First release

*Actions → x-release → Run workflow*, with the *bump* box unchecked: you get `5.8.2-x.1`. From then on everything runs by itself.

## How the automation works

1. Every 15 minutes `x-upstream-release` calls `GET /repos/wgtunnel/android/releases/latest`. This endpoint doesn't return drafts or pre-releases; on top of that, the workflow checks that the tag looks like `X.Y.Z`.
2. If there's no fork release for that version yet, the tag is merged into `main` (`scripts/upstream-sync.sh`):
   - upstream workflows deleted in the fork stay deleted, new ones are removed;
   - in files that carry fork hooks, the upstream version is taken and the hooks are applied again (`scripts/apply-hooks.py`);
   - `xRevision` is reset to 1.
3. Tests → validation of the generated configs against a real Xray-core with the runetfreedom databases → signed `standaloneRelease` build.
4. Only after a successful build: push to `main`, tag `X.Y.Z-x.1`, and a release with the APKs (`arm64`, `armv7`, universal), `SHA256SUMS`, the certificate fingerprint and the upstream release notes. The release is marked *latest*, and installed copies offer to update.

The delay after an upstream release is usually 15–30 minutes: GitHub may push scheduled runs back when it's under load.

## When something goes wrong

The workflow opens an issue, and the repository owner sees it (with an email notification).

**"Upstream X.Y.Z needs a manual merge".** Upstream changed code that overlaps with the fork outside the known hooks.

```sh
git fetch https://github.com/wgtunnel/android.git tag X.Y.Z
git merge X.Y.Z                 # resolve the conflicts
python3 scripts/apply-hooks.py  # are the hooks in place?
./gradlew :xray:testDebugUnitTest :app:assembleStandaloneDebug
git push
```

Then *Actions → x-upstream-release → Run workflow* (the tag field can stay empty).

**"WG Tunnel X build for upstream X.Y.Z failed".** The merge went through, but the build or the tests didn't. Most often upstream changed an API that `RoutingTunnelProvider` relies on. Fix the code in `main` and run the workflow by hand. If the Xray config validation failed, look at the log of the *Validate generated configs* step.

**The schedule stopped running.** *Actions → x-upstream-release → Enable workflow*. It rarely comes to that: the workflow keeps itself alive once a day.

## Updating Xray-core

Once a week `x-update-xray` checks for [2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) releases. A new version is committed to `main` (`xray/libxray.version` and `xray/libxray.sha256`) only if the tests and `xray run -test` pass, and it ships with the next release. To release it right away, run `x-release`.

## Releasing fork changes

*Actions → x-release* with the *bump* box checked: `5.8.3-x.1` → `5.8.3-x.2`. The version code grows as `upstreamVersionCode × 100 + xRevision`, so any fork release updates cleanly over any other.
