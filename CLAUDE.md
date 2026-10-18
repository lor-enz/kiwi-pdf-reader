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
- Try to update claude.md and the readme with the relevant commit. E.g. when updating the milestones instead of a separate commit just for the claude.md/readme changes.
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
- Print passes the file through to the print system unchanged, except for
  password-protected documents: the print system can't open those, so
  their pages are rendered as images (200 dpi) from the open document.
- Share copies the file to `cache/shared/` under its real name and hands
  it out via `FileProvider`. Copies are deleted after a day (checked at
  startup and on each share), not right away, because recipients (mail
  drafts) may read them later.
- "Remember password" is ticked by default. Protected documents get no
  thumbnail unless their password is remembered (privacy).
- Phones without SDK extension 13 stay supported (minSdk 31). Raising
  minSdk to 35 would be the only way to require it and would exclude most
  Android 12–14 phones, which mostly have the extension anyway.
- Form edits are autosaved about 1 s after the last edit and when the app
  goes to the background, as a full filled-in PDF in private storage,
  keyed by the SHA-256 of the original. They survive closing the document
  and process death, whether the file is picked or opened from another
  app. Reopening the original opens the filled-in version without asking;
  "Clear filled-in fields" in the tools sheet starts over. Share, Print
  and Save a copy use the filled-in version, and the sheet says so next to
  them. Filled-in versions count toward the copies' limits (30 days,
  250 MB). Where `PdfFeature.FORM_FILLING` is missing, fields stay
  read-only.
- Only documents opened from other apps get their own task (card in the
  app switcher); the app's own list and picker open in the main task.
- `androidx.pdf` can't read a PDF's outline (table of contents), so a
  tablet side panel could only show page thumbnails.
- No OCR for scanned PDFs: the usual engine (ML Kit) needs Google Play
  Services, and Tesseract is too large.
- Web, mail and phone links go through `onUrlLinkClicked`: only http(s),
  mailto and tel are opened (the library's default would open any scheme),
  and a snackbar says so when no app can open one. In-document links are
  handled by `PdfView` itself: it centers the target in the viewport (so it
  never hides under the top bar), and links with a bare `/Dest` array do
  nothing; only `/GoTo` actions work, including named destinations (LaTeX,
  Word). Nothing to hook into for either, as of beta01.
- Failures to open are diagnosed by reading the file's first bytes: no PDF
  header means not a PDF, a header (or an empty file) means damaged.
  Deleting a document revokes its URI grants, so a `SecurityException`
  can't tell "deleted" from "permission revoked": for files that opened
  before it counts as moved or deleted, otherwise as not given access.

# Planned changes

## Known issues
- The same PDF can appear twice in the recent files list: once picked with
  the file picker (deduplicated by URI) and once opened from another app
  (a copy, deduplicated by SHA-256). Possible fix: hash picked files too.

## Upcoming features

### Milestone 5: robustness
- [x] Links: test web, mailto, tel and in-document links (with the top
  bar overlaid); don't crash when no app handles a link
- [x] Broken files: tell apart damaged, not-a-PDF and lost permission,
  with advice (download it again, ask the sender for a new copy)
- [ ] Search in a document without text (scanned): say so instead of
  "No matches"

### Milestone 6: recent files sheet
- [ ] Long-press an entry: sheet with its file name at the top
- [ ] Remove from list, with an Undo snackbar (delete files only once the
  snackbar is gone)
- [ ] Share without opening the document
- [ ] Forget password (only when one is remembered), also deletes the
  thumbnail
- [ ] Hash picked files too, fixing the duplicate entries (known issue)

### Milestone 7: save and forms
- [ ] Save a copy ("Save to…" via the system file picker)
- [ ] Form filling with autosave and restore (see Project notes)
- [ ] Check whether the filled-in file of a password-protected PDF stays
  encrypted; if not, ask the user before storing it
- [ ] Update the privacy policy (filled-in forms are stored locally)

### Milestone 8: documents from other apps in their own task
- [ ] Split home and viewer into separate activities; reopening the same
  document returns to its card; back and process death per task

### Milestone 9: presentation mode
- [ ] Fullscreen (hide system bars) first
- [ ] One page at a time, swipe left/right, pinch-zoom (own pager view)

### Later
- [ ] Tablets: two pages side by side (`pagesPerRow`), or a thumbnail
  side panel
