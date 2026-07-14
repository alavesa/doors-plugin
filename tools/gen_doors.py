#!/usr/bin/env python3
"""Door models + textures for the doors: namespace.

The visible door is an ItemDisplay wearing one of these models through the
item_model component (assets/doors/items/<name>.json -> models/entity/...),
so no other resource pack in the stack can shadow it. Models are authored
as a 1x1 block panel facing SOUTH; the plugin scales it to the doorway's
width x height and rotates it per facing, so ONE model serves every size.
Repaint the textures freely; regenerate defaults with this script.

Run from the repo root:  python3 tools/gen_doors.py
"""
import json, os, struct, zlib

HERE = os.path.dirname(__file__)
ASSETS = os.path.join(HERE, "..", "resource-pack", "assets", "doors")

def png(path, px):
    h, w = len(px), len(px[0])
    rows = b"".join(b"\x00" + b"".join(bytes(p) for p in line) for line in px)
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    print(path)

def sheet(base, seam=None):
    px = [[tuple(base) + (255,)] * 16 for _ in range(16)]
    for i in range(16):
        px[0][i] = px[15][i] = tuple(c - 14 for c in base) + (255,)
        px[i][0] = px[i][15] = tuple(c - 14 for c in base) + (255,)
    if seam is not None:
        for y in range(16):
            px[y][7] = px[y][8] = tuple(seam) + (255,)
    return px

# sliding door: cool gray steel, center seam, hazard stripe across the base
steel = sheet((116, 122, 128), seam=(74, 80, 86))
for y in (12, 13):
    for x in range(1, 15):
        steel[y][x] = (222, 176, 32, 255) if (x + y) % 4 < 2 else (40, 40, 42, 255)
png(os.path.join(ASSETS, "textures", "block", "door_steel.png"), steel)

# blast door: near-black iron, heavy rivets, wide warning chevrons
blast = sheet((56, 58, 62))
for y in (2, 7, 12):
    for x in (2, 7, 12):
        blast[y][x] = (92, 94, 100, 255)
for y in (13, 14):
    for x in range(1, 15):
        blast[y][x] = (200, 60, 40, 255) if (x + y) % 4 < 2 else (30, 30, 32, 255)
png(os.path.join(ASSETS, "textures", "block", "door_blast.png"), blast)

def panel(texture):
    face = {"texture": "#face"}
    return {
        "textures": {"particle": texture, "face": texture},
        "elements": [{
            "from": [0, 0, 6.5],
            "to": [16, 16, 9.5],
            "faces": {f: face for f in ("north", "south", "east", "west", "up", "down")}
        }],
        "display": {"fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
    }

for name, tex in [("door_sliding", "doors:block/door_steel"),
                  ("door_blast", "doors:block/door_blast")]:
    model_dir = os.path.join(ASSETS, "models", "entity")
    os.makedirs(model_dir, exist_ok=True)
    with open(os.path.join(model_dir, name + ".json"), "w") as f:
        json.dump(panel(tex), f, indent=2)
    items_dir = os.path.join(ASSETS, "items")
    os.makedirs(items_dir, exist_ok=True)
    with open(os.path.join(items_dir, name + ".json"), "w") as f:
        json.dump({"model": {"type": "minecraft:model",
                             "model": "doors:entity/" + name}}, f, indent=2)
    print(f"{name}: item definition + model")
print("doors assets done")
