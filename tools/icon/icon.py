"""Geoshift launcher icon: one shape list -> Android vector drawables + PNG previews.

Usage: python3 -I icon.py <out_dir_for_xml> <out_dir_for_png>
Paths use absolute M L C Q A Z only, in a 108 x 108 viewport.
"""
import math
import os
import re
import sys

from PIL import Image, ImageDraw

V = 108.0
BG_TOP, BG_BOTTOM = "#FFCF8F", "#F27F62"
WHITE, CREAM, SLEEVE, SKIN, SKIN_FAR = "#FFFFFF", "#FFF7EA", "#FFE6C7", "#FFE3C8", "#F6C9A6"
HAIR, BROWN, WICKER = "#4E2F2B", "#6B3F37", "#B5733F"
SKIRT, BERET, LEAF, BLUSH = "#2F7F7B", "#C9435A", "#5FA36F", "#FF9E8F"


def circle(cx, cy, r):
    return f"M{cx - r:g},{cy:g} A{r:g},{r:g} 0 1,0 {cx + r:g},{cy:g} A{r:g},{r:g} 0 1,0 {cx - r:g},{cy:g} Z"


def stroke(d, color, width, alpha=1.0):
    return dict(d=d, stroke=color, width=width, alpha=alpha)


def fill(d, color, alpha=1.0):
    return dict(d=d, fill=color, alpha=alpha)


# Evening sun and the ground the bike stands on; part of the background layer.
GROUND = [
    fill(circle(70, 38, 25), WHITE, 0.16),
    fill(circle(70, 38, 18), WHITE, 0.18),
    fill("M0,82.6 Q54,76.2 108,82.6 L108,108 L0,108 Z", WHITE, 0.16),
    fill("M0,92 Q54,86 108,92 L108,108 L0,108 Z", WHITE, 0.14),
]

# A girl out for an easy evening ride: long hair, beret and glasses, knit top and long skirt, on a
# step-through city bike with flowers in the basket. Drawn back to front in the full viewport, then
# scaled into the safe zone.
RIDER = [
    # petals drifting behind
    fill(circle(24.5, 41, 1.5), WHITE, 0.75),
    fill(circle(19.5, 50, 1.2), WHITE, 0.55),
    fill(circle(27.5, 53.5, 1.0), WHITE, 0.6),
    # far shin and shoe (pedal up and back)
    stroke("M51.5,57 L49.6,63", SKIN_FAR, 3.6),
    stroke("M47.4,64.6 L52,64.6", BROWN, 3.0),
    # wheels
    stroke(circle(33, 68, 11.5), WHITE, 3.2),
    stroke(circle(75, 68, 11.5), WHITE, 3.2),
    fill(circle(33, 68, 1.8), WHITE),
    fill(circle(75, 68, 1.8), WHITE),
    # frame: chain stay, seat stay, seat tube, swooping step-through tube, head tube and fork
    stroke("M33,68 L52,68 L46.2,50 M33,68 L47.4,53.8", CREAM, 2.9),
    stroke("M52,68 Q61.5,65.5 65.8,47.5", CREAM, 2.9),
    stroke("M65.2,43.4 L66.2,48 L75,68", CREAM, 2.9),
    # saddle, handlebar swept back to the hand
    stroke("M42.4,48.8 L50,48.8", BROWN, 3.6),
    stroke("M65.2,43.4 Q64,40.6 60.6,41.2", WHITE, 2.8),
    # basket with flowers, in front of the handlebar
    stroke("M71.2,38.5 L70.4,33.8 M74.6,38.5 L75.2,32.6 M77.6,38.5 L79.2,34.6", LEAF, 1.5),
    fill(circle(70.3, 33, 2.5), WHITE),
    fill(circle(75.3, 31.4, 2.8), BERET),
    fill(circle(79.6, 33.8, 2.3), WHITE),
    fill(circle(75.3, 31.4, 1.0), "#FFD98A"),
    fill("M67.4,38.2 L81.6,38.2 Q82.6,38.2 82.4,39.2 L80.4,47 Q80.1,48 79,48 L70,48 Q68.9,48 68.6,47 L66.6,39.2 Q66.4,38.2 67.4,38.2 Z", WICKER),
    stroke("M67.6,41.4 L81.4,41.4 M68.4,44.6 L80.6,44.6", "#D9995F", 0.9),
    # long hair down the back, lifted a little by the breeze
    fill("M50.5,16.5 C44.5,17.5 41.5,23.5 42.2,30 C42.6,35 40.4,39.4 35.6,42.6 C42,43.6 47.4,40.6 49.6,35.4 C51,31.4 51.4,26 52.4,22 Z", HAIR),
    # near shin and shoe (pedal down and forward)
    stroke("M57.6,63.5 L56.2,71.4", SKIN, 4.0),
    stroke("M53.8,73.2 L59.4,73.2", BROWN, 3.2),
    # long skirt, fluttering behind the saddle
    fill("M44.2,43.4 L52.4,44.8 C56.6,47.6 60.2,51.8 60.8,56 L60,65 Q54.4,67.8 49.2,66.2 C47.2,62.4 44,60.4 39.6,61.6 C42,55.6 43,49.4 44.2,43.4 Z", SKIRT),
    # knit top, arm reaching to the bar, hand
    stroke("M47.8,44.2 L51.4,31.4", CREAM, 8.6),
    stroke("M51.8,31.8 L56.8,38.6 L60.4,40.6", SLEEVE, 4.2),
    fill(circle(61.2, 41.1, 1.9), SKIN),
    # neck, head
    stroke("M52.4,28.6 L53.3,26", SKIN, 3.4),
    fill(circle(54.6, 21.4, 6.3), SKIN),
    # hair over the crown and beside the face, blush, smile, glasses
    fill("M48.3,22.5 C47.5,16.4 51.6,13.6 55.6,14 C58.4,14.2 60.2,15.8 60.8,17.6 C57.6,16.9 54.2,17.6 52.6,20.2 C52,23 51.6,25.6 50.4,27.4 C49,26.4 48.4,24.6 48.3,22.5 Z", HAIR),
    fill(circle(57.2, 24.5, 1.3), BLUSH, 0.85),
    stroke("M58.7,25.5 Q59.6,26.3 60.4,25.5", BROWN, 0.7),
    stroke(circle(58.4, 21.5, 1.9), BROWN, 0.8),
    stroke("M56.5,21.3 L53,20.9", BROWN, 0.7),
    # beret, tilted back
    fill("M47,17.4 C46.6,12.4 51.8,9.8 56.6,10.5 C60.8,11.1 62.8,13.9 61.8,16.2 C57,14.7 51.6,15.5 47,17.4 Z", BERET),
    stroke("M54.6,10.4 L55,8.9", BERET, 1.3),
]

