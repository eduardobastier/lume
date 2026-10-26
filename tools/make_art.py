"""Draws the app icon, the TV banner and the site's images (Pillow). Usage: python3 tools/make_art.py"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app/assets/fonts/InterVariable.ttf")
STOPS = [(0.0, (94, 92, 230)), (1.0, (10, 132, 255))]   # indigo -> blue (same as the Settings icon)


def gradient(w, h, angle=90):
    """Gradient along `angle` (90 = top to bottom)."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    a = math.radians(angle)
    dx, dy = math.cos(a), math.sin(a)
    lo = min(0, w * dx, h * dy, w * dx + h * dy)
    hi = max(0, w * dx, h * dy, w * dx + h * dy)
    for y in range(h):
        for x in range(w):
            t = ((x * dx + y * dy) - lo) / (hi - lo)
            for i in range(len(STOPS) - 1):
                t0, c0 = STOPS[i]
                t1, c1 = STOPS[i + 1]
                if t <= t1:
                    f = (t - t0) / (t1 - t0)
                    px[x, y] = tuple(round(c0[k] + (c1[k] - c0[k]) * f) for k in range(3))
                    break
    return img


def glyph(d, s, white=(255, 255, 255, 255), hole=(0, 0, 0, 0)):
    """The mark, drawn in an s x s box: a rounded TV with a little house (with a door) on the screen."""
    d.rounded_rectangle((s * 0.19, s * 0.23, s * 0.81, s * 0.67), radius=int(s * 0.12), outline=white, width=int(s * 0.055))
    d.rounded_rectangle((s * 0.4, s * 0.76, s * 0.6, s * 0.815), radius=int(s * 0.0275), fill=white)
    cx, top, half, eave, bottom = s * 0.5, s * 0.335, s * 0.115, s * 0.445, s * 0.575
    r = s * 0.025
    d.polygon([(cx - half, eave), (cx, top), (cx + half, eave), (cx + half, bottom - r), (cx - half, bottom - r)], fill=white)
    d.rounded_rectangle((cx - half, eave, cx + half, bottom), radius=int(r), fill=white)
    d.line([(cx - half, eave), (cx, top), (cx + half, eave)], fill=white, width=int(s * 0.03), joint="curve")
    d.rounded_rectangle((cx - s * 0.026, s * 0.49, cx + s * 0.026, bottom + 1), radius=int(s * 0.012), fill=hole)


def mark(size, bg=True):
    """The mark on the gradient squircle (bg) or alone (white)."""
    s = size * 4
    img = gradient(s, s).convert("RGBA") if bg else Image.new("RGBA", (s, s), (0, 0, 0, 0))
    if bg:
        mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.23), fill=255)
        img.putalpha(mask)
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    glyph(ImageDraw.Draw(layer), s)
    if bg:
        shadow = layer.copy().filter(ImageFilter.GaussianBlur(s * 0.02))
        tint = Image.new("RGBA", (s, s), (20, 30, 110, 0))
        tint.putalpha(shadow.getchannel("A").point(lambda v: int(v * 0.25)))
        img.alpha_composite(tint, (0, int(s * 0.012)))
    img.alpha_composite(layer)
    return img.resize((size, size), Image.LANCZOS)


def symbol():
    """The mark as an app symbol (white on transparent, cropped), like assets/symbols: drawn where sparkles was."""
    s = 1200
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    glyph(ImageDraw.Draw(img), s)
    img = img.crop(img.getbbox())
    k = 154 / img.width
    return img.resize((154, round(img.height * k)), Image.LANCZOS)


def font(px, weight=600):
    f = ImageFont.truetype(FONT, px)
    try:
        axes = f.get_variation_axes()
        values = []
        for a in axes:
            name = a["name"] if isinstance(a["name"], str) else a["name"].decode()
            values.append(weight if "eight" in name else 32 if "ptical" in name else a.get("default", 0))
        f.set_variation_by_axes(values)
    except Exception:
        pass
    return f


def banner(w=640, h=360):
    s = 4
    img = gradient(w * s, h * s, angle=20).convert("RGBA")
    m = mark(int(h * 0.5 * s), bg=False)
    d = ImageDraw.Draw(img)
    f = font(int(h * 0.15 * s), 650)
    text = "Lume"
    box = d.textbbox((0, 0), text, font=f)
    gap = -int(m.width * 0.1)
    total = m.width + gap + (box[2] - box[0])
    x = (w * s - total) // 2
    img.alpha_composite(m, (x, int((h * s - m.height) / 2)))
    y = (h * s - (box[3] - box[1])) // 2 - box[1]
    d.text((x + m.width + gap - box[0], y), text, font=f, fill=(255, 255, 255, 255))
    return img.resize((w, h), Image.LANCZOS)


if __name__ == "__main__":
    res = os.path.join(ROOT, "app/res/drawable-nodpi")
    os.makedirs(res, exist_ok=True)
    mark(192).save(os.path.join(res, "icon.png"))
    banner().save(os.path.join(res, "banner.png"))
    symbol().save(os.path.join(ROOT, "app/assets/symbols/lume.png"))
    site = os.path.join(ROOT, "docs/assets")
    os.makedirs(site, exist_ok=True)
    mark(512).save(os.path.join(site, "icon-512.png"))
    mark(180).save(os.path.join(site, "apple-touch-icon.png"))
    mark(64).save(os.path.join(site, "favicon-64.png"))
    print("ok")
