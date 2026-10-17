# How to work here

- Commit atomically — one logical change per commit.
- Use gitmoji for commit messages, following the conventions in [`gitmoji.md`](gitmoji.md).

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

- PDF rendering uses `androidx.pdf` (beta) with the Compose `PdfViewer`,
  which is still `@ExperimentalPdfApi`. Fallback if it misbehaves: host
  `PdfViewerFragment` via `fragment-compose`.
- Devices without SDK extension 13 get a compat renderer: viewing works,
  search/selection don't. Check `PdfFeature` support instead of blocking.
- `PdfView` draws search highlights with a MULTIPLY blend, so highlight
  colors must be opaque and light.
- Keep the app free of Google Play Services (open source, F-Droid).

# Recent files and private copies

Files opened from other apps (WhatsApp, mail, share sheet) usually lose
their access permission once we close them. So that they can still be
reopened from the recent files list, the app keeps a private copy.
Files picked with the app's own file picker keep a persistable
permission instead and are not copied.

Fixed rules, not configurable:
- A copy is deleted 30 days after it was last opened (not after it was
  copied), so files the user keeps returning to never expire.
- All copies together may use at most 250 MB. Above that, the least
  recently opened copies are deleted first.
- The currently open document is never deleted, even if it alone is
  larger than 250 MB. It is cleaned up on a later run once it is closed.
- Copies live in the app's files directory, not the cache, so Android
  cannot delete them behind the list's back.
- Copies are excluded from Android backup (`data_extraction_rules.xml`,
  `backup_rules.xml`), so other people's PDFs don't end up in the user's
  cloud backup.
- When a copy is deleted, its recent files entry is removed too, so the
  list never shows a file that cannot be opened.
- The recent files list shows at most about 20 entries, to keep the home
  screen short. This only limits the list, not the storage.
- Each entry shows a thumbnail of the first page.

# TODO

## Milestone 2: usable app
- [ ] Recent files list with first-page thumbnails on the home screen
- [ ] Private copies of files opened from other apps, with the cleanup
      rules above
- [ ] Persistable permissions for files from the file picker; handle
      deleted or moved files
- [ ] Remember the last page per document
- [ ] Restore the open document after process death

## Milestone 3: edge cases & branding
- [ ] Password-protected PDFs: ask for the password (currently shows
      "not supported yet")
- [ ] Top bar takes too much space in landscape (hide or auto-hide it)
- [ ] Fast-scroll page number indicator is black and invisible on dark
      pages: style it via `FastScrollConfiguration`
- [ ] Full styling from `brand-identity.txt` (typography, components)
- [ ] App icon
- [ ] Test on a device without SDK extension 13 (search icon hidden?)

## Milestone 4: release (optional)
- [ ] Release signing, check the R8 release build on a device
- [ ] Play Store screenshots and listing text from the README

## Done
- [x] Milestone 1: viewer spike. Tested on a real phone with a 26 MB,
      28-page image-heavy PDF: opening from WhatsApp, file manager and
      share sheet, and rotation all work.