# Foreground fits the adaptive-icon safe zone (66 dp circle) after this transform about the centre.
FG_SCALE, FG_DY = 0.8, 4.0


# ---- path flattening -------------------------------------------------------------------------

def arc(x0, y0, rx, ry, phi, large, sweep, x1, y1, out):
    if rx == 0 or ry == 0:
        out.append((x1, y1)); return
    p = math.radians(phi)
    dx, dy = (x0 - x1) / 2, (y0 - y1) / 2
    xp = math.cos(p) * dx + math.sin(p) * dy
    yp = -math.sin(p) * dx + math.cos(p) * dy
    lam = xp * xp / (rx * rx) + yp * yp / (ry * ry)
    if lam > 1:
        rx *= math.sqrt(lam); ry *= math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * yp * yp - ry * ry * xp * xp
    den = rx * rx * yp * yp + ry * ry * xp * xp
    co = math.sqrt(max(0.0, num / den)) * (1 if large != sweep else -1)
    cxp, cyp = co * rx * yp / ry, -co * ry * xp / rx
    cx = math.cos(p) * cxp - math.sin(p) * cyp + (x0 + x1) / 2
    cy = math.sin(p) * cxp + math.cos(p) * cyp + (y0 + y1) / 2
    t0 = math.atan2((yp - cyp) / ry, (xp - cxp) / rx)
    t1 = math.atan2((-yp - cyp) / ry, (-xp - cxp) / rx)
    dt = t1 - t0
    if sweep and dt < 0: dt += 2 * math.pi
    if not sweep and dt > 0: dt -= 2 * math.pi
    n = max(8, int(abs(dt) / (math.pi / 48)))
    for i in range(1, n + 1):
        t = t0 + dt * i / n
        ex, ey = rx * math.cos(t), ry * math.sin(t)
        out.append((math.cos(p) * ex - math.sin(p) * ey + cx, math.sin(p) * ex + math.cos(p) * ey + cy))


