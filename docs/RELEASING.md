# Releasing

Releases are built by GitHub Actions (`.github/workflows/release.yml`) when a
version tag is pushed. The workflow signs the APK with the project's release key
(kept only in the encrypted secrets of the repository's `release` environment) and
attaches these files to a new GitHub Release:

- `AzaynRecorder-vX.Y.Z.apk`, the app
- `callrec.dex`, the prebuilt recorder daemon
- `callrec.sh`, which installs the app and starts the daemon from those files
- `SHA256SUMS`

## Publishing a release

```bash
git checkout main && git pull
git tag v1.1.0            # semantic version; a suffix like v1.1.0-beta.1 makes a pre-release
git push origin v1.1.0
```

The version name comes from the tag; the version code is derived from it
(`1.2.3` → `10203`), so every newer tag installs as an update.
Release notes start with install steps and the legal reminder, followed by notes
generated from the commits since the previous tag; edit them on GitHub afterwards if needed.

## The signing key (one-time setup, maintainers only)

Android only installs an update if it's signed with the **same key** as the
installed app. Losing the key means users must uninstall to get new versions;
leaking it lets others publish "updates" as Azayn Recorder. Keep it out of the repo
and backed up somewhere safe (e.g. a password manager).

1. Create the key (keep it outside the repository):

   ```bash
   keytool -genkeypair -v -keystore ~/azayn-recorder-release.jks -alias recorder \
     -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Azayn Recorder"
   ```

2. Add these secrets to the **`release` environment** (GitHub → Settings →
   Environments → release → Environment secrets), or with the GitHub CLI:

   ```bash
   gh secret set RECORDER_KEYSTORE_BASE64 --env release < <(base64 -w0 ~/azayn-recorder-release.jks)
   gh secret set RECORDER_KEYSTORE_PASSWORD --env release
   gh secret set RECORDER_KEY_PASSWORD --env release
   ```

   The key alias (`recorder`) is set in the workflow itself; it isn't secret.

   The environment also restricts which tags may deploy (Settings →
   Environments → release → Deployment branches and tags → `v*`).

3. Optional, to build signed releases locally: create `android/keystore.properties`
   (git-ignored):

   ```properties
   storeFile=/home/you/azayn-recorder-release.jks
   storePassword=…
   keyAlias=recorder
   keyPassword=…
   ```

   Without it, local release builds are signed with the debug key.

## Forks

Forks build fine without any secrets (CI uses the debug build). To publish your
own releases, create your own key and secrets as above; your APKs won't install
over the official ones, since they're signed differently.
