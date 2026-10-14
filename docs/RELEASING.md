# Morlune release process (GitHub + F-Droid)

This workflow is for **signed GitHub releases**. F-Droid builds from source and applies its own signing. The GitHub-signed and F-Droid-signed APKs will normally **not update each other** without uninstalling the app first. They share the same Android package ID.

## 1. Create and protect a signing key (on CachyOS)

```bash
mkdir -p ~/.local/share/morlune
chmod 700 ~/.local/share/morlune
keytool -genkeypair -v -keystore ~/.local/share/morlune/release.jks \
  -alias morlune -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
chmod 600 ~/.local/share/morlune/release.jks
```

Enter strong store and key passwords. Keep an encrypted offline backup of the keystore and passwords. Do **not** commit the keystore or the passwords. If you lose this key, users of your GitHub-signed APKs cannot receive a normal signed update from a replacement key.

## 2. Add GitHub Actions secrets

In GitHub: **Settings > Secrets and variables > Actions > New repository secret**.

Set four secrets:

- `MORLUNE_KEYSTORE_BASE64`: the one-line base64 encoding of `release.jks`
- `MORLUNE_KEYSTORE_PASSWORD`: keystore password
- `MORLUNE_KEY_ALIAS`: `morlune`
- `MORLUNE_KEY_PASSWORD`: key password

Encode locally (do not paste the result in a chat):

```bash
base64 -w 0 ~/.local/share/morlune/release.jks | xclip -selection clipboard
```

If `xclip` is unavailable, redirect to a temporary private file instead and delete it securely after copying into GitHub. Don't print signing secrets into CI logs.

## 3. Install the release workflow

Apply the accompanying Git patch, commit and push to `main`. Before release, inspect the installed `app/build.gradle.kts` and `.github/workflows/release.yml`.

## 4. Confirm the final version

Update `versionName` and **increment `versionCode`** in `app/build.gradle.kts` for each subsequent release. The workflow enforces that `versionName` equals the pushed `vX.Y.Z` tag.

Run tests/build locally if the Android SDK is installed:

```bash
./gradlew :engine:runTests :app:assembleDebug
```

## 5. Create the release

After all feature branches are merged and GitHub Actions is green:

```bash
git switch main
git pull --ff-only origin main
git tag -a v0.1.0 -m 'Morlune 0.1.0'
git push origin v0.1.0
```

Only create the tag once you confirm that `v0.1.0` does not already exist (`git tag --list v0.1.0`). If it already exists, do not move it; use a new version and increment `versionCode`.

GitHub Actions will test, build, sign, verify and attach `morlune.apk` plus `SHA256SUMS.txt` to the release. A failed build or missing secrets prevents publication.

## 6. F-Droid

Submit the F-Droid recipe separately, pointing at the same immutable tag. The F-Droid build must be able to run *without* the GitHub signing secrets. Validate the recipe against the exact tag; no automatic acceptance is guaranteed. If you plan to use only F-Droid distribution, you may skip GitHub APK signing entirely and publish the tag/source release instead.
