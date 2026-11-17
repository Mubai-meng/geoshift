#!/usr/bin/env python3
"""Generate Geoshift bitmap resources from the checked-in icon artwork."""
from pathlib import Path
from PIL import Image, ImageFilter

root = Path(__file__).resolve().parents[2]
res = root / 'app/src/main/res/drawable-nodpi'
res.mkdir(parents=True, exist_ok=True)
source = Image.open(Path(__file__).with_name('artwork.png')).convert('RGB')
# Adaptive icons are masked by the launcher: keep artwork inside the central safe zone.
bg = source.resize((432, 432), Image.Resampling.LANCZOS).filter(ImageFilter.GaussianBlur(36))
foreground = source.resize((346, 346), Image.Resampling.LANCZOS)
bg.paste(foreground, (43, 43))
bg.save(res / 'launcher_art.png', optimize=True)
source.resize((512, 512), Image.Resampling.LANCZOS).save(res / 'ic_logo.png', optimize=True)