def flatten(d):
    """-> list of (points, closed)."""
    tokens = re.findall(r"[MLCQAZ]|-?\d*\.?\d+(?:e-?\d+)?", d)
    subs, pts, i, cmd = [], [], 0, None

    def num():
        nonlocal i
        v = float(tokens[i]); i += 1
        return v

    while i < len(tokens):
        if tokens[i] in "MLCQAZ":
            cmd = tokens[i]; i += 1
            if cmd == "Z":
                subs.append((pts, True)); pts = []
                continue
        if cmd == "M":
            if pts: subs.append((pts, False))
            pts = [(num(), num())]; cmd = "L"
        elif cmd == "L":
            pts.append((num(), num()))
        elif cmd == "Q":
            x0, y0 = pts[-1]; x1, y1, x2, y2 = num(), num(), num(), num()
            for k in range(1, 25):
                t = k / 24
                pts.append(((1 - t) ** 2 * x0 + 2 * t * (1 - t) * x1 + t * t * x2, (1 - t) ** 2 * y0 + 2 * t * (1 - t) * y1 + t * t * y2))
        elif cmd == "C":
            x0, y0 = pts[-1]; x1, y1, x2, y2, x3, y3 = (num() for _ in range(6))
            for k in range(1, 33):
                t = k / 32; u = 1 - t
                pts.append((u ** 3 * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t ** 3 * x3,
                            u ** 3 * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t ** 3 * y3))
        elif cmd == "A":
            x0, y0 = pts[-1]; rx, ry, phi = num(), num(), num(); large, sweep = int(num()), int(num()); x1, y1 = num(), num()
            arc(x0, y0, rx, ry, phi, large, sweep, x1, y1, pts)
    if pts: subs.append((pts, False))
    return subs


# ---- rendering -------------------------------------------------------------------------------

def rgb(c):
    return tuple(int(c[k:k + 2], 16) for k in (1, 3, 5))


def draw_shapes(img, shapes, px, scale=1.0, dx=0.0, dy=0.0, mono=None):
    """Composites [shapes] onto RGBA [img]; px = pixels per viewport unit; transform is about the centre."""
    def tr(p):
        return (((p[0] - V / 2) * scale + V / 2 + dx) * px, ((p[1] - V / 2) * scale + V / 2 + dy) * px)

    for s in shapes:
        mask = Image.new("L", img.size, 0)
        g = ImageDraw.Draw(mask)
        for pts, closed in flatten(s["d"]):
            q = [tr(p) for p in pts]
            if "fill" in s:
                if len(q) >= 3: g.polygon(q, fill=255)
            else:
                w = s["width"] * scale * px
                line = q + [q[0]] if closed else q
                g.line(line, fill=255, width=max(1, round(w)), joint="curve")
                for x, y in line:  # round caps and joins
                    g.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=255)
        alpha = s.get("alpha", 1.0)
        if alpha < 1: mask = mask.point(lambda v: int(v * alpha))
        color = mono or rgb(s.get("fill") or s["stroke"])
        img.paste(Image.new("RGBA", img.size, color + (255,)), (0, 0), mask)


def background(px):
    n = int(V * px)
    img = Image.new("RGBA", (n, n))
    top, bottom = rgb(BG_TOP), rgb(BG_BOTTOM)
    g = ImageDraw.Draw(img)
    for y in range(n):
        t = y / (n - 1)
        g.line([(0, y), (n, y)], fill=tuple(round(top[k] + (bottom[k] - top[k]) * t) for k in range(3)) + (255,))
    draw_shapes(img, GROUND, px)
    return img


def masked(img, px, kind):
    """What a launcher shows: the central 72 x 72 of the 108 viewport under a mask."""
    n = img.size[0]
    lo, hi = 18 * px, 90 * px
    mask = Image.new("L", (n, n), 0)
    g = ImageDraw.Draw(mask)
    if kind == "circle": g.ellipse((lo, lo, hi, hi), fill=255)
    else: g.rounded_rectangle((lo, lo, hi, hi), radius=(18 if kind == "squircle" else 9) * px, fill=255)
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out.crop((int(lo), int(lo), int(hi), int(hi)))


def previews(out):
    px = 10
    icon = background(px)
    draw_shapes(icon, RIDER, px, FG_SCALE, 0, FG_DY)
    sheet = Image.new("RGBA", (1500, 560), (24, 26, 34, 255))
    x = 20
    for kind in ("circle", "squircle", "rounded"):
        big = masked(icon, px, kind).resize((360, 360), Image.LANCZOS)
        sheet.paste(big, (x, 20), big); x += 380
    # launcher sizes: 96 px (xhdpi), 60 px, 48 px
    y = 400
    x = 20
    for size in (144, 96, 60, 48):
        small = masked(icon, px, "circle").resize((size, size), Image.LANCZOS)
        sheet.paste(small, (x, y), small); x += size + 24
    # light tile behind, to judge the edge on a light wallpaper
    tile = Image.new("RGBA", (200, 150), (236, 238, 244, 255))
    small = masked(icon, px, "squircle").resize((110, 110), Image.LANCZOS)
    tile.paste(small, (45, 20), small)
    sheet.paste(tile, (x + 20, 400))
    # monochrome (themed icon): tinted glyph on a tinted disc
    mono = Image.new("RGBA", icon.size, (214, 226, 255, 255))
    draw_shapes(mono, RIDER, px, FG_SCALE, 0, FG_DY, mono=(30, 52, 120))
    m = masked(mono, px, "circle").resize((360, 360), Image.LANCZOS)
    sheet.paste(m, (1160, 20), m)
    ms = masked(mono, px, "circle").resize((60, 60), Image.LANCZOS)
    sheet.paste(ms, (1160, 400), ms)
    # in-app logo: rounded square, rider larger
    logo = background(px)
    draw_shapes(logo, RIDER, px, LOGO_SCALE, 0, LOGO_DY)
    n = logo.size[0]
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, n, n), radius=24 * px, fill=255)
    lg = Image.new("RGBA", (n, n), (0, 0, 0, 0)); lg.paste(logo, (0, 0), mask)
    lg = lg.resize((150, 150), Image.LANCZOS)
    sheet.paste(lg, (1300, 400), lg)
    sheet.save(os.path.join(out, "preview.png"))


