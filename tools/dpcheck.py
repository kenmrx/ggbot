#!/usr/bin/env python3
"""从 uiautomator dump 里量某个控件的像素高度，换算 dp，判断可点高度是否达标。

用法: dpcheck.py <dump.xml> <density> <文本> [--min dp]
退出码: 0 达标/找到, 1 不足或缺失
"""
import re
import sys
import xml.etree.ElementTree as ET

path, density, text = sys.argv[1], float(sys.argv[2]), sys.argv[3]
want = 44.0
if '--min' in sys.argv:
    want = float(sys.argv[sys.argv.index('--min') + 1])

raw = open(path, encoding='utf-8').read()
best = None
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', raw):
    t = m.group(1)
    if text not in t:
        continue
    h = int(m.group(5)) - int(m.group(3))
    dp = h * 160.0 / density
    if best is None or dp > best[1]:
        best = (t, dp, h)

if best is None:
    print('未找到 "{}"'.format(text))
    sys.exit(1)
print('{:24s} {}px = {:.1f}dp (阈值 {:.0f}dp)'.format(best[0][:24], best[2], best[1], want))
sys.exit(0 if best[1] >= want else 1)