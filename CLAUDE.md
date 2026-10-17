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

- Use emulators available to you.
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
- Files opened from other apps lose their permission once closed, so the
  app keeps private copies for the recent files list. The limits (30 days
  since last opened, 250 MB in total, 20 entries) are deliberate and not
  configurable.
- "Remember password" is ticked by default. Protected documents get no
  thumbnail unless their password is remembered (privacy).

# TODO

## Known issues (not fixed on purpose, for now)
- The same PDF can appear twice in the recent files list: once picked with
  the file picker (deduplicated by URI) and once opened from another app
  (a copy, deduplicated by SHA-256). Possible fix: hash picked files too.

## Open
- [ ] App icon: the user will provide artwork; keep the template icon
      until then
- [ ] More UI adjustments (the user will specify)

## Milestone 4: release (optional)
- [ ] Release signing, check the R8 release build on a device
- [ ] Play Store screenshots and listing text from the README
