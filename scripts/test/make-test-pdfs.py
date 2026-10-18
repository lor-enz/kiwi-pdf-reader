#!/usr/bin/env python3
"""Generates the PDFs for manual and adb-driven testing.

Usage: scripts/test/make-test-pdfs.py [output dir]   (default: build/test-pdfs)

Needs reportlab (with Pillow) and qpdf (for the link variants reportlab can't write).
"""
import io
import os
import re
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont
from reportlab.lib.pagesizes import A4
from reportlab.lib.pdfencrypt import StandardEncryption
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas

W, H = A4
PASSWORD = "kiwi"


def links(path):
    """Every kind of link. In-document links are rewritten below, as reportlab only writes /Dest."""
    c = canvas.Canvas(path, pagesize=A4)
    c.setFont("Helvetica-Bold", 22)
    c.drawString(60, H - 60, "Links")
    c.setFont("Helvetica", 18)
    y = H - 130
    for label, url in [
        ("Web: https://example.com", "https://example.com"),
        ("Mail: mailto:test@example.com", "mailto:test@example.com"),
        ("Phone: tel:+4930123456", "tel:+4930123456"),
        ("No scheme: www.example.org", "www.example.org"),
        ("Not allowed: geo:52.5,13.4", "geo:52.5,13.4"),
    ]:
        c.drawString(60, y, label)
        c.linkURL(url, (55, y - 8, 500, y + 22), relative=0)
        y -= 60
    for label, dest in [
        ("GoTo action: page 3, top", "goto_p3"),
        ("Named destination: page 2, middle", "named_p2"),
        ("Bare /Dest (does nothing, library): page 3", "dest_p3"),
    ]:
        c.drawString(60, y, label)
        c.linkRect("", dest, (55, y - 8, 500, y + 22), relative=0)
        y -= 60
    c.showPage()
    c.setFont("Helvetica", 18)
    c.drawString(60, H - 60, "Page 2 top")
    c.bookmarkPage("named_p2", fit="XYZ", left=0, top=H / 2 + 30)
    c.drawString(60, H / 2, "Page 2 MIDDLE TARGET")
    c.showPage()
    c.bookmarkPage("goto_p3", fit="XYZ", left=0, top=H)
    c.bookmarkPage("dest_p3", fit="XYZ", left=0, top=H)
    c.setFont("Helvetica-Bold", 22)
    c.drawString(60, H - 50, "Page 3 TARGET HEADING")
    c.showPage()
    c.save()
    rewrite_goto_links(path)


def rewrite_goto_links(path):
    """Turns the first /Dest into a /GoTo action and the second into a named destination."""
    with tempfile.TemporaryDirectory() as tmp:
        qdf = os.path.join(tmp, "qdf.pdf")
        subprocess.run(["qpdf", "--qdf", "--object-streams=disable", path, qdf], check=True)
        data = open(qdf, "rb").read()
        dests = re.findall(rb"/Dest (\[[^\]]*\])", data)
        assert len(dests) == 3, dests
        data = data.replace(b"/Dest " + dests[0], b"/A << /S /GoTo /D " + dests[0] + b" >>", 1)
        data = data.replace(b"/Dest " + dests[1], b"/A << /S /GoTo /D (named_p2) >>", 1)
        data = data.replace(
            b"/Type /Catalog",
            b"/Type /Catalog /Names << /Dests << /Names [ (named_p2) " + dests[1] + b" ] >> >>",
            1,
        )
        open(qdf, "wb").write(data)
        fixed = subprocess.run(["fix-qdf", qdf], check=True, capture_output=True).stdout
    open(path, "wb").write(fixed)


def text_pages(path, pages, encrypt=None):
    """Searchable text; "kiwi" appears on every fifth page."""
    c = canvas.Canvas(path, pagesize=A4, encrypt=encrypt)
    for n in range(1, pages + 1):
        c.setFont("Helvetica-Bold", 28)
        c.drawString(60, H - 80, f"Page {n}")
        c.setFont("Helvetica", 14)
        for line in range(30):
            word = "kiwi" if n % 5 == 0 and line == 10 else "lorem ipsum"
            c.drawString(60, H - 140 - line * 22, f"Line {line + 1} on page {n}: {word} dolor sit amet")
        c.showPage()
    c.save()


def scanned(path, pages=3):
    """Pages that are only images of text, like a scan."""
    c = canvas.Canvas(path, pagesize=A4)
    font = ImageFont.load_default(size=36)
    for n in range(1, pages + 1):
        image = Image.new("L", (1240, 1754), 255)
        draw = ImageDraw.Draw(image)
        draw.text((120, 120), f"Scanned page {n}", font=font, fill=0)
        for line in range(20):
            draw.text((120, 240 + line * 60), f"This text is only an image. Line {line + 1}.", font=font, fill=40)
        buffer = io.BytesIO()
        image.save(buffer, "PNG")
        buffer.seek(0)
        c.drawImage(ImageReader(buffer), 0, 0, W, H)
        c.showPage()
    c.save()


def form(path):
    c = canvas.Canvas(path, pagesize=A4)
    c.setFont("Helvetica-Bold", 22)
    c.drawString(60, H - 60, "Form")
    c.setFont("Helvetica", 16)
    f = c.acroForm
    c.drawString(60, H - 130, "Name")
    f.textfield(name="name", x=200, y=H - 140, width=300, height=28)
    c.drawString(60, H - 190, "Agree")
    f.checkbox(name="agree", x=200, y=H - 198, size=24)
    c.drawString(60, H - 250, "Size")
    for i, value in enumerate(["S", "M", "L"]):
        f.radio(name="size", value=value, x=200 + i * 60, y=H - 258, size=24)
        c.drawString(230 + i * 60, H - 250, value)
    c.drawString(60, H - 310, "Colour")
    f.choice(name="colour", options=["Green", "Brown", "Yellow"], value="Green", x=200, y=H - 320, width=200, height=28)
    c.showPage()
    c.save()


def broken(out):
    open(os.path.join(out, "not-a-pdf.pdf"), "w").write("Hello, this is plain text, not a PDF.\n")
    with open(os.path.join(out, "damaged.pdf"), "wb") as f:
        f.write(b"%PDF-1.4\n" + os.urandom(4000))
    whole = open(os.path.join(out, "long.pdf"), "rb").read()
    open(os.path.join(out, "truncated.pdf"), "wb").write(whole[: len(whole) // 3])
    open(os.path.join(out, "empty.pdf"), "wb").close()


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join("build", "test-pdfs")
    os.makedirs(out, exist_ok=True)
    links(os.path.join(out, "links.pdf"))
    text_pages(os.path.join(out, "long.pdf"), pages=60)
    text_pages(os.path.join(out, "protected.pdf"), pages=5, encrypt=StandardEncryption(PASSWORD, canPrint=1))
    scanned(os.path.join(out, "scanned.pdf"))
    form(os.path.join(out, "form.pdf"))
    broken(out)
    for name in sorted(os.listdir(out)):
        print(os.path.join(out, name))
    print(f"protected.pdf password: {PASSWORD}")


if __name__ == "__main__":
    main()
