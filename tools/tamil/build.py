"""Build qos Desktop's Tamil catalogues from the glossary.

The glossary is every .tsv file in glossary/: common.tsv for words shared by
all of XFCE, and a file per component (gettext domain) for the rest; a
component's own file wins where two translate the same English. A line is
English<TAB>Tamil, or English<TAB>Tamil for one<TAB>Tamil for more than one
where XFCE has plural forms. The English is as XFCE shows it, without the "_"
that marks a keyboard shortcut and without a trailing "..."; both are put back,
the shortcut as "(_X)" after the Tamil. \\n and \\t stand for a new line and a
tab. %s, %d and the like must match on both sides, or the line is left out.

For each XFCE component (at the version Termux ships), read every English
string from upstream's en_GB.po, translate those the glossary has, and write
po/<domain>.po (to review), untranslated/<domain>.txt (what is left), and the
compiled .mo that qos installs, under app/src/main/assets/qos/locale/ta.
names.tsv is copied next to it for qos-xsession. Run it again after changing
a .tsv file:

    python tools/tamil/build.py
"""
import re
import struct
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
ASSETS = ROOT / "app/src/main/assets/qos"
OUT_MO = ASSETS / "locale/ta/LC_MESSAGES"

XFCE = "https://gitlab.xfce.org/api/v4/projects/{project}/repository/files/po%2Fen_GB.po/raw?ref={tag}"
GNOME = "https://gitlab.gnome.org/GNOME/{project}/-/raw/{tag}/po/en_GB.po"

# gettext domain: where its English strings come from. Versions match Termux's packages.
SOURCES = {
    "xfdesktop": XFCE.format(project="xfce%2Fxfdesktop", tag="xfdesktop-4.20.2"),
    "xfce4-panel": XFCE.format(project="xfce%2Fxfce4-panel", tag="xfce4-panel-4.20.8"),
    "xfce4-session": XFCE.format(project="xfce%2Fxfce4-session", tag="xfce4-session-4.20.4"),
    "thunar": XFCE.format(project="xfce%2Fthunar", tag="thunar-4.20.10"),
    "xfwm4": XFCE.format(project="xfce%2Fxfwm4", tag="xfwm4-4.20.0"),
    "xfce4-terminal": XFCE.format(project="apps%2Fxfce4-terminal", tag="xfce4-terminal-1.2.0"),
    "libxfce4ui": XFCE.format(project="xfce%2Flibxfce4ui", tag="libxfce4ui-4.20.2"),
    "exo": XFCE.format(project="xfce%2Fexo", tag="exo-4.21.0"),
    "xfce4-settings": XFCE.format(project="xfce%2Fxfce4-settings", tag="xfce4-settings-4.20.5"),
    "gtk30": GNOME.format(project="gtk", tag="3.24.52"),
}

FORMAT = re.compile(r"%(?:\d+\$)?[-+ #0]*\d*(?:\.\d+)?(?:hh|h|ll|l|L|z|j|t)?[diouxXeEfgGcsp%]")


def unescape(s):
    return re.sub(r'\\(["\\nt])', lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), s)


def escape(s):
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t")


