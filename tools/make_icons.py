#!/usr/bin/env python3
"""Build the Hashes launcher and store icons from downloads/hashes-icon-1.png.

The source art is a 1254x1254 card: a near-black field, a rounded-corner frame
and the pixel hash-plus-cursor mark in phosphor green. Android needs the mark
separated from the card, because an adaptive icon is composed by the launcher
from a background colour and a foreground layer that is scaled and masked by
the device.

The script keys the near-black field out of the source into an alpha channel
(the green glow stays, so the mark keeps its phosphor bleed), crops the mark,
and places it inside the 72dp safe zone of a 108dp foreground layer. It also
writes the legacy square and round launcher bitmaps, the Android 13 monochrome
layer, and the 512px store icon.

Usage: python3 tools/make_icons.py
"""

from __future__ import annotations

import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "..", "..", "downloads", "hashes-icon-1.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")
STORE_ICON = os.path.join(ROOT, "fastlane", "metadata", "android", "en-US", "images", "icon.png")

BACKGROUND = (0x03, 0x06, 0x04)

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

# Adaptive icon geometry: 108dp layer, 72dp visible shape, 66dp guaranteed safe
# circle. The mark including its glow is kept inside 60dp so no launcher shape
# clips it.
ADAPTIVE_DP = 108
MARK_DP = 66


def load_mark() -> Image.Image:
    art = Image.open(SOURCE).convert("RGB")
    width, height = art.size
    pixels = art.load()
    rgba = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    out = rgba.load()
    for y in range(height):
        for x in range(width):
            r, g, b = pixels[x, y]
            luminance = max(r, g, b) / 255.0
            # Everything below the card's own tone becomes transparent; the
            # phosphor and its glow keep a proportional alpha.
            alpha = max(0.0, min(1.0, (luminance - 0.055) / 0.45))
            if alpha <= 0.0:
                continue
            out[x, y] = (r, g, b, int(round(alpha * 255)))
    box = rgba.getbbox()
    if box is None:
        raise SystemExit("no mark found in the source image")
    return rgba.crop(box)


def scaled(mark: Image.Image, box_px: int) -> Image.Image:
    ratio = min(box_px / mark.width, box_px / mark.height)
    size = (max(1, int(mark.width * ratio)), max(1, int(mark.height * ratio)))
    return mark.resize(size, Image.LANCZOS)


def adaptive_foreground(mark: Image.Image, layer_px: int) -> Image.Image:
    canvas = Image.new("RGBA", (layer_px, layer_px), (0, 0, 0, 0))
    mark_px = round(layer_px * MARK_DP / ADAPTIVE_DP)
    art = scaled(mark, mark_px)
    canvas.alpha_composite(art, ((layer_px - art.width) // 2, (layer_px - art.height) // 2))
    return canvas


def monochrome(mark: Image.Image, layer_px: int) -> Image.Image:
    layer = adaptive_foreground(mark, layer_px)
    mono = Image.new("RGBA", layer.size, (255, 255, 255, 0))
    mono.putalpha(layer.getchannel("A"))
    return mono


def legacy_square(mark: Image.Image, size: int) -> Image.Image:
    """The published card art, scaled down: the pre-26 launcher bitmap."""
    art = Image.open(SOURCE).convert("RGB").resize((size, size), Image.LANCZOS)
    return art


def legacy_round(mark: Image.Image, size: int) -> Image.Image:
    """A round variant: the mark on the background field, clipped to a circle."""
    canvas = Image.new("RGBA", (size, size), BACKGROUND + (255,))
    art = scaled(mark, int(size * 0.62))
    canvas.alpha_composite(art, ((size - art.width) // 2, (size - art.height) // 2))
    mask = Image.new("L", (size * 4, size * 4), 0)
    from PIL import ImageDraw

    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    canvas.putalpha(mask)
    return canvas


def write(image: Image.Image, path: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, optimize=True)
    print("wrote", os.path.relpath(path, ROOT), image.size)


def main() -> int:
    mark = load_mark()
    print("mark cropped to", mark.size)

    for density, factor in DENSITIES.items():
        layer_px = round(ADAPTIVE_DP * factor)
        folder = os.path.join(RES, "mipmap-" + density)
        write(adaptive_foreground(mark, layer_px), os.path.join(folder, "ic_launcher_foreground.png"))
        write(monochrome(mark, layer_px), os.path.join(folder, "ic_launcher_monochrome.png"))
        legacy_px = round(48 * factor)
        write(legacy_square(mark, legacy_px), os.path.join(folder, "ic_launcher.png"))
        write(legacy_round(mark, legacy_px), os.path.join(folder, "ic_launcher_round.png"))

    write(legacy_square(mark, 512), STORE_ICON)
    return 0


if __name__ == "__main__":
    sys.exit(main())
