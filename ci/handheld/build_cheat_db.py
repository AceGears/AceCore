#!/usr/bin/env python3
"""
Builds AceArcade's handheld database (assets/handheld/handheld.db, SQLite) from a checkout of
libretro-database (https://github.com/libretro/libretro-database, CC BY-SA 4.0):

  * releases   - every known ROM of Game Boy, Game Boy Color, Game Boy Advance and Nintendo DS from the No-Intro
                 catalogue (metadat/no-intro) plus the ROM-hack and homebrew catalogues (metadat/hacks, /homebrew):
                 name, region, revision, game code ("serial"), size, CRC32, MD5, SHA-1. Identification is by
                 checksum first; the header game code is only used to suggest probable matches.
  * cheat sets - every .cht file for those systems (cht/), parsed into cheats (description, code, group). Identical
                 sets are stored once (zlib-compressed JSON). Entries without a code are group headings in the
                 source files and become the cheats' groups.
  * matches    - which cheat sets belong to which release: "exact" when the set was written for exactly that
                 release (same No-Intro name, ignoring a trailing cheat-device tag such as "(Code Breaker)"),
                 "probable" when only the title matches (another region/revision, older naming, or a hack built
                 on that game). The app shows probable sets only with a warning and after confirmation.

Every cheat starts as "unverified": the source doesn't record whether a code was tested. The app records its own
verification (per ROM checksum) on the device.

Usage: build_cheat_db.py LIBRETRO_DATABASE_DIR OUT.db [revision]
"""
import hashlib
import json
import os
import re
import sqlite3
import sys
import time
import zlib

SCHEMA_VERSION = 1

PLATFORMS = {
    "gb": "Nintendo - Game Boy",
    "gbc": "Nintendo - Game Boy Color",
    "gba": "Nintendo - Game Boy Advance",
    "nds": "Nintendo - Nintendo DS",
}

# Cheat-device tags some file names carry after the release name.
DEVICE_TAGS = re.compile(r"\s*\((Code ?Breaker|GameShark|Game Shark|Action Replay(?: MAX)?|Codebreaker|CodeBreaker|GS|AR|Gameshark)\)\s*",
                         re.IGNORECASE)
# GoodTools-style flags: [!], [b1], [h1C] ...
GOOD_FLAGS = re.compile(r"\s*\[[^\]]*\]")


def norm_title(name):
    """Title without any parenthesised/bracketed parts, lower-case, punctuation folded: for probable matching."""
    t = GOOD_FLAGS.sub("", name)
    t = re.sub(r"\([^()]*(\([^()]*\)[^()]*)*\)", "", t)
    t = t.lower().replace("&", "and")
    t = re.sub(r"[^a-z0-9]+", " ", t).strip()
    return t


def name_tags(name):
    """The parenthesised tags of a No-Intro style name (region, languages, revision, ...)."""
    return [m.strip() for m in re.findall(r"\(([^()]*)\)", name)]


def region_of(name):
    for tag in name_tags(name):
        parts = [p.strip() for p in tag.split(",")]
        known = {"USA", "Europe", "Japan", "World", "Germany", "France", "Spain", "Italy", "Korea", "China", "Australia",
                 "Netherlands", "Sweden", "Brazil", "Canada", "Asia", "Taiwan", "Hong Kong", "Russia", "UK", "Denmark",
                 "Norway", "Finland", "Portugal", "Greece", "Poland", "Unknown", "Scandinavia", "Latin America"}
        if parts and all(p in known for p in parts):
            return tag
    # GoodTools codes
    m = re.search(r"\((U|E|J|G|F|S|I|K|UE|JU|JUE|EU)\)", name)
    return {"U": "USA", "E": "Europe", "J": "Japan", "G": "Germany", "F": "France", "S": "Spain", "I": "Italy",
            "K": "Korea", "UE": "USA, Europe", "JU": "Japan, USA", "JUE": "World", "EU": "Europe"}.get(m.group(1)) if m else None


def revision_of(name):
    for tag in name_tags(name):
        if re.fullmatch(r"Rev \w+|v\d+(\.\d+)*[a-z]?|V\d+(\.\d+)*", tag):
            return tag
    return None


# ------------------------------------------------------------------ DAT (clrmamepro) parsing

