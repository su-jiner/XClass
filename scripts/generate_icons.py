"""Generate XClass launcher icons for Android / iOS / HarmonyOS from favicon.png.

Source: <repo>/favicon.png (square badge artwork on a light background).
Run:    python scripts/generate_icons.py

All sizes are derived from the single source image, so replacing favicon.png and
re-running this script refreshes every platform icon.
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "favicon.png"

ANDROID_FOREGROUND = ROOT / "app/src/main/res/drawable-nodpi/ic_launcher_foreground.png"
ANDROID_MONOCHROME = ROOT / "app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png"
IOS_ICON = ROOT / "ios/XClass/Assets.xcassets/AppIcon.appiconset/icon-1024.png"
HARMONY_ICONS = [
    ROOT / "harmonyos/AppScope/resources/base/media/app_icon.png",
    ROOT / "harmonyos/entry/src/main/resources/base/media/app_icon.png",
    ROOT / "harmonyos/entry/src/ohosTest/resources/base/media/app_icon.png",
]

# Adaptive icons: 108dp canvas, content must stay inside the central 72dp safe zone.
ANDROID_CANVAS = 1080
ANDROID_CONTENT_RATIO = 0.64
# Opaque square icons (iOS / HarmonyOS): keep a small margin so rounded masks
# never clip the badge ring.
SQUARE_CONTENT_RATIO = 0.90


def load_source() -> Image.Image:
    if not SOURCE.is_file():
        raise SystemExit(f"source image not found: {SOURCE}")
    return Image.open(SOURCE).convert("RGBA")


def badge_crop(image: Image.Image) -> Image.Image:
    """Crop to the artwork badge, ignoring the light surrounding background."""
    # The source PNG uses transparency around the badge, so the alpha channel is
    # the reliable source of truth for the artwork bounds.
    bbox = image.split()[3].getbbox()
    if bbox is None:
        return image
    left, top, right, bottom = bbox
    size = max(right - left, bottom - top)
    # Keep a small margin so the outer ring is not clipped by the crop.
    size = int(size * 1.02)
    cx, cy = (left + right) // 2, (top + bottom) // 2
    half = size // 2
    box = (cx - half, cy - half, cx + half, cy + half)
    cropped = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    region = image.crop((max(box[0], 0), max(box[1], 0), min(box[2], image.width), min(box[3], image.height)))
    cropped.paste(region, (max(-box[0], 0), max(-box[1], 0)))
    return cropped


def flatten(image: Image.Image, size: int, content_ratio: float = SQUARE_CONTENT_RATIO) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    side = int(size * content_ratio)
    scaled = image.resize((side, side), Image.LANCZOS)
    offset = (size - side) // 2
    canvas.alpha_composite(scaled, (offset, offset))
    return canvas.convert("RGB")


def android_foreground(badge: Image.Image) -> Image.Image:
    canvas = Image.new("RGBA", (ANDROID_CANVAS, ANDROID_CANVAS), (0, 0, 0, 0))
    side = int(ANDROID_CANVAS * ANDROID_CONTENT_RATIO)
    scaled = badge.resize((side, side), Image.LANCZOS)
    offset = (ANDROID_CANVAS - side) // 2
    canvas.alpha_composite(scaled, (offset, offset))
    return canvas


def android_monochrome(badge: Image.Image) -> Image.Image:
    """Solid silhouette for Android's themed-icon layer (tinted by the system)."""
    side = int(ANDROID_CANVAS * ANDROID_CONTENT_RATIO)
    scaled = badge.resize((side, side), Image.LANCZOS)
    on_white = Image.new("RGBA", (side, side), (255, 255, 255, 255))
    on_white.alpha_composite(scaled)
    luminance = on_white.convert("L")
    mask = luminance.point(lambda value: 255 if value < 170 else 0)
    mask = mask.filter(ImageFilter.GaussianBlur(1.2))
    layer = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    black = Image.new("RGBA", (side, side), (0, 0, 0, 255))
    layer.paste(black, (0, 0), mask)
    canvas = Image.new("RGBA", (ANDROID_CANVAS, ANDROID_CANVAS), (0, 0, 0, 0))
    offset = (ANDROID_CANVAS - side) // 2
    canvas.alpha_composite(layer, (offset, offset))
    return canvas


def main() -> int:
    source = load_source()
    badge = badge_crop(source)
    print(f"source {source.size}, badge crop {badge.size}")

    ANDROID_FOREGROUND.parent.mkdir(parents=True, exist_ok=True)
    android_foreground(badge).save(ANDROID_FOREGROUND)
    print(f"wrote {ANDROID_FOREGROUND.relative_to(ROOT)} ({ANDROID_CANVAS}x{ANDROID_CANVAS})")

    android_monochrome(badge).save(ANDROID_MONOCHROME)
    print(f"wrote {ANDROID_MONOCHROME.relative_to(ROOT)} ({ANDROID_CANVAS}x{ANDROID_CANVAS})")

    IOS_ICON.parent.mkdir(parents=True, exist_ok=True)
    flatten(badge, 1024).save(IOS_ICON, format="PNG")
    print(f"wrote {IOS_ICON.relative_to(ROOT)} (1024x1024, opaque)")

    for target in HARMONY_ICONS:
        target.parent.mkdir(parents=True, exist_ok=True)
        flatten(badge, 1024).save(target, format="PNG")
        print(f"wrote {target.relative_to(ROOT)} (1024x1024, opaque)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
