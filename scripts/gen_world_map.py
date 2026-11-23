#!/usr/bin/env python3
"""Builds app/src/main/assets/map/world50.bin from Natural Earth 1:50m admin-0 countries
(public domain, https://www.naturalearthdata.com/). Douglas-Peucker simplification in
degrees, coordinates quantized to 1/100 degree, zigzag varint deltas.

Format (little endian): b"SMAP" u8 version=1, u16 countryCount, then per country:
  2 bytes ISO-3166 alpha-2 (ASCII), i32 labelLon*100, i32 labelLat*100,
  i32 minLon,minLat,maxLon,maxLat (*100, largest polygon = home part),
  varint ringCount, per ring: varint pointCount, then zigzag varint dLon,dLat (*100).
Usage: gen_world_map.py ne_50m_admin_0_countries.geojson out.bin
"""
import json, struct, sys

def dp(pts, tol):
    if len(pts) < 3: return pts
    keep = [False]*len(pts); keep[0] = keep[-1] = True
    stack = [(0, len(pts)-1)]
    while stack:
        a, b = stack.pop()
        ax, ay = pts[a]; bx, by = pts[b]
        dx, dy = bx-ax, by-ay; L = (dx*dx+dy*dy) ** .5
        best, bi = -1, -1
        for i in range(a+1, b):
            px, py = pts[i]
            d = abs(dy*(px-ax)-dx*(py-ay))/L if L else ((px-ax)**2+(py-ay)**2) ** .5
            if d > best: best, bi = d, i
        if best > tol:
            keep[bi] = True; stack += [(a, bi), (bi, b)]
    return [p for p, k in zip(pts, keep) if k]

def varint(n, out):
    while True:
        b = n & 0x7f; n >>= 7
        if n: out.append(b | 0x80)
        else: out.append(b); return

def zz(n): return (n << 1) ^ (n >> 31)

def main(src, dst, tol=0.02):
    D = json.load(open(src))
    out = bytearray(b"SMAP"); out.append(1)
    body = bytearray(); count = 0; total = 0
    for f in D["features"]:
        p = f["properties"]
        iso = p.get("ISO_A2_EH") or p.get("ISO_A2")
        if not iso or iso == "-99" or len(iso) != 2:
            continue
        g = f["geometry"]
        polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
        rings = []; best_area = -1; bbox = None
        for poly in polys:
            for ri, ring in enumerate(poly):
                xs = [c[0] for c in ring]; ys = [c[1] for c in ring]
                span = max(max(xs)-min(xs), max(ys)-min(ys))
                if span < 0.06: continue
                s = dp([(c[0], c[1]) for c in ring], tol if span > 1 else tol/2)
                q = []
                for x, y in s:
                    t = (round(x*100), round(y*100))
                    if not q or q[-1] != t: q.append(t)
                if len(q) < 4: continue
                rings.append(q)
                if ri == 0:
                    area = (max(xs)-min(xs))*(max(ys)-min(ys))
                    if area > best_area:
                        best_area = area
                        bbox = (round(min(xs)*100), round(min(ys)*100), round(max(xs)*100), round(max(ys)*100))
        if not rings: continue
        count += 1
        body += iso.upper().encode("ascii")
        body += struct.pack("<ii", round(p["LABEL_X"]*100), round(p["LABEL_Y"]*100))
        body += struct.pack("<iiii", *bbox)
        varint(len(rings), body)
        for q in rings:
            varint(len(q), body); total += len(q)
            px, py = 0, 0
            for x, y in q:
                varint(zz(x-px), body); varint(zz(y-py), body); px, py = x, y
    out += struct.pack("<H", count) + body
    open(dst, "wb").write(out)
    print(f"{count} countries, {total} points, {len(out)} bytes")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
