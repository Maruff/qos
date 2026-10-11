"""Build qos Desktop's Tamil catalogues from glossary.tsv.

For each XFCE component (at the version Termux ships), read every English
string from upstream's en_GB.po, translate those the glossary has, and write
po/<domain>.po (to review) and the compiled .mo that qos installs, under
app/src/main/assets/qos/locale/ta/LC_MESSAGES. names.tsv is copied next to it
for qos-xsession. Run it again after changing either .tsv file:

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


def read_tsv(path):
    table = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        english, tamil = line.split("\t")
        table[english] = tamil
    return table


def entries(po_text):
    """(context, msgid) of each singular entry in a .po file; plural ones are left out."""
    out = []
    for block in po_text.split("\n\n"):
        if re.search(r"^msgid_plural ", block, re.M):
            continue
        m = re.search(r'^msgid ((?:".*"\n?)+)', block, re.M)
        if not m:
            continue
        msgid = unescape("".join(re.findall(r'"(.*)"', m.group(1))))
        ctx = re.search(r'^msgctxt "(.*)"', block, re.M)
        if msgid:
            out.append((unescape(ctx.group(1)) if ctx else None, msgid))
    return out


def unescape(s):
    return re.sub(r'\\(["\\nt])', lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), s)


def escape(s):
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t")


def translate(msgid, glossary):
    """The Tamil for msgid, keeping its keyboard shortcut and ellipsis, or None."""
    text, ellipsis = msgid, ""
    for e in ("...", "…"):
        if text.endswith(e):
            text, ellipsis = text[: -len(e)], e
    mnemonic = ""
    if "_" in text:
        i = text.index("_")
        if i + 1 < len(text) and text[i + 1].isalnum():
            mnemonic = text[i + 1]
        text = text.replace("_", "")
    tamil = glossary.get(text)
    if tamil is None:
        return None
    if sorted(FORMAT.findall(msgid)) != sorted(FORMAT.findall(tamil)):
        return None
    return tamil + (f" (_{mnemonic.upper()})" if mnemonic else "") + ellipsis


def write_mo(path, messages):
    """A .mo file: the gettext binary format, originals sorted as the lookup expects."""
    items = sorted(((k.encode("utf-8"), v.encode("utf-8")) for k, v in messages.items()), key=lambda kv: kv[0])
    n = len(items)
    originals_at, translations_at = 28, 28 + n * 8
    strings_at = 28 + n * 16
    keys = vals = b""
    key_table, val_table = [], []
    for k, v in items:
        key_table.append((len(k), strings_at + len(keys)))
        keys += k + b"\0"
    vals_at = strings_at + len(keys)
    for k, v in items:
        val_table.append((len(v), vals_at + len(vals)))
        vals += v + b"\0"
    data = struct.pack("<7I", 0x950412DE, 0, n, originals_at, translations_at, 0, 0)
    data += b"".join(struct.pack("<2I", *e) for e in key_table)
    data += b"".join(struct.pack("<2I", *e) for e in val_table)
    path.write_bytes(data + keys + vals)


def main():
    glossary = read_tsv(HERE / "glossary.tsv")
    read_tsv(HERE / "names.tsv")  # checked for its form; qos-xsession reads it
    OUT_MO.mkdir(parents=True, exist_ok=True)
    (HERE / "po").mkdir(exist_ok=True)
    header = "Content-Type: text/plain; charset=UTF-8\nLanguage: ta\n"
    for domain, url in SOURCES.items():
        source = urllib.request.urlopen(url, timeout=120).read().decode("utf-8")
        messages, po = {"": header}, [f'msgid ""\nmsgstr "{escape(header)}"\n']
        for ctx, msgid in entries(source):
            tamil = translate(msgid, glossary)
            if tamil is None:
                continue
            key = f"{ctx}\x04{msgid}" if ctx else msgid
            if key in messages:
                continue
            messages[key] = tamil
            po.append((f'msgctxt "{escape(ctx)}"\n' if ctx else "") + f'msgid "{escape(msgid)}"\nmsgstr "{escape(tamil)}"\n')
        (HERE / "po" / f"{domain}.po").write_text("\n".join(po), encoding="utf-8", newline="\n")
        write_mo(OUT_MO / f"{domain}.mo", messages)
        print(f"{domain}: {len(messages) - 1} strings")
    (ASSETS / "tamil-names.tsv").write_text((HERE / "names.tsv").read_text(encoding="utf-8"), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    sys.exit(main())
