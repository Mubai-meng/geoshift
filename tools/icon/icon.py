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
BG_TOP, BG_BOTTOM = "#FFE0B5", "#F58F78"
WHITE, CREAM, SLEEVE, SKIN, SKIN_SHADE = "#FFFFFF", "#FFFDF8", "#F3E2D2", "#FFE4D0", "#F6CDB3"
HAIR, HAIR_LIGHT, BROWN, WICKER = "#2E1F23", "#6E4B4B", "#5A3A36", "#B5733F"
SKIRT, LIPS, LEAF, BLUSH, SUN = "#9A3350", "#C8374F", "#5FA36F", "#FF9E8F", "#FFC978"


def circle(cx, cy, r):
    return f"M{cx - r:g},{cy:g} A{r:g},{r:g} 0 1,0 {cx + r:g},{cy:g} A{r:g},{r:g} 0 1,0 {cx - r:g},{cy:g} Z"


def stroke(d, color, width, alpha=1.0):
    return dict(d=d, stroke=color, width=width, alpha=alpha)


def fill(d, color, alpha=1.0):
    return dict(d=d, fill=color, alpha=alpha)


# Low evening sun behind her head, and the road; part of the background layer.
GROUND = [
    fill(circle(52, 47, 27), SUN, 0.62),
    fill("M0,93 Q54,88 108,93 L108,108 L0,108 Z", WHITE, 0.16),
]

# A woman on an evening ride, seen close and in profile: full shoulder-length hair with a fringe,
# lowered eyes, cream blouse and a long wine skirt, flowers in the bike's basket. Drawn back to
# front in the full viewport (the body runs off the bottom edge), then scaled into the safe zone.
RIDER = [
    # wheels (mostly below the frame), frame tube, fork
    stroke(circle(22, 99, 15), WHITE, 3.4),
    stroke(circle(80, 99, 15), WHITE, 3.4),
    stroke("M73.8,76 L56,87", CREAM, 2.6),
    stroke("M72.6,69.5 L74,78 L80,99", CREAM, 2.6),
    # basket with flowers
    stroke("M79.6,66.4 L79,61.6 M83,66.4 L83.4,60 M86.4,66.4 L87.4,62", LEAF, 1.4),
    fill(circle(79, 60.6, 2.4), WHITE),
    fill(circle(83.4, 58.6, 2.8), WHITE),
    fill(circle(87.5, 61, 2.2), WHITE),
    fill(circle(83.4, 58.6, 1.1), "#FFCB6B"),
    fill(circle(79, 60.6, 0.9), "#FFCB6B"),
    fill("M76.2,66.2 L90,66.2 Q91,66.2 90.8,67.2 L89,75.2 Q88.8,76.2 87.8,76.2 L78.4,76.2 Q77.4,76.2 77.2,75.2 L75.4,67.2 Q75.2,66.2 76.2,66.2 Z", WICKER),
    stroke("M76.4,69.4 L89.8,69.4 M77.2,72.6 L89,72.6", "#D9995F", 0.8),
    # hair behind the head, lifted back by the ride
    fill("M61.6,33.6 C60.6,29.8 57.4,27.4 53.4,27.6 C47.6,27.9 43.6,32 43.2,38 C43,42.6 42.4,46 39.6,49 C37.6,51.2 35,52.4 32,52.4 C34.6,54.6 38,55.2 41,54.4 C39.6,56.6 37.4,58 34.6,58.6 C39,60.4 44.6,59.4 48.4,56.4 C51.4,54 53.4,51 54.4,48 L56,36 Z", HAIR),
    # neck, blouse, skirt
    fill("M55,45 L60.8,47.6 C60.2,50 60.4,52.2 61.4,54 L52.6,54.4 C54.2,51.6 55,48.4 55,45 Z", SKIN_SHADE),
    fill("M52.6,52.4 C49,53.4 46,56 44.6,60.5 C43,66 42,72 41.6,78.4 L54.2,79 C55,74.4 57.4,70 60.4,66.6 C62,63.4 62.2,59.4 61,55.6 C60.4,54.4 59.6,53.6 58.8,53.2 C57,54.6 54.6,54.2 52.6,52.4 Z", CREAM),
    fill("M41.7,77.2 L54.3,77.8 C59,79.6 63.6,83.4 65.4,88.4 L65,108 L38,108 C39.4,98 40.6,87 41.7,77.2 Z", SKIRT),
    # handlebar, arm reaching to it, hand
    stroke("M72.6,69.5 Q71.6,66.2 68.4,66.8", WHITE, 2.4),
    stroke("M51.6,58.6 L58.4,69.2 L67.6,67.8", SLEEVE, 5.6),
    fill(circle(69.6, 67.3, 2.1), SKIN),
    # face in profile
    fill("M56,33 L61,33.5 C62.6,34.8 63.3,36.4 63.2,37.6 C62.8,38.2 62.7,38.6 63,39 L65.2,41.3 C65.3,41.9 64.6,42.2 63.7,42.2 C64.3,42.8 64.4,43.2 64.1,43.6 L63.5,43.9 C64,44.3 64.1,44.8 63.7,45.2 C63.2,45.6 63.3,46.2 63.4,46.8 C63.2,47.8 62.2,48.4 60.8,48.3 C58.5,48 56.5,47 55.5,45.5 L54,38 Z", SKIN),
    fill(circle(60.2, 43.2, 1.3), BLUSH, 0.45),
    stroke("M60.2,39.5 Q61.3,40.2 62.3,39.6", HAIR, 0.7),
    stroke("M60,37.8 Q61.4,37.1 62.7,37.7", HAIR, 0.5),
    fill("M62.8,43.3 Q63.7,43 64.2,43.5 Q63.9,43.9 63.5,43.9 Q63.9,44.2 63.8,44.7 Q63.2,44.9 62.8,44.4 Z", LIPS),
    # fringe and the lock beside the cheek, highlight, earring
    fill(circle(60.2, 32.4, 1.4), HAIR),
    fill("M60.4,31.6 C62.2,33 63.3,35 63.3,37.2 C61.2,36.3 59,36.6 57.5,37.8 C56.2,39.8 55.8,42.4 56,45.4 C56,47.8 55.4,49.8 54.2,51 C52.4,48 52,44 52.4,40 C52.8,35.6 56,32 60.4,31.6 Z", HAIR),
    stroke("M46.6,35.4 Q49.6,30.8 55.6,30.6", HAIR_LIGHT, 0.9),
    fill(circle(55.9, 47.2, 0.85), WHITE),
]

# Foreground fits the adaptive-icon safe zone after this transform about the centre.
FG_SCALE, FG_DY = 0.86, -3.0


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


LOGO_SCALE, LOGO_DY = 1.0, 0.0


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


STAT_SCALE, STAT_DY = 1.1, -8.0

if __name__ == "__main__":
    res, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    previews(out)
    if res != "-":
        drawables(res)
