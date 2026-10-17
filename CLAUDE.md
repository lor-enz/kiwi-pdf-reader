# How to work here

- Commit atomically — one logical change per commit.
- Use gitmoji for commit messages, following the conventions in [`gitmoji.md`](gitmoji.md).

# Project notes

- PDF rendering uses `androidx.pdf` (beta) with the Compose `PdfViewer`,
  which is still `@ExperimentalPdfApi`. Fallback if it misbehaves: host
  `PdfViewerFragment` via `fragment-compose`.
- Devices without SDK extension 13 get a compat renderer: viewing works,
  search/selection don't. Check `PdfFeature` support instead of blocking.
- `PdfView` draws search highlights with a MULTIPLY blend, so highlight
  colors must be opaque and light.
- Keep the app free of Google Play Services (open source, F-Droid).

# TODO

## Milestone 2: usable app
- [ ] Recent files list on the home screen (`takePersistableUriPermission`,
      handle deleted/moved files)
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
