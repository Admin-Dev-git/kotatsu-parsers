#!/usr/bin/env python3
"""Remove all manga sources except the 19 allowed ones."""
import os
import re
import shutil

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "kotlin", "org", "koitharu", "kotatsu", "parsers")
SITE = os.path.join(ROOT, "site")
TEST_ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "test")

KEEP_SOURCES = {
    "MANGAGEKO",
    "OMEGASCANS",
    "MANHWA18CC",
    "MANHWA18",
    "MANHWA18COM",
    "TOOMICSEN",
    "TOONGOD",
    "TOONILY",
    "TOONILY_ME",
    "HOTCOMICS",
    "COCOMIC",
    "KISSMANGA",
    "LIKEMANGA",
    "MANHUASCAN",
    "MANHWADEN",
    "MADARADEX",
    "RAVENSCANS",
    "MGREAD",
    "HEYTOON",
}

# Base framework files to always keep (relative to site/)
KEEP_BASE_FILES = {
    "madara/MadaraParser.kt",
    "madtheme/MadthemeParser.kt",
    "hotcomics/HotComicsParser.kt",
    "likemanga/LikeMangaParser.kt",
    "mangareader/MangaReaderParser.kt",
    "heancms/HeanCms.kt",
}

# Entire site subdirectories to keep (will prune sources inside)
KEEP_SITE_DIRS = {
    "en",
    "madara",
    "madtheme",
    "hotcomics",
    "likemanga",
    "mangareader",
    "heancms",
}

ANNOTATION_RE = re.compile(r'@MangaSourceParser\s*\(\s*"([A-Z_][A-Z0-9_]*)"')


def rel_site(path: str) -> str:
    return os.path.relpath(path, SITE)


def should_keep_file(path: str, content: str) -> bool:
    rel = rel_site(path)
    if rel.replace("\\", "/") in KEEP_BASE_FILES:
        return True
    match = ANNOTATION_RE.search(content)
    if match:
        return match.group(1) in KEEP_SOURCES
    # Keep non-annotated files only if under allowed dirs and not orphaned locale dirs
    parts = rel.replace("\\", "/").split("/")
    if parts[0] not in KEEP_SITE_DIRS:
        return False
    return False


def prune_site():
    removed = 0
    for dirpath, _, filenames in os.walk(SITE, topdown=False):
        for name in filenames:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as f:
                content = f.read()
            if not should_keep_file(path, content):
                os.remove(path)
                removed += 1
                print(f"REMOVED {rel_site(path)}")
        # Remove empty dirs
        if dirpath != SITE and not os.listdir(dirpath):
            os.rmdir(dirpath)
            print(f"RMDIR {rel_site(dirpath)}")

    # Remove entire unused top-level site dirs
    for name in os.listdir(SITE):
        full = os.path.join(SITE, name)
        if os.path.isdir(full) and name not in KEEP_SITE_DIRS:
            shutil.rmtree(full)
            print(f"RMTREE site/{name}")

    return removed


def prune_tests():
    if os.path.isdir(TEST_ROOT):
        shutil.rmtree(TEST_ROOT)
        print("Removed all parser tests")


if __name__ == "__main__":
    n = prune_site()
    prune_tests()
    print(f"Done. Removed {n} source files.")
