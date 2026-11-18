#!/usr/bin/env python3
"""Generate Geoshift bitmap resources and preview sheet from the checked-in icon artwork."""
from pathlib import Path
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parents[2]
res_nodpi = root / 'app/src/main/res/drawable-nodpi'
res_drawable = root / 'app/src/main/res/drawable'
res_nodpi.mkdir(parents=True, exist_ok=True)
res_drawable.mkdir(parents=True, exist_ok=True)

source = Image.open(Path(__file__).with_name('artwork.png')).convert('RGB')
w, h = source.size

# Center of the pin + portal motif in the 1024x1024 master artwork
cx, cy = int(w * 0.496), int(h * 0.496)


def crop_square(img: Image.Image, center_x: int, center_y: int, span: int, out_size: int) -> Image.Image:
    half = span // 2
    x0 = max(0, min(img.width - span, center_x - half))
    y0 = max(0, min(img.height - span, center_y - half))
    return img.crop((x0, y0, x0 + span, y0 + span)).resize((out_size, out_size), Image.Resampling.LANCZOS)


# 1. Adaptive icon full-bleed layer (432x432 = 108dp @ xxxhdpi).
# Seamless crop from continuous master background — no blur-paste border seam,
# while keeping the central pin + portal rings comfortably inside the 66dp safe zone.
launcher_art = crop_square(source, cx, cy, int(w * 0.88), 432)
launcher_art.save(res_nodpi / 'launcher_art.png', optimize=True)


# 2. In-app logo (512x512 RGBA) used in Splash and SetupScreen.
# Framed tightly around the visible 72dp badge area and masked to a smooth rounded squircle
# with a subtle translucent inner glass rim so it renders cleanly on both light and dark surfaces.
logo_size = 512
ss = 4  # 4x supersampling for crisp anti-aliased rounded corners
logo_rgb = crop_square(source, cx, cy, int(w * 0.65), logo_size)

mask_hi = Image.new('L', (logo_size * ss, logo_size * ss), 0)
corner_radius = int(logo_size * ss * 0.225)
ImageDraw.Draw(mask_hi).rounded_rectangle(
    (0, 0, logo_size * ss - 1, logo_size * ss - 1),
    radius=corner_radius,
    fill=255,
)
mask = mask_hi.resize((logo_size, logo_size), Image.Resampling.LANCZOS)

border_hi = Image.new('L', (logo_size * ss, logo_size * ss), 0)
ImageDraw.Draw(border_hi).rounded_rectangle(
    (ss * 2, ss * 2, logo_size * ss - 1 - ss * 2, logo_size * ss - 1 - ss * 2),
    radius=corner_radius - ss * 2,
    outline=52,
    width=int(ss * 1.5),
)
border = border_hi.resize((logo_size, logo_size), Image.Resampling.LANCZOS)

ic_logo = logo_rgb.convert('RGBA')
rim_overlay = Image.new('RGBA', (logo_size, logo_size), (255, 255, 255, 255))
ic_logo = Image.composite(rim_overlay, ic_logo, border)
ic_logo.putalpha(mask)
ic_logo.save(res_nodpi / 'ic_logo.png', optimize=True)


