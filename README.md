# Kiwi PDF Reader
A simple, clean, ad-free PDF reader for Android. Built for the people in your life who somehow end up with five PDF apps installed. Install this for them and tell them "Just use the green one!"

## Why?
There are already thousands of PDF reader apps.

They all open PDFs. Most work fine. They're all red.

This one is green.

Oh, and no ads. No subscriptions. No Paid Features. Just a clean, simple PDF reader that happens to be green.
Install it for someone you love and tell them: "Just use the green one."
They will.

## Play Store Listing Information:
Images

"Yes, another PDF reader."
Screenshot of a clean PDF open in the app — [Suspiciously simple. Suspiciously green.]
"No ads. No subscriptions. No red."
Screenshot of the app UI — [Just a PDF. Just green. Just works.]
"Just use the green one."
Screenshot of a happy parent successfully opening a PDF — [Finally.]

## Releasing

Releases to Google Play are published by a GitHub Action ([`.github/workflows/release.yml`](.github/workflows/release.yml)).

1. Increase `versionCode` in [`version.properties`](version.properties) by one, and update `versionName` (the version users see, e.g. `1.1`).
2. Commit it, e.g. `🔖 release version 1.1`, and push to `main`.
3. The action builds a signed bundle, uploads it to the **internal testing** track as a draft and tags the commit as `v<versionCode>`.
4. In the Play Console, review the draft and roll it out, or promote it to closed testing or production.

The action only runs when `version.properties` changes on `main`, and skips a `versionCode` that already has a tag, so every version is released once. Play rejects a `versionCode` that isn't higher than the last upload; the action then fails without publishing anything.

It needs these repository secrets:

| Secret | Content |
| --- | --- |
| `UPLOAD_KEYSTORE_BASE64` | The upload keystore, base64-encoded (`base64 -w0 upload.jks`) |
| `UPLOAD_KEYSTORE_PASSWORD` | The keystore password |
| `UPLOAD_KEY_ALIAS` | The key alias, e.g. `upload` |
| `UPLOAD_KEY_PASSWORD` | The key password (the same as the keystore password for PKCS12 keystores) |
| `PLAY_SERVICE_ACCOUNT_JSON` | The JSON key of a Google service account with permission to release Kiwi PDF Reader to testing tracks |

To build a release locally, create `keystore.properties` in the project root (git ignores it) and run `./gradlew :app:bundleRelease`:

```
storeFile=/path/to/upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```
