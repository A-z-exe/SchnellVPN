"""Generate all app icons from the master PNG (shield + bolt + ring).

Source: the PNG the user provided (white background, shield with orange bolt,
cyan ring). We crop out the white background, make it transparent, then write:

  * windows/resources/schnellvpn.ico   (Windows exe / tray icon)
  * windows/resources/schnellvpn.png   (Linux + docs / fallback)
  * app/src/main/res/mipmap-*/ic_launcher.png        (Android)
  * app/src/main/res/mipmap-*/ic_launcher_round.png  (Android round)
  * docs/icon.png                                    (website OG image)
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path(
    r"C:\Users\new-TCH\Downloads\Telegram Desktop\photo_2026-11-24_01-39-56.jpg"
)


def load_square() -> Image.Image:
    """Crop to the artwork bounds and make the white background transparent."""
    image = Image.open(SOURCE).convert("RGBA")
    # White background -> transparent; colored artwork stays opaque.
    # A pixel is "white" only when ALL channels are near 255, so compute the
    # darkest channel per pixel and fade only very bright pixels.
    r, g, b, _ = image.split()
    # Use the minimum channel so the yellow bolt stays opaque.
    from PIL import ImageChops

    darkest = ImageChops.darker(ImageChops.darker(r, g), b)
    mask = darkest.point(
        lambda v: 0 if v > 248 else (int((248 - v) * 24) if v > 208 else 255)
    )
    image.putalpha(mask)
    box = image.getbbox()
    if box:
        image = image.crop(box)
    # Pad to a square canvas.
    size = max(image.size)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(image, ((size - image.width) // 2, (size - image.height) // 2))
    return canvas


def save_all() -> None:
    square = load_square()
    win = ROOT / "windows" / "resources"
    win.mkdir(parents=True, exist_ok=True)
    square.save(win / "schnellvpn.png")
    square.save(
        win / "schnellvpn.ico",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
    )

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
        scaled = square.resize((size, size), Image.LANCZOS)
        scaled.save(target / "ic_launcher.png")
        scaled.save(target / "ic_launcher_round.png")

    docs = ROOT / "docs"
    docs.mkdir(parents=True, exist_ok=True)
    square.resize((256, 256), Image.LANCZOS).save(docs / "icon.png")
    print("icons written")


if __name__ == "__main__":
    save_all()