def parse_dat(path):
    """Yields dicts {name, region, serial, roms:[{name,size,crc,md5,sha1,serial}], description, patch} per game."""
    text = open(path, encoding="utf-8", errors="replace").read()
    for block in re.finditer(r"^game \($(.*?)^\)$", text, re.S | re.M):
        body = block.group(1)
        g = {"roms": []}
        for key in ("name", "region", "serial", "description", "patch"):
            m = re.search(r'^\s*' + key + r'\s+"((?:[^"\\]|\\.)*)"', body, re.M)
            if m:
                g[key] = m.group(1)
        for rm in re.finditer(r"^\s*rom \((.*)\)\s*$", body, re.M):
            r = {}
            line = rm.group(1)
            m = re.search(r'name "((?:[^"\\]|\\.)*)"', line)
            if m:
                r["name"] = m.group(1)
            for key in ("size", "crc", "md5", "sha1"):
                m = re.search(r"\b" + key + r"\s+([0-9A-Fa-f]+)", line)
                if m:
                    r[key] = m.group(1)
            m = re.search(r'serial "([^"]*)"', line)
            if m:
                r["serial"] = m.group(1)
            g["roms"].append(r)
        if "name" in g:
            yield g


# ------------------------------------------------------------------ .cht parsing

CHT_LINE = re.compile(r'^\s*(\w+)\s*=\s*(?:"(.*)"|(\S+))\s*$')


def parse_cht(path):
    """Returns [(desc, code_or_None, enabled_default)] in file order."""
    values = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        m = CHT_LINE.match(line.rstrip("\r\n"))
        if m:
            values[m.group(1)] = m.group(2) if m.group(2) is not None else m.group(3)
    n = int(values.get("cheats", "0") or 0)
    out = []
    for i in range(n):
        desc = (values.get(f"cheat{i}_desc") or "").strip()
        code = (values.get(f"cheat{i}_code") or "").strip()
        enable = (values.get(f"cheat{i}_enable") or "false").lower() == "true"
        out.append((desc, code or None, enable))
    return out


MASTER = re.compile(r"master code|\(m\)|must be on|enable code|\bmaster\b", re.I)


def cheats_of(entries):
    """Cheats with their group (the last code-less heading before them); headings themselves are dropped."""
    group = None
    out = []
    for desc, code, enable in entries:
        if code is None:
            group = desc or None
            continue
        c = {"d": desc or "(unnamed)", "c": code}
        if group:
            c["g"] = group
        if MASTER.search(desc or ""):
            c["m"] = 1
        if enable:
            c["e"] = 1
        out.append(c)
    return out


def device_of(file_name):
    m = DEVICE_TAGS.search(file_name)
    if not m:
        return None
    t = m.group(1).lower().replace(" ", "")
    if "codebreaker" in t:
        return "codebreaker"
    if "gameshark" in t or t == "gs":
        return "gameshark"
    if "actionreplay" in t or t == "ar":
        return "actionreplay"
    return None


# ------------------------------------------------------------------ build

