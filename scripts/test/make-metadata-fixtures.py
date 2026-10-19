#!/usr/bin/env python3
"""Writes the PDFs for PdfMetadataReaderTest to app/src/test/resources/metadata/.

Each one stores its metadata in a different way. Every page says which file it is and what it
tests, for trying them on a phone. Needs pikepdf; run it again after changing it and commit the
files.
"""
import os
import re

import pikepdf
from pikepdf import Dictionary, Name, Array, String

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "test", "resources", "metadata")
A4 = [0, 0, 595.276, 841.89]
PASSWORD = "kiwi"
LETTER = [0, 0, 612, 792]


def add_pages(pdf, name, about, pages=2, media_box=A4):
    """Pages with the file name, what it's for and the page number."""
    font = pdf.make_indirect(Dictionary(
        Type=Name.Font, Subtype=Name.Type1, BaseFont=Name.Helvetica, Encoding=Name.WinAnsiEncoding,
    ))
    for number in range(1, pages + 1):
        page = pdf.add_blank_page(page_size=(media_box[2], media_box[3]))
        page.obj.Resources = Dictionary(Font=Dictionary(F1=font))
        top = media_box[3] - 80
        lines = [(22, name), (12, "")] + [(14, line) for line in about] + [(12, ""), (14, f"Page {number} of {pages}")]
        content = b"BT 60 %d Td" % top
        for size, line in lines:
            text = line.encode("cp1252").replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)")
            content += b" /F1 %d Tf (%s) Tj 0 -%d Td" % (size, text, size + 10)
        page.obj.Contents = pdf.make_stream(content + b" ET")


def document(name, about, title="Electricity bill", pages=2, media_box=A4, rotate=None):
    pdf = pikepdf.new()
    add_pages(pdf, name, about, pages, media_box)
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
    bill = "Title \"Electricity bill\", author Kiwi Power Ltd, subject March 2024, A4."
    save(document("table.pdf", [bill, "Stored the classic way: a cross-reference table, nothing compressed."]),
         "table.pdf", object_stream_mode=pikepdf.ObjectStreamMode.disable, compress_streams=False)

    # A compressed cross-reference stream, with the info dictionary in an object stream.
    save(document("streams.pdf", [bill, "Compressed: the info is inside an object stream."]),
         "streams.pdf", object_stream_mode=pikepdf.ObjectStreamMode.generate)

    # UTF-16 text and a page tree with inherited, rotated letter pages.
    pdf = document("unicode.pdf", [
        "Title \"Stromrechnung März – 2024\" with a check mark, author Zoë Ångström.",
        "Letter pages, turned to landscape by the page tree (so this text runs sideways).",
    ], title="Stromrechnung März – 2024 ✓", media_box=LETTER, rotate=90)
    pdf.docinfo.Author = String("Zoë Ångström")
    save(pdf, "unicode.pdf", object_stream_mode=pikepdf.ObjectStreamMode.disable)

    # An update appended to the file changes the title.
    path = os.path.join(OUT, "updated.pdf")
    save(document("updated.pdf", [
        "Saved as \"Draft\", then edited: the title should say \"Final\" (and no subject).",
    ], title="Draft"), "updated.pdf", object_stream_mode=pikepdf.ObjectStreamMode.disable)
    append_update(path, b"<< /Title (Final) /Author (Kiwi Power Ltd) >>")

    # Encrypted. With only a permissions password ("owner") they open without asking; the others
    # need "kiwi" to open, or the permissions password.
    disable = pikepdf.ObjectStreamMode.disable
    for name, user, revision, aes, streams, method in [
        ("rc4-40.pdf", "", 2, False, disable, "RC4 40-bit (very old)"),
        ("rc4-128-password.pdf", PASSWORD, 3, False, disable, "RC4 128-bit"),
        ("aes-128.pdf", "", 4, True, disable, "AES 128-bit"),
        ("aes-256.pdf", "", 6, True, pikepdf.ObjectStreamMode.generate, "AES 256-bit, compressed"),
        ("aes-256-password.pdf", PASSWORD, 6, True, disable, "AES 256-bit"),
    ]:
        opening = (f"Needs the password \"{PASSWORD}\" to open (or \"owner\")." if user else
                   "Opens without a password; \"owner\" only guards printing and copying.")
        save(document(name, [bill, f"Encrypted with {method}.", opening]), name, object_stream_mode=streams,
             encryption=pikepdf.Encryption(owner="owner", user=user, R=revision, aes=aes, metadata=revision >= 4))

    # The cross-reference table's offsets are wrong (as after a careless edit); readers scan.
    save(document("broken-xref.pdf", [
        "Title \"Shifted\". Its table of contents points to the wrong places,",
        "so readers have to search the file for its parts.",
    ], title="Shifted"), "broken-xref.pdf",
         object_stream_mode=pikepdf.ObjectStreamMode.disable, compress_streams=False)
    path = os.path.join(OUT, "broken-xref.pdf")
    data = open(path, "rb").read()
    data = re.sub(rb"(\d{10}) (\d{5}) n", lambda m: b"%010d %s n" % (int(m[1]) + 7, m[2]), data)
    open(path, "wb").write(data)

    # No info dictionary at all.
    pdf = pikepdf.new()
    add_pages(pdf, "no-info.pdf", ["No metadata at all: only pages, page size (A4) and file size."], pages=1)
    save(pdf, "no-info.pdf", object_stream_mode=pikepdf.ObjectStreamMode.disable)

    for name in sorted(os.listdir(OUT)):
        os.chmod(os.path.join(OUT, name), 0o644)
        print(os.path.join(OUT, name))


if __name__ == "__main__":
    main()
