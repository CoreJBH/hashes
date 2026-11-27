#!/usr/bin/env python3
"""Render the launcher icons from the approved artwork.

    python3 scripts/make-icons.py docs/icon/hashes-icon-1.png

The source is one 1024x1024 image: the pixel hash mark on the deep black field.
This script finds the mark, centres it on a transparent canvas at the same
proportion it has in the artwork, and writes

  * the adaptive-icon foreground for API 26+ (one PNG per density),
  * a legacy square icon and a legacy round icon for launchers that still ask
    for one by name.

Everything is drawn from the source pixels only; nothing is invented, and the
green of the mark is not retouched.
"""

from __future__ import annotations

import os
import sys

from PIL import Image

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

FOREGROUND_DP = 108
LEGACY_DP = 48

# How much of the icon's height the mark takes, as in the approved artwork.
MARK_FRACTION = 0.58


def mark_bounds(image: Image.Image) -> tuple[int, int, int, int]:
    """Bounding box of the green mark (the background is near-black)."""
    pixels = image.convert("RGB").load()
    width, height = image.size
    left, top, right, bottom = width, height, 0, 0
    for y in range(height):
        for x in range(width):
            r, g, b = pixels[x, y]
            if g > 90 and g > r + 40 and g > b + 40:
                left = min(left, x)
                right = max(right, x)
                top = min(top, y)
                bottom = max(bottom, y)
    if right <= left or bottom <= top:
        raise SystemExit("no mark found in the source image")
    return left, top, right + 1, bottom + 1


def centred_mark(source: Image.Image) -> Image.Image:
    """The mark cropped tight, with the black field turned into transparency."""
    box = mark_bounds(source)
    mark = source.crop(box).convert("RGBA")
    # The artwork is green on near-black, so its green channel is the mark's own
    # coverage: the core is 255, the glow falls off, the field is almost 0. Using
    # it as alpha drops the field and lets the icon sit on any background; a
    # small floor clears the faint field tint and its rounded edge entirely.
    mark.putalpha(mark.split()[1].point(lambda value: 0 if value < 24 else value))

    side = max(mark.size)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    offset = ((side - mark.width) // 2, (side - mark.height) // 2)
    canvas.paste(mark, offset, mark)
    return canvas


def render(mark: Image.Image, size: int, background: tuple[int, int, int, int] | None) -> Image.Image:
    """The mark at `size`, on a background colour or on transparency."""
    canvas = Image.new("RGBA", (size, size), background or (0, 0, 0, 0))
    target = max(1, int(round(size * MARK_FRACTION)))
    scaled = mark.resize((target, target), Image.LANCZOS)
    # Resampling spreads a little coverage into the transparent field; clearing
    # the faintest of it keeps the edge of the mark crisp at 48dp.
    scaled.putalpha(scaled.split()[3].point(lambda value: 0 if value < 40 else value))
    offset = ((size - target) // 2, (size - target) // 2)
    canvas.alpha_composite(scaled, offset)
    return canvas


def circular_icon(icon: Image.Image) -> Image.Image:
    """A circular crop for launchers that ask for a round icon below API 26."""
    size = icon.size[0]
    mask = Image.new("L", (size, size), 0)
    from PIL import ImageDraw

    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    round_icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    round_icon.paste(icon, (0, 0), mask)
    return round_icon


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2

    source_path = sys.argv[1]
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.join(root, "app", "src", "main", "res")

    with Image.open(source_path) as opened:
        source = opened.convert("RGBA")

    mark = centred_mark(source)
    black = (3, 6, 4, 255)

    for density, scale in DENSITIES.items():
        folder = os.path.join(res, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)

        foreground_size = int(round(FOREGROUND_DP * scale))
        render(mark, foreground_size, None).save(
            os.path.join(folder, "ic_launcher_foreground.png")
        )

        legacy_size = int(round(LEGACY_DP * scale))
        square = render(mark, legacy_size, black)
        square.save(os.path.join(folder, "ic_launcher.png"))
        circular_icon(square).save(os.path.join(folder, "ic_launcher_round.png"))

        print(f"{density}: foreground {foreground_size}px, legacy {legacy_size}px")

    return 0


if __name__ == "__main__":
    sys.exit(main())
