# PDF Penguin
A simple, clean, ad-free PDF reader for Android. Built for the people in your life who somehow end up with five PDF apps installed. Install this for them and tell them "Just use the blue one!"

## Why?
There are already thousands of PDF reader apps.

They all open PDFs. Most work fine. They're all red.

This one is blue. It has a penguin.

Oh, and no ads. No subscriptions. No Paid Features. Just a clean, simple PDF reader that happens to be blue.

## Design philosophy
- Simple means nothing new to learn, not few features.
- Good defaults beat settings.
- Nothing jumps, pops in or flickers.
- Every slip can be undone.
- The app tells the truth, even when it's "this won't work here".
- The same thing works the same way everywhere.
- It speaks your language, not the computer's.
- Feedback appears where you're looking.
- It does Android properly, down to the details most apps skip.
- Your PDFs stay on your device: the app doesn't even have internet access.

## Requirements
Android 14 or later. On Android 14 the app needs a recent Google Play system update (SDK extension 13): without it, PDFs still open, but links, search, forms and password-protected files don't work, and the app says so.

## Development

### Checks

Every push to `main` and every pull request runs the unit tests and lint and builds the release version ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)). Dependabot opens a pull request each month that updates the dependencies ([`.github/dependabot.yml`](.github/dependabot.yml)). Run the same locally with `./gradlew testDebugUnitTest lintDebug assembleRelease`. When a run fails, its test and lint reports are attached to it.

### Releasing

Releases to Google Play are published by a GitHub Action ([`.github/workflows/release.yml`](.github/workflows/release.yml)).

1. Increase `versionCode` in [`version.properties`](version.properties) by one, and update `versionName` (the version users see, e.g. `1.1`).
2. Commit it, e.g. `🔖 release version 1.1`, and push to `main`.
3. The action runs the CI checks (unit tests, lint, release build); only if they pass does it build a signed bundle, upload it to the **internal testing** track as a draft and tag the commit as `v<versionCode>`.
4. In the Play Console, review the draft and roll it out, or promote it to closed testing or production.

The action only runs when `version.properties` changes on `main`, and skips a `versionCode` that already has a tag, so every version is released once. Releases run one at a time; a second push waits for the first. Play rejects a `versionCode` that isn't higher than the last upload; the action then fails without publishing anything.

To build a release locally, create `keystore.properties` in the project root (git ignores it) and run `./gradlew :app:bundleRelease`:

```
storeFile=/path/to/upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```
