#!/usr/bin/env python3
"""Builds Thumper's launcher icon (Android vector drawables) and the same mark as an SVG for the README: a thumper, the Dune sand-pounder, driven into the
ground with sound rippling out across the sand. The ripples are flattened rings (a view across the ground) that fade as they spread.
    python3 tools/logo/build_icon.py"""
import math, os

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "app", "src", "main", "res")
CX, GY = 54, 75                      # the stake's tip on the ground
RINGS = [(10, 3.8, 3.4, 1.0), (18, 6.9, 3.0, .72), (26, 9.9, 2.6, .46)]   # rx, ry, stroke, opacity

def ell(cx, cy, rx, ry):
    k = 0.5522847498
    return (f"M{cx-rx},{cy}C{cx-rx},{cy-ry*k:.2f} {cx-rx*k:.2f},{cy-ry} {cx},{cy-ry}C{cx+rx*k:.2f},{cy-ry} {cx+rx},{cy-ry*k:.2f} {cx+rx},{cy}"
            f"C{cx+rx},{cy+ry*k:.2f} {cx+rx*k:.2f},{cy+ry} {cx},{cy+ry}C{cx-rx*k:.2f},{cy+ry} {cx-rx},{cy+ry*k:.2f} {cx-rx},{cy}Z")

# the thumper: a rounded hammer head, a collar, and a shaft that tapers to a point
HEAD = "M45.5,28.5h17a4.2,4.2 0 0 1 4,4v0a4,4 0 0 1 -4,4h-15a4,4 0 0 1 -4,-4v0a4,4 0 0 1 4,-4z"
COLLAR = "M48.5,38.2h11a1.6,1.6 0 0 1 1.6,1.6v1.4h-14.2v-1.4a1.6,1.6 0 0 1 1.6,-1.6z"
SHAFT = f"M50.2,42.5H57.8L56.8,64.5L54,{GY}L51.2,64.5Z"
GLOW = ell(CX, GY, 9, 3.4)

GRAD_STROKE = ('<aapt:attr name="android:strokeColor"><gradient android:startX="24" android:startY="24" android:endX="84" android:endY="92" android:type="linear">'
               '<item android:offset="0" android:color="#FFD9D0FF"/><item android:offset="0.5" android:color="#FF8F7AFF"/><item android:offset="1" android:color="#FF5B47E3"/></gradient></aapt:attr>')
GRAD_FILL = ('<aapt:attr name="android:fillColor"><gradient android:startX="54" android:startY="29" android:endX="54" android:endY="76" android:type="linear">'
             '<item android:offset="0" android:color="#FFE4DEFF"/><item android:offset="0.55" android:color="#FF9A86FF"/><item android:offset="1" android:color="#FF6A55F0"/></gradient></aapt:attr>')

HEAD_XML = ('<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"{aapt}\n'
            '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n')

def stroke(d, w, a):
    return (f'    <path android:pathData="{d}" android:strokeWidth="{w}" android:strokeAlpha="{a}" android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000">\n'
            f'        {GRAD_STROKE}\n    </path>\n')
def fill(d):
    return f'    <path android:pathData="{d}">\n        {GRAD_FILL}\n    </path>\n'

foreground = HEAD_XML.format(aapt=' xmlns:aapt="http://schemas.android.com/aapt"')
foreground += f'    <path android:pathData="{GLOW}" android:fillColor="#FF8F7AFF" android:fillAlpha="0.28"/>\n'
foreground += "".join(stroke(ell(CX, GY, rx, ry), w, a) for rx, ry, w, a in RINGS)
foreground += fill(SHAFT) + fill(COLLAR) + fill(HEAD) + '</vector>\n'
open(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), "w").write(foreground)

def solid(d): return f'    <path android:pathData="{d}" android:fillColor="#FF000000"/>\n'
def line(d, w): return f'    <path android:pathData="{d}" android:strokeWidth="{w}" android:strokeLineCap="round" android:strokeColor="#FF000000" android:fillColor="#00000000"/>\n'
mono = HEAD_XML.format(aapt="") + "".join(line(ell(CX, GY, rx, ry), w) for rx, ry, w, a in RINGS) + solid(SHAFT) + solid(COLLAR) + solid(HEAD) + '</vector>\n'
open(os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"), "w").write(mono)
print("ok")