# 3. Preview sheet (tools/icon/preview.png, gitignored) showing adaptive masks, monochrome, and in-app badges.
def draw_mono_glyph(size: int, bg_col: tuple, fg_col: tuple) -> Image.Image:
    scale = 4
    n = size * scale
    canvas = Image.new('RGBA', (n, n), bg_col)
    d = ImageDraw.Draw(canvas)

    def p(x: float, y: float) -> tuple[float, float]:
        # Map inner 72dp viewport (18..90 of 108dp) to 0..n
        return ((x - 18.0) / 72.0 * n, (y - 18.0) / 72.0 * n)

    # Pin body (head + tapered cone)
    hx0, hy0 = p(36.0, 23.0)
    hx1, hy1 = p(70.0, 57.0)
    d.ellipse((hx0, hy0, hx1, hy1), fill=fg_col)
    d.polygon([p(37.6, 46.5), p(53.0, 66.0), p(68.4, 46.5)], fill=fg_col)
    # Hollow core
    cx0, cy0 = p(45.2, 32.2)
    cx1, cy1 = p(60.8, 47.8)
    d.ellipse((cx0, cy0, cx1, cy1), fill=bg_col)

    # Inner portal ellipse arc
    px0, py0 = p(33.0, 59.5)
    px1, py1 = p(73.0, 72.5)
    d.arc((px0, py0, px1, py1), start=335, end=205, fill=fg_col, width=max(2, int(n * 0.055)))

    # Outer portal ripple arc
    ox0, oy0 = p(29.0, 62.5)
    ox1, oy1 = p(77.0, 76.5)
    d.arc((ox0, oy0, ox1, oy1), start=350, end=190, fill=fg_col, width=max(1, int(n * 0.028)))

    # Left & right trajectory orbital wings + 4-point star spark
    left_wing = []
    for i in range(25):
        t = i / 24.0
        u = 1.0 - t
        bx = u**3 * 31.5 + 3 * u**2 * t * 25.5 + 3 * u * t**2 * 29.0 + t**3 * 39.5
        by = u**3 * 42.0 + 3 * u**2 * t * 47.5 + 3 * u * t**2 * 53.5 + t**3 * 53.8
        left_wing.append(p(bx, by))
    d.line(left_wing, fill=fg_col, width=max(2, int(n * 0.038)), joint='curve')

    right_wing = []
    for i in range(20):
        t = i / 19.0
        u = 1.0 - t
        bx = u**2 * 65.5 + 2 * u * t * 70.0 + t**2 * 73.8
        by = u**2 * 49.8 + 2 * u * t * 46.8 + t**2 * 42.5
        right_wing.append(p(bx, by))
    d.line(right_wing, fill=fg_col, width=max(2, int(n * 0.038)), joint='curve')

    spark = [
        p(75.0, 35.5), p(76.4, 39.1), p(80.0, 40.5), p(76.4, 41.9),
        p(75.0, 45.5), p(73.6, 41.9), p(70.0, 40.5), p(73.6, 39.1),
    ]
    d.polygon(spark, fill=fg_col)

    m_hi = Image.new('L', (n, n), 0)
    ImageDraw.Draw(m_hi).ellipse((0, 0, n - 1, n - 1), fill=255)
    out = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    out.paste(canvas, (0, 0), m_hi)
    return out.resize((size, size), Image.Resampling.LANCZOS)


sheet = Image.new('RGBA', (1480, 540), (22, 24, 35, 255))
visible_crop = crop_square(launcher_art, 216, 216, 288, 320).convert('RGBA')  # inner 72dp of 108dp

for idx, rad in enumerate((160, 88, 64)):
    m_hi = Image.new('L', (320 * 4, 320 * 4), 0)
    ImageDraw.Draw(m_hi).rounded_rectangle((0, 0, 320 * 4 - 1, 320 * 4 - 1), radius=rad * 4, fill=255)
    m = m_hi.resize((320, 320), Image.Resampling.LANCZOS)
    badge = Image.new('RGBA', (320, 320), (0, 0, 0, 0))
    badge.paste(visible_crop, (0, 0), m)
    sheet.paste(badge, (32 + idx * 356, 28), badge)

mono_badge = draw_mono_glyph(320, (220, 226, 255, 255), (32, 38, 122, 255))
sheet.paste(mono_badge, (32 + 3 * 356, 28), mono_badge)

# Bottom row: scaled launcher circles + light/dark in-app logo cards + monochrome small
x_cursor = 32
for sz in (136, 96, 64, 48):
    m_hi = Image.new('L', (sz * 4, sz * 4), 0)
    ImageDraw.Draw(m_hi).ellipse((0, 0, sz * 4 - 1, sz * 4 - 1), fill=255)
    m = m_hi.resize((sz, sz), Image.Resampling.LANCZOS)
    sub = visible_crop.resize((sz, sz), Image.Resampling.LANCZOS)
    b = Image.new('RGBA', (sz, sz), (0, 0, 0, 0))
    b.paste(sub, (0, 0), m)
    sheet.paste(b, (x_cursor, 376), b)
    x_cursor += sz + 24

for idx, bg_col in enumerate(((247, 248, 252, 255), (18, 17, 29, 255))):
    card = Image.new('RGBA', (196, 140), bg_col)
    lg = ic_logo.resize((104, 104), Image.Resampling.LANCZOS)
    card.paste(lg, (46, 18), lg)
    sheet.paste(card, (476 + idx * 220, 376))

mono_small = draw_mono_glyph(72, (220, 226, 255, 255), (32, 38, 122, 255))
sheet.paste(mono_small, (1100, 376), mono_small)
sheet.paste(ic_logo.resize((140, 140), Image.Resampling.LANCZOS), (1280, 376), ic_logo.resize((140, 140), Image.Resampling.LANCZOS))

sheet.convert('RGB').save(Path(__file__).with_name('preview.png'), optimize=True)
