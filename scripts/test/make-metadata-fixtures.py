#!/usr/bin/env python3
"""Writes the PDFs for PdfMetadataReaderTest to app/src/test/resources/metadata/.

Each one stores its metadata in a different way. Needs pikepdf; run it again after changing it
and commit the files.
"""
import os
import re

import pikepdf
from pikepdf import Dictionary, Name, Array, String

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "test", "resources", "metadata")
A4 = [0, 0, 595.276, 841.89]
LETTER = [0, 0, 612, 792]


def document(title="Electricity bill", pages=2, media_box=A4, rotate=None):
    pdf = pikepdf.new()
    for _ in range(pages):
        pdf.add_blank_page(page_size=(media_box[2], media_box[3]))
    if rotate is not None:
        # Inherited from the page tree's root.
        for page in pdf.pages:
            del page.obj.MediaBox
        pdf.Root.Pages.MediaBox = Array(media_box)
        pdf.Root.Pages.Rotate = rotate
    # Through docinfo, so pikepdf doesn't add XMP metadata or change the producer.
    pdf.docinfo = pdf.make_indirect(Dictionary(
        Title=String(title),
        Author=String("Kiwi Power Ltd"),
        Subject=String("March 2024"),
        Creator=String("Writer"),
        Producer=String("LibreOffice 7.6"),
        CreationDate=String("D:20240305143000+01'00'"),
        ModDate=String("D:20240306090000Z"),
    ))
    return pdf


def save(pdf, name, **options):
    pdf.save(os.path.join(OUT, name), fix_metadata_version=False, **options)


def append_update(path, info):
    """Appends a new info dictionary as an update, the way editors save changes."""
    data = open(path, "rb").read()
    prev = int(re.findall(rb"startxref\s+(\d+)", data)[-1])
    trailer = re.findall(rb"trailer\s*(<<.*?>>)\s*startxref", data, re.S)[-1]
    size = int(re.search(rb"/Size (\d+)", trailer)[1])
    root = re.search(rb"/Root (\d+ \d+ R)", trailer)[1]
    obj_offset = len(data) + 1
    update = b"\n%d 0 obj\n%s\nendobj\n" % (size, info)
    xref_offset = len(data) + len(update)
    update += b"xref\n%d 1\n%010d 00000 n \ntrailer\n<< /Size %d /Root %s /Info %d 0 R /Prev %d >>\nstartxref\n%d\n%%%%EOF\n" % (
        size, obj_offset, size + 1, root, size, prev, xref_offset)
    open(path, "wb").write(data + update)


def main():
    os.makedirs(OUT, exist_ok=True)

    # A classic cross-reference table.
    save(document(), "table.pdf", object_stream_mode=pikepdf.ObjectStreamMode.disable,
         compress_streams=False)

    # A compressed cross-reference stream, with the info dictionary in an object stream.
    save(document(), "streams.pdf", object_stream_mode=pikepdf.ObjectStreamMode.generate)

    # UTF-16 text and a page tree with inherited, rotated letter pages.
    pdf = document(title="Stromrechnung März – 2024 ✓", media_box=LETTER, rotate=90)
    pdf.docinfo.Author = String("Zoë Ångström")
    save(pdf, "unicode.pdf", object_stream_mode=pikepdf.ObjectStreamMode.disable)

    # An update appended to the file changes the title.
    path = os.path.join(OUT, "updated.pdf")
    save(document(title="Draft"), "updated.pdf", object_stream_mode=pikepdf.ObjectStreamMode.disable)
    append_update(path, b"<< /Title (Final) /Author (Kiwi Power Ltd) >>")

    # Encrypted with an owner password only: opens without asking, but the strings are encrypted.
    save(document(), "encrypted.pdf", object_stream_mode=pikepdf.ObjectStreamMode.disable,
         encryption=pikepdf.Encryption(owner="owner", user="", R=4))

    # The cross-reference table's offsets are wrong (as after a careless edit); readers scan.
    save(document(title="Shifted"), "broken-xref.pdf",
         object_stream_mode=pikepdf.ObjectStreamMode.disable, compress_streams=False)
    path = os.path.join(OUT, "broken-xref.pdf")
    data = open(path, "rb").read()
    data = re.sub(rb"(\d{10}) (\d{5}) n", lambda m: b"%010d %s n" % (int(m[1]) + 7, m[2]), data)
    open(path, "wb").write(data)

    # No info dictionary at all.
    pdf = pikepdf.new()
    pdf.add_blank_page(page_size=(A4[2], A4[3]))
    save(pdf, "no-info.pdf", object_stream_mode=pikepdf.ObjectStreamMode.disable)

    for name in sorted(os.listdir(OUT)):
        os.chmod(os.path.join(OUT, name), 0o644)
        print(os.path.join(OUT, name))


if __name__ == "__main__":
    main()
