#!/usr/bin/env python3
"""Regenerate assets/cn.ttf so it covers every character the app can render.

The camera firmware's system font has Latin and basic punctuation (including -- · → … ±) but no CJK
glyphs, so the app bundles a subset of DroidSansFallback (Apache-2.0) as assets/cn.ttf. The invariant,
enforced by CnFontTest under tools/test.sh:

    every CJK-range char that can reach the screen (recipe names and tips, strings.xml, layouts, Java
    string literals) is a glyph of assets/cn.ttf

Usage:
  python3 tools/font-subset.py /path/to/DroidSansFallback.ttf

Source font (not committed, 3.4 MB):
  https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r1/data/fonts/DroidSansFallback.ttf
"""
import glob
import json
import os
import re
import sys

from fontTools.ttLib import TTFont
from fontTools import subset

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, 'assets', 'cn.ttf')
LITERAL = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')

def needed_chars():
    need = set()
    def add(text):
        for ch in text:
            if ord(ch) >= 0x20 and ord(ch) != 0xFEFF:
                need.add(ord(ch))
    for r in json.load(open(os.path.join(ROOT, 'res/raw/recipes.json'), encoding='utf-8')):
        add(r['name']); add(r.get('tip', ''))
    for f in ['res/values/strings.xml', 'res/layout/main.xml']:
        add(open(os.path.join(ROOT, f), encoding='utf-8').read())
    for f in glob.glob(os.path.join(ROOT, 'src/com/voxivoid/recipelab/*.java')):
        for m in LITERAL.findall(open(f, encoding='utf-8').read()):
            add(m)
    return need

def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    source = TTFont(sys.argv[1])
    src_chars = set(source.getBestCmap().keys())
    old_chars = set(TTFont(FONT).getBestCmap().keys())
    keep = (needed_chars() | old_chars) & src_chars   # want + already have, limited to what the source font carries

    args = subset.Options()
    args.layout_features = []               # CJK needs no OpenType shaping, and dropping the feature tables
    args.drop_tables += ['GPOS', 'GSUB']    # keeps the structure identical to the on-camera-proven subset
    subsetter = subset.Subsetter(options=args)
    subsetter.populate(text=''.join(chr(c) for c in sorted(keep)))
    out = FONT + '.new'
    subsetter.subset(source)
    source.save(out)

    have = set(TTFont(out).getBestCmap().keys())
    missing = keep - have
    if missing:
        os.remove(out)
        sys.exit('font subset is missing: ' + ''.join(chr(c) for c in sorted(missing)))
    os.replace(out, FONT)
    print(f"assets/cn.ttf: {len(have)} glyphs (+{len(have) - len(old_chars)}), {os.path.getsize(FONT) // 1024} KB")

if __name__ == '__main__':
    main()
