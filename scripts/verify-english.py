#!/usr/bin/env python3
"""Check the two rules every Hashes source file keeps.

1. English only. No Hangul, CJK, kana, Cyrillic, Greek, Arabic, Hebrew,
   Devanagari, Thai or full-width characters in any text file: UI strings,
   comments, documents, store metadata and scripts alike.
2. The brand string "corejbh" appears nowhere except inside the package
   identifier itself: a qualified `com.corejbh.hashes...` reference in code, its
   slash form `com/corejbh/hashes`, and the JNI symbols derived from it
   (`Java_com_corejbh_hashes_engine_NativeMiner_nativeStart`). UI text, resources,
   store metadata, prompts, user agents and worker names are all in scope.

Usage:
    python3 scripts/verify-english.py [path]

Exit code 0 means the tree is clean, 1 means at least one violation was found.
"""

from __future__ import annotations

import os
import re
import sys

SKIP_DIRS = {
    ".git",
    ".gradle",
    ".kotlin",
    ".idea",
    "build",
    "cxx",
    ".cxx",
    ".externalNativeBuild",
    "out",
    "__pycache__",
    "node_modules",
}

TEXT_SUFFIXES = {
    ".kt",
    ".kts",
    ".java",
    ".c",
    ".h",
    ".cpp",
    ".hpp",
    ".cmake",
    ".xml",
    ".md",
    ".yml",
    ".yaml",
    ".json",
    ".properties",
    ".pro",
    ".txt",
    ".py",
    ".sh",
    ".html",
    ".css",
    ".js",
}

BINARY_SUFFIXES = {
    ".png",
    ".jpg",
    ".jpeg",
    ".webp",
    ".apk",
    ".aab",
    ".jar",
    ".aar",
    ".so",
    ".o",
    ".a",
    ".keystore",
    ".jks",
    ".zip",
    ".ttf",
    ".otf",
}

# Ranges for scripts that must never appear.
FORBIDDEN_RANGES = (
    (0x1100, 0x11FF),   # Hangul Jamo
    (0x2E80, 0x2FDF),   # CJK radicals
    (0x3000, 0x303F),   # CJK symbols and punctuation
    (0x3040, 0x30FF),   # Hiragana / Katakana
    (0x3130, 0x318F),   # Hangul compatibility Jamo
    (0x3400, 0x4DBF),   # CJK extension A
    (0x4E00, 0x9FFF),   # CJK unified ideographs
    (0xA960, 0xA97F),   # Hangul Jamo extended A
    (0xAC00, 0xD7AF),   # Hangul syllables
    (0xD7B0, 0xD7FF),   # Hangul Jamo extended B
    (0xF900, 0xFAFF),   # CJK compatibility ideographs
    (0xFF00, 0xFFEF),   # Half-width and full-width forms
    (0x0400, 0x04FF),   # Cyrillic
    (0x0370, 0x03FF),   # Greek
    (0x0600, 0x06FF),   # Arabic
    (0x0590, 0x05FF),   # Hebrew
    (0x0900, 0x097F),   # Devanagari
    (0x0E00, 0x0E7F),   # Thai
)

# Characters that are allowed even though they sit outside ASCII: the typographic
# characters a document may reasonably use.
ALLOWED_CHARACTERS = {
    "\u00b7",              # middle dot
    "\u2018", "\u2019",    # curly single quotes
    "\u201c", "\u201d",    # curly double quotes
    "\u2013", "\u2014",    # en dash, em dash
    "\u2026",              # ellipsis
    "\u00a0",              # non-breaking space
    "\u20bf",              # bitcoin sign
    "\u2192",              # right arrow
    "\u2713",              # check mark
}

# The brand may appear only as part of the package identifier, in source as
# `com.corejbh.hashes...` and in the JNI symbol names derived from it.
BRAND = re.compile(r"corejbh", re.IGNORECASE)

BRAND_ALLOWED_CONTEXTS = (
    re.compile(r"com\.corejbh\.hashes\b"),
    re.compile(r"com/corejbh/hashes\b"),
    re.compile(r"Java_com_corejbh_hashes_\w*"),
    re.compile(r'namespace\s*=\s*"com\.corejbh\.hashes"'),
)


def is_forbidden(character: str) -> bool:
    if character in ALLOWED_CHARACTERS:
        return False
    code = ord(character)
    for start, end in FORBIDDEN_RANGES:
        if start <= code <= end:
            return True
    return False


def brand_is_allowed(line: str) -> bool:
    remainder = line
    for pattern in BRAND_ALLOWED_CONTEXTS:
        remainder = pattern.sub("", remainder)
    return BRAND.search(remainder) is None


def iter_files(root: str):
    for current_root, dirs, files in os.walk(root):
        dirs[:] = [
            name for name in dirs
            if name not in SKIP_DIRS
            and not os.path.join(current_root, name).endswith("build")
        ]
        for name in files:
            path = os.path.join(current_root, name)
            suffix = os.path.splitext(name)[1].lower()
            if suffix in BINARY_SUFFIXES:
                continue
            if suffix not in TEXT_SUFFIXES:
                continue
            yield path


def main() -> int:
    default_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    root = sys.argv[1] if len(sys.argv) > 1 else default_root
    script = os.path.abspath(__file__)

    violations: list[str] = []

    for path in sorted(iter_files(root)):
        relative = os.path.relpath(path, root)
        # This file spells the forbidden ranges and the brand pattern, so its own
        # brand check is skipped; its English check still runs.
        is_script = os.path.abspath(path) == script
        try:
            with open(path, "r", encoding="utf-8") as handle:
                lines = handle.readlines()
        except (UnicodeDecodeError, OSError):
            continue
        for number, line in enumerate(lines, start=1):
            bad = sorted({character for character in line if is_forbidden(character)})
            if bad:
                violations.append(f"{relative}:{number}: non-English character(s) {''.join(bad)}")
            if not is_script and BRAND.search(line) and not brand_is_allowed(line):
                violations.append(f"{relative}:{number}: brand string outside the package name")

    if violations:
        print("Violations found:")
        for violation in violations:
            print("  " + violation)
        return 1

    print("OK: English only, and the brand appears only inside the package name.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