LOGO_SCALE, LOGO_DY = 1.05, 0.5


# ---- Android vector drawables ----------------------------------------------------------------

HEAD = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"{aapt}\n'
        '    android:width="{size}dp" android:height="{size}dp" android:viewportWidth="108" android:viewportHeight="108">\n')


def argb(color, alpha=1.0):
    return "#%02X%s" % (round(alpha * 255), color[1:]) if alpha < 1 else color


def path_xml(s, mono=None, indent="    "):
    color = mono or (s.get("fill") or s["stroke"])
    a = s.get("alpha", 1.0)
    if "fill" in s:
        return f'{indent}<path android:fillColor="{argb(color, a)}" android:pathData="{s["d"]}"/>\n'
    return (f'{indent}<path android:strokeColor="{argb(color, a)}" android:strokeWidth="{s["width"]:g}" '
            f'android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{s["d"]}"/>\n')


def group(shapes, scale, dy, mono=None):
    out = f'    <group android:pivotX="54" android:pivotY="54" android:scaleX="{scale:g}" android:scaleY="{scale:g}" android:translateY="{dy:g}">\n'
    for s in shapes: out += path_xml(s, mono, "        ")
    return out + "    </group>\n"


GRADIENT = ('    <path android:pathData="{d}">\n'
            '        <aapt:attr name="android:fillColor">\n'
            '            <gradient android:type="linear" android:startX="54" android:startY="0" android:endX="54" android:endY="108">\n'
            f'                <item android:offset="0" android:color="{BG_TOP}"/>\n'
            f'                <item android:offset="1" android:color="{BG_BOTTOM}"/>\n'
            '            </gradient>\n'
            '        </aapt:attr>\n'
            '    </path>\n')
AAPT = ' xmlns:aapt="http://schemas.android.com/aapt"'
NOTE = "<!-- Generated by tools/icon/icon.py; edit the shapes there, not here. -->\n"


def write(path, text):
    with open(path, "w", encoding="utf-8") as f: f.write(NOTE + text)


def drawables(res):
    d = os.path.join(res, "drawable")
    # adaptive background: gradient + ground
    write(os.path.join(d, "ic_launcher_background.xml"),
          HEAD.format(aapt=AAPT, size=108) + GRADIENT.format(d="M0,0 L108,0 L108,108 L0,108 Z") + "".join(path_xml(s) for s in GROUND) + "</vector>\n")
    write(os.path.join(d, "ic_launcher_foreground.xml"), HEAD.format(aapt="", size=108) + group(RIDER, FG_SCALE, FG_DY) + "</vector>\n")
    # themed icons (Android 13+): the system tints this single-colour layer
    write(os.path.join(d, "ic_launcher_monochrome.xml"), HEAD.format(aapt="", size=108) + group(RIDER, FG_SCALE, FG_DY, mono="#000000") + "</vector>\n")
    # in-app logo (splash, setup header): rounded square
    square = "M24,0 L84,0 Q108,0 108,24 L108,84 Q108,108 84,108 L24,108 Q0,108 0,84 L0,24 Q0,0 24,0 Z"
    clip = f'    <clip-path android:pathData="{square}"/>\n'
    write(os.path.join(d, "ic_logo.xml"),
          HEAD.format(aapt=AAPT, size=108) + clip + GRADIENT.format(d=square) + "".join(path_xml(s) for s in GROUND)
          + group(RIDER, LOGO_SCALE, LOGO_DY) + "</vector>\n")
    # status-bar icon for the two foreground services: white glyph filling a 24 dp box
    write(os.path.join(d, "ic_stat_geoshift.xml"),
          HEAD.format(aapt="", size=24) + group([s for s in RIDER if s.get("alpha", 1) == 1], STAT_SCALE, STAT_DY, mono="#FFFFFF") + "</vector>\n")


STAT_SCALE, STAT_DY = 1.42, 12.0

if __name__ == "__main__":
    res, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    previews(out)
    if res != "-":
        drawables(res)
