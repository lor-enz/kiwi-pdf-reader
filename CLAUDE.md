# How to work here

- Commit atomically — one logical change per commit.
- Use gitmoji for commit messages, following the conventions in [`gitmoji.md`](gitmoji.md).
- Commit only when the user asks. Propose the split first; every commit
  must build and pass the tests on its own.
- Work in milestones. After each one: build, run the unit tests, test on
  the emulator, then report to the user, who tests on a real phone.
- Test on an available emulator yourself, except when the user says they'll
  test (usually a small change): then stop after the build and unit tests.
- If the emulator or a device misbehaves during testing (hanging installs,
  very slow responses, unresponsive adb), stop and tell the user instead of
  waiting or working around it. It usually needs a manual restart.
- Only test on the emulator unless the user says otherwise; their own
  phone may be connected via adb too (target with `adb -s <serial>`).
- Give new UI elements a test tag (`ui/TestTags.kt`) in the same change
  that adds them, so they can be driven by adb (see Testing).
- Record decisions and findings in this file as they come up, and update it
  and the readme in the commit they belong to (e.g. the milestones), not in a
  separate one.

# Testing

- Unit tests run on the JVM. `RecentFilesStoreTest` uses Robolectric (simulating
  Android 15, see `src/test/resources/robolectric.properties`) and a fake
  documents provider (`TestFilesProvider`), so store logic is tested without a
  device; add a test there when changing the store.