def read_tsv(path):
    """English: [Tamil] or [Tamil for one, Tamil for more]."""
    table = {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        fields = [unescape(f) for f in line.split("\t")]
        if len(fields) not in (2, 3) or not all(fields):
            sys.exit(f"{path.name}:{number}: expected English<TAB>Tamil[<TAB>Tamil plural]")
        table[fields[0]] = fields[1:]
    return table


def read_glossary(domain):
    """Every glossary file, with the domain's own (domain.tsv, domain-*.tsv) taking precedence."""
    def own(path):
        return path.stem == domain or path.stem.startswith(domain + "-")
    glossary = {}
    for path in sorted((HERE / "glossary").glob("*.tsv"), key=lambda p: (own(p), p.name)):
        glossary.update(read_tsv(path))
    return glossary


def entries(po_text):
    """(context, msgid, msgid_plural or None) of each entry in a .po file."""
    out = []
    for block in po_text.split("\n\n"):
        if block.lstrip().startswith("#~"):
            continue
        m = re.search(r'^msgid ((?:".*"\n?)+)', block, re.M)
        if not m:
            continue
        msgid = unescape("".join(re.findall(r'"(.*)"', m.group(1))))
        p = re.search(r'^msgid_plural ((?:".*"\n?)+)', block, re.M)
        plural = unescape("".join(re.findall(r'"(.*)"', p.group(1)))) if p else None
        ctx = re.search(r'^msgctxt "(.*)"', block, re.M)
        if msgid:
            out.append((unescape(ctx.group(1)) if ctx else None, msgid, plural))
    return out


def key_of(msgid):
    """msgid without its shortcut marker and trailing ellipsis, as the glossary has it."""
    text = msgid
    for e in ("...", "…"):
        if text.endswith(e):
            text = text[: -len(e)]
    return text.replace("_", "") if re.search(r"_[^\W_]", text) else text


def translate(msgid, glossary, plural=None):
    """The Tamil for msgid as a list (one form, or two for plurals), keeping its
    keyboard shortcut and ellipsis; None if the glossary has none."""
    ellipsis = next((e for e in ("...", "…") if msgid.endswith(e)), "")
    m = re.search(r"_([^\W_])", msgid)
    mnemonic = m.group(1).upper() if m else ""
    forms = glossary.get(key_of(msgid))
    if forms is None or (plural is None) != (len(forms) == 1):
        return None
    for english, tamil in zip([msgid] if plural is None else [msgid, plural], forms):
        if sorted(FORMAT.findall(english)) != sorted(FORMAT.findall(tamil)):
            return None
    return [t + (f" (_{mnemonic})" if mnemonic else "") + ellipsis for t in forms]


def write_mo(path, messages):
    """A .mo file: the gettext binary format, originals sorted as the lookup expects."""
    items = sorted(((k.encode("utf-8"), v.encode("utf-8")) for k, v in messages.items()), key=lambda kv: kv[0])
    n = len(items)
    strings_at = 28 + n * 16
    keys = vals = b""
    key_table, val_table = [], []
    for k, _ in items:
        key_table.append((len(k), strings_at + len(keys)))
        keys += k + b"\0"
    vals_at = strings_at + len(keys)
    for _, v in items:
        val_table.append((len(v), vals_at + len(vals)))
        vals += v + b"\0"
    data = struct.pack("<7I", 0x950412DE, 0, n, 28, 28 + n * 8, 0, 0)
    data += b"".join(struct.pack("<2I", *e) for e in key_table)
    data += b"".join(struct.pack("<2I", *e) for e in val_table)
    path.write_bytes(data + keys + vals)


def main():
    read_tsv(HERE / "names.tsv")  # checked for its form; qos-xsession reads it
    OUT_MO.mkdir(parents=True, exist_ok=True)
    for d in ("po", "untranslated"):
        (HERE / d).mkdir(exist_ok=True)
    header = "Content-Type: text/plain; charset=UTF-8\nLanguage: ta\nPlural-Forms: nplurals=2; plural=(n != 1);\n"
    for domain, url in SOURCES.items():
        glossary = read_glossary(domain)
        source = urllib.request.urlopen(url, timeout=120).read().decode("utf-8")
        messages, po, missing = {"": header}, [f'msgid ""\nmsgstr "{escape(header)}"\n'], []
        for ctx, msgid, plural in entries(source):
            key = f"{ctx}\x04{msgid}" if ctx else msgid
            if key in messages:
                continue
            forms = translate(msgid, glossary, plural)
            if forms is None:
                missing.append("\t".join(escape(s) for s in (key_of(msgid), key_of(plural) if plural else "") if s))
                continue
            messages[key] = "\0".join(forms)
            entry = (f'msgctxt "{escape(ctx)}"\n' if ctx else "") + f'msgid "{escape(msgid)}"\n'
            if plural is None:
                entry += f'msgstr "{escape(forms[0])}"\n'
            else:
                entry += f'msgid_plural "{escape(plural)}"\nmsgstr[0] "{escape(forms[0])}"\nmsgstr[1] "{escape(forms[1])}"\n'
            po.append(entry)
        (HERE / "po" / f"{domain}.po").write_text("\n".join(po), encoding="utf-8", newline="\n")
        (HERE / "untranslated" / f"{domain}.txt").write_text("".join(m + "\n" for m in dict.fromkeys(missing)), encoding="utf-8", newline="\n")
        write_mo(OUT_MO / f"{domain}.mo", messages)
        print(f"{domain}: {len(messages) - 1} translated, {len(set(missing))} not")
    (ASSETS / "tamil-names.tsv").write_text((HERE / "names.tsv").read_text(encoding="utf-8"), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    sys.exit(main())
