#!/usr/bin/env python3
"""Writes the PDFs for PdfMergerTest to app/src/test/resources/merge/.

Each one has something a merge has to carry over: attributes pages inherit, named links, a form,
layers. The tests also merge the metadata fixtures (encryption, broken offsets, outlines). Every
page says which file it is, for trying them on a phone. Needs pikepdf; run it again after
changing it and commit the files.
"""
import os

import pikepdf
from pikepdf import Array, Dictionary, Name, String

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "test", "resources", "merge")
A5 = [0, 0, 419.528, 595.276]


def font(pdf):
    return pdf.make_indirect(Dictionary(
        Type=Name.Font, Subtype=Name.Type1, BaseFont=Name.Helvetica, Encoding=Name.WinAnsiEncoding,
    ))


def text(lines, top=520):
    content = b"BT /F1 14 Tf 40 %d Td" % top
    for line in lines:
        content += b" (%s) Tj 0 -22 Td" % line.encode("cp1252")
    return content + b" ET"


def save(pdf, name, title):
    pdf.docinfo[Name.Title] = String(title)
    pdf.save(os.path.join(OUT, name), object_stream_mode=pikepdf.ObjectStreamMode.disable, compress_streams=False, deterministic_id=True)


def inherited():
    """A nested page tree whose nodes give the pages their resources, size and rotation."""
    pdf = pikepdf.new()
    resources = pdf.make_indirect(Dictionary(Font=Dictionary(F1=font(pdf))))
    pages = []
    for number in (1, 2, 3):
        page = pdf.make_indirect(Dictionary(Type=Name.Page, Contents=pdf.make_stream(
            text(["inherited.pdf", "Resources, MediaBox and Rotate", f"come from the page tree. Page {number} of 3"])
        )))
        pages.append(page)
    inner = pdf.make_indirect(Dictionary(Type=Name.Pages, Kids=Array(pages[1:]), Count=2, Rotate=90))
    root = pdf.Root.Pages
    root.Kids = Array([pages[0], inner])
    root.Count = 3
    root.Resources = resources
    root.MediaBox = Array(A5)
    for page in pages[:1]:
        page.Parent = root
    for page in pages[1:]:
        page.Parent = inner
    inner.Parent = root
    save(pdf, "inherited.pdf", "Inherited")


def named_links():
    """Links to named destinations: one in the name tree, one by a "go to" action, and the outline."""
    pdf = pikepdf.new()
    f1 = font(pdf)
    for number in (1, 2, 3):
        page = pdf.add_blank_page(page_size=(A5[2], A5[3]))
        page.obj.Resources = Dictionary(Font=Dictionary(F1=f1))
        page.obj.Contents = pdf.make_stream(text(["named-links.pdf", f"Page {number} of 3"]))
    p1, p2, p3 = (page.obj for page in pdf.pages)
    pdf.Root.Names = Dictionary(Dests=Dictionary(Names=Array([
        String("second"), Array([p2, Name.XYZ, 0, 500, None]),
        String("third"), Dictionary(D=Array([p3, Name.Fit])),
    ])))
    p1.Annots = Array([
        pdf.make_indirect(Dictionary(
            Type=Name.Annot, Subtype=Name.Link, Rect=Array([40, 400, 300, 430]), Border=Array([0, 0, 0]),
            Dest=String("second"),
        )),
        pdf.make_indirect(Dictionary(
            Type=Name.Annot, Subtype=Name.Link, Rect=Array([40, 360, 300, 390]), Border=Array([0, 0, 0]),
            A=Dictionary(S=Name.GoTo, D=String("third")),
        )),
    ])
    first = pdf.make_indirect(Dictionary(Title=String("Second page"), Dest=String("second")))
    second = pdf.make_indirect(Dictionary(Title=String("Third page"), A=Dictionary(S=Name.GoTo, D=String("third"))))
    outlines = pdf.make_indirect(Dictionary(Type=Name.Outlines, First=first, Last=second, Count=2))
    first.Parent = second.Parent = outlines
    first.Next = second
    second.Prev = first
    pdf.Root.Outlines = outlines
    save(pdf, "named-links.pdf", "Named links")


def form():
    """A text field and a checkbox, their names like any form's ("name", "agree")."""
    pdf = pikepdf.new()
    f1 = font(pdf)
    page = pdf.add_blank_page(page_size=(A5[2], A5[3]))
    page.obj.Resources = Dictionary(Font=Dictionary(F1=f1))
    page.obj.Contents = pdf.make_stream(text(["form.pdf", "Name:", "", "Agree:"]))
    name = pdf.make_indirect(Dictionary(
        Type=Name.Annot, Subtype=Name.Widget, FT=Name.Tx, T=String("name"), V=String("Pingu"),
        Rect=Array([100, 470, 380, 494]), DA=String("/Helv 12 Tf 0 g"), P=page.obj,
    ))
    agree = pdf.make_indirect(Dictionary(FT=Name.Btn, T=String("agree"), V=Name.Off))
    box = pdf.make_indirect(Dictionary(
        Type=Name.Annot, Subtype=Name.Widget, Parent=agree, Rect=Array([100, 426, 120, 446]),
        AS=Name.Off, MK=Dictionary(CA=String("4")), P=page.obj,
    ))
    agree.Kids = Array([box])
    page.obj.Annots = Array([name, box])
    pdf.Root.AcroForm = Dictionary(
        Fields=Array([name, agree]),
        DA=String("/Helv 0 Tf 0 g"),
        DR=Dictionary(Font=Dictionary(Helv=pdf.make_indirect(Dictionary(
            Type=Name.Font, Subtype=Name.Type1, BaseFont=Name.Helvetica,
        )))),
        NeedAppearances=True,
    )
    save(pdf, "form.pdf", "Form")


def layers(name, base_off):
    """Two layers, one hidden: by OFF, or with BaseState OFF by not being ON."""
    pdf = pikepdf.new()
    f1 = font(pdf)
    shown = pdf.make_indirect(Dictionary(Type=Name.OCG, Name=String("Shown")))
    hidden = pdf.make_indirect(Dictionary(Type=Name.OCG, Name=String("Hidden")))
    page = pdf.add_blank_page(page_size=(A5[2], A5[3]))
    page.obj.Resources = Dictionary(Font=Dictionary(F1=f1), Properties=Dictionary(L1=shown, L2=hidden))
    page.obj.Contents = pdf.make_stream(
        text([name]) + b" /OC /L1 BDC " + text(["This layer shows."], 470) + b" EMC"
        + b" /OC /L2 BDC " + text(["This layer is hidden."], 440) + b" EMC"
    )
    config = Dictionary(Order=Array([shown, hidden]))
    if base_off:
        config.BaseState = Name.OFF
        config.ON = Array([shown])
    else:
        config.OFF = Array([hidden])
    pdf.Root.OCProperties = Dictionary(OCGs=Array([shown, hidden]), D=config)
    save(pdf, name, "Layers")


def main():
    os.makedirs(OUT, exist_ok=True)
    inherited()
    named_links()
    form()
    layers("layers.pdf", base_off=False)
    layers("layers-base-off.pdf", base_off=True)
    print(f"Wrote the fixtures to {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
