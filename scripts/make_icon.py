"""Generate app icons from the new two-state shield/bolt/ring artwork.

Sources (in the user's Telegram Desktop download folder):
  CONECTD.jpg   - green background  -> connected state
  DISCONECTD.jpg - navy background   -> disconnected / default state

The background colour is removed (made transparent), the artwork is cropped to
a square and written in every size/format the apps and website need:

  * windows/resources/schnellvpn.ico  (Windows exe + tray)
  * windows/resources/schnellvpn.png  (Qt app icon + fallback)
  * windows/resources/schnellvpn_connected.png  (green, connected tray)
  * app/src/main/res/mipmap-*/ic_launcher*.png  (Android, navy version)
  * docs/icon.png                     (website, navy version)
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageChops
import colorsys

ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = Path(r"C:\Users\new-TCH\Downloads\Telegram Desktop")
NAVY_SRC = SRC_DIR / "DISCONECTD.jpg"
GREEN_SRC = SRC_DIR / "CONECTD.jpg"


def transparent_logo(source: Path, bg_is_green: bool) -> Image.Image:
    """Remove the flat background, crop to bounds, pad to a square RGBA image."""
    image = Image.open(source).convert("RGB")
    r, g, b = image.split()

    if bg_is_green:
        # Background is a flat dark green; keep non-green pixels. Compute how
        # "green-dominant" each pixel is and fade those to transparent.
        green_score = ImageChops.subtract(g, ImageChops.lighter(r, b))
        # flat green -> ~50+, white shield -> ~0, yellow bolt -> negative.
        mask = green_score.point(
            lambda v: 0 if v > 22 else (int((22 - v) * 12) if v > 4 else 255)
        )
    else:
        # Background is flat navy (max RGB ~50); keep bright/coloured pixels.
        brightness = ImageChops.lighter(ImageChops.lighter(r, g), b)
        mask = brightness.point(
            lambda v: 0 if v < 62 else (int((v - 62) * 10) if v < 88 else 255)
        )

    rgba = image.convert("RGBA")
    rgba.putalpha(mask)
    # getbbox() on RGBA considers all bands, so the invisible dark background
    # would keep the full canvas. Crop using the alpha mask instead.
    box = mask.getbbox()
    if box:
        rgba = rgba.crop(box)
    size = max(rgba.size)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(rgba, ((size - rgba.width) // 2, (size - rgba.height) // 2))
    return canvas


def recolor_blue_to_green(image: Image.Image) -> Image.Image:
    """Turn the blue shield into green for the connected-state icon.

    Only pixels that are clearly blue-dominant (blue much higher than red)
    are shifted in hue; the white shield face and the yellow bolt are kept.
    """
    pixels = image.load()
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = pixels[x, y]
            # Only the strong outer blue shield (b > 130, b > g + 50).
            if a < 24 or b < 130 or b <= g + 50 or b <= r + 60:
                continue
            h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
            # Map blue hue (~0.60) to a green hue (~0.35), keep saturation/lightness.
            h = 0.35 + (h - 0.60) * 0.35
            nr, ng, nb = colorsys.hls_to_rgb(h, l, s)
            pixels[x, y] = (int(nr * 255), int(ng * 255), int(nb * 255), a)
    return image


def save_all() -> None:
    navy = transparent_logo(NAVY_SRC, bg_is_green=False)
    green = transparent_logo(GREEN_SRC, bg_is_green=True)
    green = recolor_blue_to_green(green)

    win = ROOT / "windows" / "resources"
    win.mkdir(parents=True, exist_ok=True)
    navy.save(win / "schnellvpn.png")
    navy.save(
        win / "schnellvpn.ico",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
    )
    green.save(win / "schnellvpn_connected.png")

    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    res = ROOT / "app" / "src" / "main" / "res"
    for folder, size in densities.items():
        target = res / folder
        target.mkdir(parents=True, exist_ok=True)
        scaled = navy.resize((size, size), Image.LANCZOS)
        scaled.save(target / "ic_launcher.png")
        scaled.save(target / "ic_launcher_round.png")

    docs = ROOT / "docs"
    docs.mkdir(parents=True, exist_ok=True)
    navy.resize((256, 256), Image.LANCZOS).save(docs / "icon.png")
    print("icons written")


if __name__ == "__main__":
    save_all()
