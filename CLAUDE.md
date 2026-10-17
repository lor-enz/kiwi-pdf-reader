# How to work here

- Commit atomically — one logical change per commit.
- Use gitmoji for commit messages, following the conventions in [`gitmoji.md`](gitmoji.md).
- Commit only when the user asks. Propose the split first; every commit
  must build and pass the tests on its own.
- Work in milestones. After each one: build, run the unit tests, test on
  the emulator, then report to the user, who tests on a real phone.
- If the emulator or a device misbehaves during testing (hanging installs,
  very slow responses, unresponsive adb), stop and tell the user instead of
  waiting or working around it. It usually needs a manual restart.
- Only test on the emulator unless the user says otherwise; their own
  phone may be connected via adb too (target with `adb -s <serial>`).
- Record decisions and findings in this file as they come up.

# Testing

- Use only already running emulators. If none are running or they are misbehaving, tell the user.
- "Open from another app": push the PDF to `/sdcard/Download`, run the
  media scanner, look up its id in `content://media/external/file`, then
  `am start -a android.intent.action.VIEW -t application/pdf -d
  content://media/external/file/<id> --grant-read-uri-permission`.
- `am start` calls stack activity instances; force-stop between scenarios.
- Process death: `am kill` doesn't work. Press home, then
  `run-as kiwi.lorenz.pdfreader kill -9 <pid>`.
- Fast swipes fling to the end of the document; use slow ones.

# Product philosophy

- Simple means low friction, not few features. A feature belongs in the
  app if it adds value without adding anything the user has to notice,
  learn or decide. Example: thumbnails in the recent files list help a
  lot and cost the user nothing.
- Prefer sensible defaults over settings. Add a setting or customization
  only when no single default can work for most users.
- Before adding a feature, ask: does the user have to do or understand
  anything new? If yes, look for a way that works without it.

# Project notes

- Keep the app free of Google Play Services (open source, F-Droid).
- `androidx.pdf` is beta, and the Compose `PdfViewer` is still
  `@ExperimentalPdfApi`. Fallback if it misbehaves: host
  `PdfViewerFragment` via `fragment-compose`.
- Devices without SDK extension 13 get a compat renderer: viewing works,
  but search, selection and encrypted PDFs don't (not even with the right
  password). Check `PdfFeature` support instead of blocking the app.
- `PdfViewer` applies `contentPadding` only when its view is created,
  which is why the top bar is laid over the document.
- `PdfView` keeps the fast scroller always visible whenever any
  accessibility service runs (password managers etc.), not only screen
  readers. `FastScrollAutoHide` switches it back to fading out (library
  default: 1.3 s + 0.2 s fade) unless touch exploration is on, via the
  restricted `fastScrollVisibility`. Drop it once `androidx.pdf` fixes this.
  To reproduce on the emulator, install a dummy accessibility service.
- Files opened from other apps lose their permission once closed, so the
  app keeps private copies for the recent files list. The limits (30 days
  since last opened, 250 MB in total, 20 entries) are deliberate and not
  configurable.
- Don't set `optimization.packageScope` in the release build. The template's
  `androidx/kotlin/kotlinx` scope made R8 produce a class extending a
  package-private class in `kotlin.collections` (`IllegalAccessError` at
  startup). R8 now optimizes everything (the default, `**`).
- Releases: raising `versionCode` in `version.properties` on `main` makes
  the GitHub Action upload a signed bundle to Play's internal track as a
  draft (see "Releasing" in the README). Bump it only in its own `🔖`
  commit, and only when the user asks for a release.
- Release signing reads the gitignored `keystore.properties` in the project
  root (same setup as the user's other apps). The upload key lives outside
  the repo. Without the file, release builds are unsigned (F-Droid signs
  them itself).
- The privacy policy for Google Play is `docs/privacy-policy.md`, served by
  GitHub Pages from `/docs`. Update it whenever the app starts storing,
  sending or requesting anything new (permissions, network, backups).
- The viewer's tools live in a bottom sheet behind the ⋮ button, so the
  top bar stays calm as tools are added. Presentation mode (one page at a
  time, swipe left/right) is postponed: `PdfViewer` only scrolls
  continuously, so it needs its own pager-based view.
- "Remember password" is ticked by default. Protected documents get no
  thumbnail unless their password is remembered (privacy).

# TODO

## Known issues (not fixed on purpose, for now)
- The same PDF can appear twice in the recent files list: once picked with
  the file picker (deduplicated by URI) and once opened from another app
  (a copy, deduplicated by SHA-256). Possible fix: hash picked files too.

## Open
- [ ] More UI adjustments (the user will specify)

## Milestone 4: release (optional)
- [x] Release signing, check the R8 release build on a device
- [x] Publish to Google Play from GitHub Actions (see "Releasing" in the
  README); versionCode 1 was uploaded by hand
- [ ] Play Store screenshots and listing text from the README
