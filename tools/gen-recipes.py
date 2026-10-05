#!/usr/bin/env python3
"""Generate the 自定义 block of Recipes.java from recipes.json.

recipes.json at the repo root is what a human edits (named fields, no positional mistakes). This script
turns it into the static table rows the app ships with -- no runtime import, no IO to fail at boot. The
output is spliced between the gen:custom markers in src/com/voxivoid/recipelab/Recipes.java; everything
the script writes is verified field by field against the JSON by RecipePackTest's parity test, so a
mapping bug here can never reach the camera unnoticed.

Usage: python3 tools/gen-recipes.py
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JSON_PATH = os.path.join(ROOT, 'recipes.json')
TABLE = os.path.join(ROOT, 'src/com/voxivoid/recipelab/Recipes.java')
BEGIN = '        // ==== gen:custom begin'
END = '        // ==== gen:custom end ===='

STYLES = { 'STANDARD': 'STD', 'VIVID': 'VIVID', 'NEUTRAL': 'NEUTRAL', 'PORTRAIT': 'PORTRAIT',
           'LANDSCAPE': 'LANDSCAPE', 'MONO': 'MONO', 'CLEAR': 'CLEAR', 'DEEP': 'DEEP', 'LIGHT': 'LIGHT',
           'SUNSET': 'SUNSET', 'NIGHT': 'NIGHT', 'RED-LEAVES': 'AUTUMN', 'SEPIA': 'SEPIA' }
FLASH = { 'ON': 'FLASH_ON', 'OFF': 'FLASH_OFF', 'SOFT_ONLY': 'FLASH_SOFT' }

def ev_steps(token):
    token = str(token)   # an integer ev (0, 1) arrives as int; a third (0.3) as its text
    neg = token.startswith('-')
    d = token[1:] if neg else token
    ip, _, fp = d.partition('.')
    steps = int(ip) * 3
    if fp:
        assert len(fp) == 1 and fp in '037', 'ev %s 不是 1/3 EV 步进' % token
        steps += {'0': 0, '3': 1, '7': 2}[fp]
    return -steps if neg else steps

def java_str(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'

def row(r):
    style = STYLES[r['style']]
    dro = {'DRO_OFF': '0', 'DRO_AUTO': 'DRO_AUTO'}.get(r['dro'], r['dro'][4:])   # DRO_1..5 -> 1..5
    wb_mode, kelvin = '0', '0'
    if r.get('wbMode') == 'AUTO': wb_mode = 'AUTO'
    if r.get('wbMode') == 'KELVIN': wb_mode, kelvin = 'K', str(r['kelvin'])
    flash = FLASH[r['flashSuggest']]
    # ctor: group, name, style, sat, con, sharp, matrix, wbMode, kelvin, ab, gm, pe, ev, dro, sub, flash, tip
    return ('new Recipe(CUSTOM, %-40s %s, %2d, %2d, %2d, 0, %4s, %4s, %2d, %2d, 0, %2d, %5s, 0, %-9s %s),' % (
        java_str(r['name']) + ',', style,
        r['saturation'], r['contrast'], r['sharpness'], wb_mode, kelvin,
        r['wbAmber'], r['wbGreen'], ev_steps(r['ev']), dro, flash + ',', java_str(r.get('tip', ''))))

def main():
    with open(JSON_PATH, encoding='utf-8') as f:
        pack = json.load(f, parse_float=str)   # decimals stay strings: 0.3 never rounds
    lines = [BEGIN + '（tools/gen-recipes.py 从 recipes.json 生成——改 JSON 后重跑脚本，别手改这里）====']
    lines += ['        ' + row(r) for r in pack]
    lines.append(END)

    src = open(TABLE, encoding='utf-8').read()
    at = src.index(BEGIN)
    end = src.index(END)
    if end < at: sys.exit('markers out of order')
    open(TABLE, 'w', encoding='utf-8').write(src[:at] + '\n'.join(lines) + src[end + len(END):])
    print('生成了 %d 行 -> Recipes.java 的自定义区块' % len(pack))

if __name__ == '__main__':
    main()