- Use only already running emulators. If none are running or they are misbehaving, tell the user.
- Helpers in `scripts/test/` use the running emulator, never a connected
  phone unless `ANDROID_SERIAL` names it. Install with `install.sh`, not
  `./gradlew installDebug`, which installs on every connected device.
  - `make-test-pdfs.py` writes test files to `build/test-pdfs/` (the
    protected ones use password `kiwi`).
  - `make-metadata-fixtures.py` writes the committed PDFs for
    `PdfMetadataReaderTest` (`src/test/resources/metadata/`), one per way a
    PDF can store its metadata. Run it again after changing it.
  - `prepare-device.sh` turns animations off, which keeps screenshots
    stable. Run it before testing; an emulator booted from an older
    snapshot has them on again.
  - `open-pdf.sh [--fresh] [--no-grant] <file>` opens a file the way
    another app would (push to Download, media store URI, VIEW intent),
    in a card of its own. `--fresh` stops the app and clears its data: a
    file opened before otherwise reopens at its last page, and one that's
    still open switches to its card.
  - `ui.sh list|tap|hold|text|type|wait|back|home|shot` reads and drives
    the screen through `uiautomator`, finding elements by test tag, text
    or description. `home` opens the app like its launcher icon; a plain
    `am start -n` would stack a second home screen.
  - `app.sh cards|focus|top|kill` lists the cards with the document each
    shows, switches between them, and simulates process death (`am kill`
    doesn't work, and force-stop removes the document cards).
  - `store.sh show|age` lists the recent files list and makes an entry
    older, e.g. to test cleanup. Cleanup runs when the home screen is
    created, after a document opens and when a viewer closes.
- Test tags (`ui/TestTags.kt`) show up as resource ids because the root
  sets `testTagsAsResourceId` (`setAppContent`). Popups, sheets and dialogs
  are windows of their own and need `Modifier.exposeTestTags()` again.
- Page content is a single accessibility node, so links in a document are
  tapped by coordinates.
  - `form-values.sh [--password <pw>] <entry name | /device/path>` prints
    the field values of an entry's stored filled-in form, or of a PDF on
    the device, and whether it needs a password.
- With a text field focused, the first back only closes the keyboard.
- Form fields are tapped by coordinates. A document that reopens at its
  last position may sit lower on screen, so take a screenshot first. A tap
  only counts after about 300 ms (the view waits for a double tap), so
  leave the app no sooner than that after one.
- Fast swipes fling to the end of the document; use slow ones.

# Product philosophy

- Simple means low friction, not few features. A feature belongs in the
  app if it adds value without adding anything the user has to notice,
  learn or decide. Example: thumbnails in the recent files list help a
  lot and cost the user nothing.
- Short feedback goes in a snackbar, never a toast: a copy was saved, a
  file couldn't be shared, no app opens a link, Undo after removing.
- Prefer sensible defaults over settings. Add a setting or customization
  only when no single default can work for most users.
- Before adding a feature, ask: does the user have to do or understand
  anything new? If yes, look for a way that works without it.
- Use standard Android and Compose behavior (e.g. `animateItem()` for list
  changes) instead of custom implementations, so the app feels native.

# Project notes

- Keep the app free of Google Play Services (open source, F-Droid).
- `androidx.pdf` is beta, and the Compose `PdfViewer` is still
  `@ExperimentalPdfApi`. Fallback if it misbehaves: host
  `PdfViewerFragment` via `fragment-compose`.
- minSdk is 35 (Android 15), so every feature is always available: don't
  add feature checks. A reduced mode for older phones would need a second
  code path for every feature.
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
- The workflows use `gradle/actions/setup-gradle@v6`, whose caching is a
  proprietary component under Gradle's Terms of Use. Accepted on purpose:
  the app's own code has to be open source, not every CI tool.
- Release signing reads the gitignored `keystore.properties` in the project
  root (same setup as the user's other apps). The upload key lives outside
  the repo. Without the file, release builds are unsigned (F-Droid signs
  them itself).
- Colors (`ui/theme`): both themes use Kiwi Green for text and accents.
  In dark mode filled buttons are Dark Green instead (`PrimaryButton`), as
  a green bright enough for text glares as a filled area. Every color role
  a component may use is set, or it falls back to Material's lavender
  (snackbars use the inverse colors). `outline` is for field borders (3:1),
  `outlineVariant` for cards. In dark mode bottom sheets are darker than the
  background (`NightSheet` as `surfaceContainerLow`), in light mode they
  keep the background color.
- The privacy policy for Google Play is `docs/privacy-policy.md`, served by
  GitHub Pages from `/docs`. Update it whenever the app starts storing,
  sending or requesting anything new (permissions, network, backups).
- Settings (cog on Home, `SettingsActivity`) has no preferences so far,
  only what the app stores and information: forget all passwords, clear
  recent files (both confirmed by a dialog, as there's no undo; open
  documents stay, as in cleanup), version (tap copies it with the Android
  version and device for bug reports), source, issues, privacy policy and
  licenses. No "Open by default" link: that system page is about web links
  and doesn't help with PDFs. Every runtime dependency is Apache 2.0, so the
  licenses screen lists them with one license text
  (`res/raw/apache_license.txt`); check the list when adding a dependency
  (`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`).
  Google's `oss-licenses` plugin needs Play Services.
- The viewer's tools live in a bottom sheet behind the ⋮ button, so the
  top bar stays calm as tools are added. Presentation mode (one page at a
  time, swipe left/right) is postponed: `PdfViewer` only scrolls
  continuously, so it needs its own pager-based view.
- The password screen's padlock (`ui/Lock.kt`) is drawn in Compose, like
  Material's lock icon, so its shackle can move: once the password is
  right it lifts until the short leg is free and swings half a turn around
  the long one, which stays in the body (seen from the front, the shackle
  narrows and widens again on the other side; the open lock isn't
  centered). It shakes (with the system's reject vibration) when the
  password is wrong. All timings are in `LockMotion`. The view
  model holds the opened document back (`NeedsPassword.unlocked`) until the
  lock has opened, then it fades in. The password is only known to be right
  once the document opened, so the animation can't start any earlier.
- Print renders password-protected documents as images, as the print
  system can't open them.
- Shared copies are deleted after a day, not right away, because
  recipients (mail drafts) may read them later.
- "Remember password" is ticked by default. Protected documents get no
  thumbnail unless their password is remembered (privacy).
- The recent files list marks protected entries with Material's padlock
  icons, not the animated `Lock`: open when the password is remembered (it
  opens without asking), closed otherwise. No animation on tap. An entry
  learns it's protected (`isProtected`) when it opens; entries saved before
  the flag count as protected if their password is remembered.
- Form edits are autosaved (`FormAutosave`) as a full filled-in PDF. When
  the original changes (hash), the filled-in version is dropped; otherwise
  reopening the original opens it without asking. Share, Print and Save a
  copy use it. It counts toward the copies' limits, and a picked file then
  only loses its filled-in version. A filled-in protected form needs the
  same password. Edits to a document without a recent entry only live in
  memory, and are written out just to share or print them.
- `androidx.pdf` form filling, as of beta01: the app applies each edit
  (`EditablePdfDocument.applyEdit`), the viewer only reports it.
  `PdfViewer` sets the document before enabling form filling, so the first
  pages load without their fields and taps on them do nothing; the viewer
  enables it once the first content has loaded, which reloads the fields.
  Closing a `PdfWriteHandle` closes the whole document, so it's never
  closed. Drop-down fields open a Material Components dialog, which crashes
  without a Material theme: the XML theme is based on Material 3 (Compose
  doesn't need it) and colors that dialog via `materialAlertDialogTheme`.
- Home collects the recent files only while resumed, so opening an entry
  (which moves it to the top) doesn't reorder the list as the viewer slides
  in. Home takes the new order when it's back.
- Removing an entry hides it in the store and deletes its files only when
  the Undo snackbar is gone (also when the screen is left); after process
  death the startup cleanup finishes the job. Snackbars with an action
  default to indefinite in Material 3, so it uses `SnackbarDuration.Long`.
- The same content gets one entry (SHA-256, computed in the background
  after opening): the picked file wins and other entries, copies included,
  go. A file from another app that matches an unchanged picked entry opens
  through that entry.
- Files from other apps are accepted only as `content://` URIs. A
  `file://` URI would be read with the app's own rights, which reach its
  private files; it gets the "not given access" error instead.
- Home (`MainActivity`) and viewer are separate activities, each with its
  own view model. `MainActivity` keeps its name because launcher shortcuts
  point to it. Documents from the app's own list and picker open in
  `ViewerActivity` in the main task; documents from other apps open in
  `ExternalViewerActivity` (`documentLaunchMode="always"`), each with a card
  of its own named after the file. Back closes such a card and returns to
  the app that opened it. A document is open in one viewer only: opening it
  again, from anywhere, switches to the viewer that has it (forms in two
  viewers would overwrite each other).
- The store records which entry each task shows, so cleanup spares every
  open document, also after process death, and a card can reopen its copy
  after its intent's permission is gone. Returning to a card counts as
  opening the document. An app update or force-stop removes the cards.
- No OCR for scanned PDFs: the usual engine (ML Kit) needs Google Play
  Services, and Tesseract is too large. (`PdfViewer` does take an
  `ocrProvider`, should a suitable engine turn up.) Searching a document
  without any text says so instead of "0 / 0"; the check reads pages until
  the first one with text and runs once per document.
- Web, mail and phone links go through `onUrlLinkClicked`: only http(s),
  mailto and tel are opened (the library's default would open any scheme).
  In-document links are
  handled by `PdfView` itself: it centers the target in the viewport (so it
  never hides under the top bar), and links with a bare `/Dest` array do
  nothing; only `/GoTo` actions work, including named destinations (LaTeX,
  Word). Nothing to hook into for either, as of beta01.
- Failures to open are diagnosed by reading the file's first bytes: no PDF
  header means not a PDF, a header (or an empty file) means damaged.
  Deleting a document revokes its URI grants, so a `SecurityException`
  can't tell "deleted" from "permission revoked": for files that opened
  before it counts as moved or deleted, otherwise as not given access.
  A failed file leaves the recent list, except when it opened before and
  still looks like a PDF (`TEMPORARY`): that's most likely a passing failure
  (the PDF service), and for a copy it's the only one there is. Such a file
  is retried once on its own after 500 ms, and only then offers "Try again"
  instead of "Open another PDF". To test it, truncate the stored copy
  (`files/recent/copies/` via `run-as`) of a file that opened before.

- A document's title (from its metadata) is shown above its file name in
  the recent files list, the viewer's top bar and the sheets' headings;
  without one, only the file name shows, as before. Every title is shown,
  even ones that look generated ("Microsoft Word - …") or repeat the file
  name: the app doesn't judge a document's content, and the file name is
  always there too. The card in the app switcher, launcher shortcuts, the
  password screen, Save a copy and Share keep the file name. The viewer
  reads the title while the document opens (from the original, not the
  filled-in version), so the top bar doesn't change; entries store it
  (`title`), a protected one's only while its password is remembered (as
  with thumbnails). Entries saved before titles were stored
  (`isTitleRead` false) get theirs when Home starts.
- "Document info", in the viewer's and the recent entries' sheets, opens a
  sheet of its own with author, subject, pages, page size, file size,
  created, modified (only when another day) and the app it was made with;
  its heading shows the title. `androidx.pdf` reads no metadata, so
  `PdfMetadataReader` reads the info dictionary and the first page's size
  itself: xref tables and streams, updates, object streams, Flate with PNG
  predictors, and a scan for objects when the offsets are wrong.
  `PdfSecurity` decrypts the standard encryption (RC4, AES-128, AES-256).
  Files with only a permissions password open without asking and decrypt
  with the empty password, so they show everything. Files that need a
  password show their title and such in the viewer, and in the recents
  sheet only when the password is remembered (as with thumbnails);
  otherwise only pages and sizes. The sheet appears once the file is read:
  a `ModalBottomSheet` whose content grows later keeps its old height.
  Sheets skip the half-open state (`skipPartiallyExpanded`): a recreated
  sheet (e.g. on switching to dark mode) returns to it, even after being
  dragged up, and the info sheet is meant to be read in full anyway. Page
  sizes are named (A4, Letter, …) or given in mm, or inches in the US.

- Launcher shortcuts (`LauncherShortcuts`, long press on the app icon) open
  the four most recent entries, on top of Home so back leads to the list.
  They follow the store's list once it's loaded (`loadedEntries`; the empty
  list before that would wipe them). A shortcut pinned to the home screen
  is disabled once its entry leaves the list (also during Undo) and enabled
  again if it comes back. Icons show the thumbnail on Kiwi Green, or the PDF
  symbol when there's none (protected without remembered password). The
  shortcut intents name `MainActivity` and `ViewerActivity`, so pinned ones
  break if those are renamed.

# Planned changes

- Search the recent files list: only matters once it gets close to 20 entries, so the search field could appear only then.
- Fullscreen (hide system bars) first; One page at a time, swipe left/right, pinch-zoom (own pager view)
- Dark pages in dark mode: `PdfViewer` always draws them white, which
  glares at night. Inverting would also shift images and form colors
- Tablets: two pages side by side (`pagesPerRow`), or a thumbnail
  side panel (`androidx.pdf` can't read a PDF's outline, so no table of
  contents)