def build(db_dir, out_path, revision):
    if os.path.exists(out_path):
        os.remove(out_path)
    con = sqlite3.connect(out_path)
    con.executescript("""
        PRAGMA page_size = 4096;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE source (id INTEGER PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL, licence TEXT NOT NULL,
                             revision TEXT);
        CREATE TABLE release (id INTEGER PRIMARY KEY, platform TEXT NOT NULL, name TEXT NOT NULL, title TEXT NOT NULL,
                              region TEXT, revision TEXT, serial TEXT, size INTEGER, crc32 TEXT, md5 TEXT, sha1 TEXT,
                              kind TEXT NOT NULL, description TEXT, source_id INTEGER NOT NULL REFERENCES source(id));
        CREATE INDEX release_sha1 ON release(sha1);
        CREATE INDEX release_crc ON release(platform, crc32);
        CREATE INDEX release_serial ON release(platform, serial);
        CREATE INDEX release_title ON release(platform, title);
        CREATE TABLE cheat_blob (id INTEGER PRIMARY KEY, sha1 TEXT NOT NULL UNIQUE, count INTEGER NOT NULL,
                                 data BLOB NOT NULL);
        CREATE TABLE cheat_set (id INTEGER PRIMARY KEY, platform TEXT NOT NULL, name TEXT NOT NULL, title TEXT NOT NULL,
                                region TEXT, revision TEXT, device TEXT, blob_id INTEGER NOT NULL REFERENCES cheat_blob(id),
                                count INTEGER NOT NULL, source_id INTEGER NOT NULL REFERENCES source(id));
        CREATE INDEX cheat_set_title ON cheat_set(platform, title);
        CREATE INDEX cheat_set_name ON cheat_set(platform, name);
        CREATE TABLE match (release_id INTEGER NOT NULL REFERENCES release(id),
                            set_id INTEGER NOT NULL REFERENCES cheat_set(id), kind TEXT NOT NULL,
                            PRIMARY KEY (release_id, set_id)) WITHOUT ROWID;
        CREATE INDEX match_set ON match(set_id);
    """)
    con.execute("INSERT INTO source VALUES (1, 'libretro-database', 'https://github.com/libretro/libretro-database', "
                "'CC BY-SA 4.0', ?)", (revision,))
    stats = {}
    for plat, folder in PLATFORMS.items():
        s = stats.setdefault(plat, {"releases": 0, "hacks": 0, "homebrew": 0, "sets": 0, "cheats": 0, "exact": 0, "probable": 0})
        # Releases.
        dats = [("retail", os.path.join(db_dir, "metadat", "no-intro", folder + ".dat")),
                ("hack", os.path.join(db_dir, "metadat", "hacks", folder + ".dat")),
                ("homebrew", os.path.join(db_dir, "metadat", "homebrew", folder + ".dat"))]
        for kind, path in dats:
            if not os.path.exists(path):
                continue
            for g in parse_dat(path):
                for r in g["roms"] or [{}]:
                    name = g["name"]
                    con.execute(
                        "INSERT INTO release (platform, name, title, region, revision, serial, size, crc32, md5, sha1, kind, "
                        "description, source_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,1)",
                        (plat, name, norm_title(name), g.get("region") or region_of(name), revision_of(name),
                         (r.get("serial") or g.get("serial") or None),
                         int(r["size"]) if r.get("size") else None,
                         r.get("crc", "").upper() or None, r.get("md5", "").upper() or None, r.get("sha1", "").upper() or None,
                         kind, g.get("description"), ))
                    s["releases" if kind == "retail" else kind + ("s" if kind == "hack" else "")] += 1
        # Cheat sets.
        cdir = os.path.join(db_dir, "cht", folder)
        if os.path.isdir(cdir):
            for fn in sorted(os.listdir(cdir)):
                if not fn.lower().endswith(".cht"):
                    continue
                cheats = cheats_of(parse_cht(os.path.join(cdir, fn)))
                if not cheats:
                    continue
                name = fn[:-4]
                data = json.dumps(cheats, ensure_ascii=False, separators=(",", ":")).encode()
                digest = hashlib.sha1(data).hexdigest()
                row = con.execute("SELECT id FROM cheat_blob WHERE sha1 = ?", (digest,)).fetchone()
                blob_id = row[0] if row else con.execute(
                    "INSERT INTO cheat_blob (sha1, count, data) VALUES (?,?,?)",
                    (digest, len(cheats), zlib.compress(data, 9))).lastrowid
                base = DEVICE_TAGS.sub(" ", name).strip()
                con.execute("INSERT INTO cheat_set (platform, name, title, region, revision, device, blob_id, count, source_id) "
                            "VALUES (?,?,?,?,?,?,?,?,1)",
                            (plat, base, norm_title(base), region_of(base), revision_of(base), device_of(name), blob_id, len(cheats)))
                s["sets"] += 1
                s["cheats"] += len(cheats)
        # Matches: exact by name, probable by title.
        for rid, rname, rtitle in con.execute("SELECT id, name, title FROM release WHERE platform = ?", (plat,)).fetchall():
            exact = {sid for (sid,) in con.execute("SELECT id FROM cheat_set WHERE platform = ? AND name = ?", (plat, rname))}
            for sid in exact:
                con.execute("INSERT OR IGNORE INTO match VALUES (?,?, 'exact')", (rid, sid))
                s["exact"] += 1
            if not rtitle:
                continue
            for (sid,) in con.execute("SELECT id FROM cheat_set WHERE platform = ? AND title = ?", (plat, rtitle)).fetchall():
                if sid not in exact:
                    con.execute("INSERT OR IGNORE INTO match VALUES (?,?, 'probable')", (rid, sid))
                    s["probable"] += 1
    built = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    meta = {"schema_version": str(SCHEMA_VERSION), "db_version": time.strftime("%Y.%m.%d", time.gmtime()),
            "built_at": built, "libretro_database_revision": revision, "stats": json.dumps(stats)}
    con.executemany("INSERT INTO meta VALUES (?,?)", meta.items())
    con.commit()
    con.execute("VACUUM")
    con.close()
    return stats


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    st = build(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "unknown")
    print(json.dumps(st, indent=1))
    print(f"{sys.argv[2]}: {os.path.getsize(sys.argv[2]) / 1e6:.1f} MB")
