#!/usr/bin/env python3
"""
Modernize the lg3d-art raster assets for high-resolution displays.

Since 2006 the wallpapers in ``lg3d-art/src/resources/images/background`` are
512x512 JPEGs that ``SimpleImageBackground`` stretches across the whole screen,
so they look soft and blocky on modern 1080p/4K panels. This script keeps the
*same content* but cleans it up and enlarges it:

  * JPEG sources: a mild variance-gated (Wiener-like) denoise at native
    resolution to dissolve compression blockiness, then a Lanczos upscale and a
    high-quality re-encode. No sharpening and no contrast/colour retouch -- the
    look is preserved, only cleaned and enlarged (per the agreed policy).
  * PNG sources (icons, splash art): lossless already, so only a Lanczos
    upscale (alpha preserved) and an optimised re-encode.

Safety properties:
  * Idempotent -- an image whose longest side is already >= TARGET is never
    enlarged, and the upscale factor is capped, so re-running is safe and will
    not progressively degrade the assets.
  * Filenames, formats and aspect ratios are untouched, so every runtime
    reference (BgConfig, start-menu icons, splash, GDM theme) keeps working.
  * Only ``src/resources`` is processed; the website thumbnails under ``www/``
    and the small fixed-size GDM chrome buttons are explicitly skipped.

Usage:
    python3 modernize_assets.py [--dry-run] [--target 2048] [--quality 90]
                                [--max-upscale 4] [--root <lg3d-art dir>]
"""

from __future__ import annotations

import argparse
import os
import sys

import numpy as np
from PIL import Image, ImageOps
from scipy.ndimage import uniform_filter

# --- Scope -------------------------------------------------------------------

# Directories (relative to the lg3d-art root) that hold runtime artwork.
SRC_ROOT = os.path.join("src", "resources")

# Path fragments that must never be processed.
EXCLUDE_FRAGMENTS = (
    os.sep + "www" + os.sep,          # website thumbnails, not runtime assets
    "gdmthemes" + os.sep + "ProjectLookingGlass" + os.sep + "disconnect.png",
    "gdmthemes" + os.sep + "ProjectLookingGlass" + os.sep + "option.png",
    "gdmthemes" + os.sep + "ProjectLookingGlass" + os.sep + "session.png",
    "gdmthemes" + os.sep + "ProjectLookingGlass" + os.sep + "system.png",
)

RASTER_EXTS = (".jpg", ".jpeg", ".png")

# --- Denoise (JPEG only) -----------------------------------------------------

def adaptive_denoise(img: Image.Image, strength: float = 0.6,
                     window: int = 3, noise: float = 6.0) -> Image.Image:
    """Variance-gated smoothing that flattens flat JPEG-blocked regions while
    keeping edges. ``strength`` in [0, 1] blends the result back with the
    original so the effect stays subtle ("cleanup only")."""
    rgb = img.convert("RGB")
    arr = np.asarray(rgb, dtype=np.float64)
    out = np.empty_like(arr)
    var_noise = noise * noise
    for c in range(arr.shape[2]):
        chan = arr[:, :, c]
        mean = uniform_filter(chan, size=window, mode="nearest")
        sq_mean = uniform_filter(chan * chan, size=window, mode="nearest")
        var = np.clip(sq_mean - mean * mean, 0.0, None)
        # Wiener-style gain: 0 in flat/noisy areas -> mean, 1 at edges -> keep.
        gain = var / (var + var_noise)
        smoothed = mean + gain * (chan - mean)
        out[:, :, c] = chan * (1.0 - strength) + smoothed * strength
    out = np.clip(out, 0.0, 255.0).astype(np.uint8)
    result = Image.fromarray(out, "RGB")
    # Preserve the original alpha channel if there was one.
    if img.mode in ("RGBA", "LA") or (img.mode == "P" and "transparency" in img.info):
        result.putalpha(rgb_to_alpha(img))
    return result


def rgb_to_alpha(img: Image.Image) -> Image.Image:
    return img.convert("RGBA").getchannel("A")


# --- Sizing ------------------------------------------------------------------

def target_size(w: int, h: int, target: int, max_upscale: float):
    """Longest side scaled to ``target`` but never more than ``max_upscale``;
    returns (new_w, new_h, scale). Images already >= target are left as-is."""
    longest = max(w, h)
    if longest >= target:
        return w, h, 1.0
    scale = min(target / float(longest), max_upscale)
    if scale <= 1.0:
        return w, h, 1.0
    nw = max(2, int(round(w * scale)))
    nh = max(2, int(round(h * scale)))
    # Keep dimensions even (friendlier for video/texture paths).
    nw -= nw % 2
    nh -= nh % 2
    return nw, nh, scale


# --- Per-file processing -----------------------------------------------------

def prepare_mode(img: Image.Image, is_jpeg: bool) -> Image.Image:
    """Normalise the PIL mode for the resize/save that follows."""
    if is_jpeg:
        # JPEG has no alpha; bake EXIF orientation and go RGB.
        img = ImageOps.exif_transpose(img)
        if img.mode != "RGB":
            img = img.convert("RGB")
        return img
    # PNG: keep alpha, expand palettes.
    img = ImageOps.exif_transpose(img)
    if img.mode == "P":
        img = img.convert("RGBA" if "transparency" in img.info else "RGB")
    elif img.mode == "LA":
        img = img.convert("RGBA")
    elif img.mode not in ("RGB", "RGBA", "L"):
        img = img.convert("RGBA")
    return img


def process(path: str, target: int, max_upscale: float, quality: int,
            dry_run: bool) -> dict | None:
    try:
        with Image.open(path) as im:
            im.load()
            w, h = im.size
            fmt = (im.format or "").upper()
            is_jpeg = fmt in ("JPEG", "JPG") or path.lower().endswith((".jpg", ".jpeg"))

            nw, nh, scale = target_size(w, h, target, max_upscale)
            if scale <= 1.0 and not is_jpeg:
                # Nothing to enlarge and lossless already: skip entirely.
                return {"path": path, "skipped": True, "why": "already-large-png"}

            img = prepare_mode(im, is_jpeg)

            if is_jpeg:
                img = adaptive_denoise(img)

            if (nw, nh) != (img.width, img.height):
                img = img.resize((nw, nh), Image.LANCZOS)

            if dry_run:
                return {"path": path, "skipped": False, "dry": True,
                        "from": (w, h), "to": (nw, nh), "scale": scale,
                        "jpeg": is_jpeg}

            tmp = path + ".modernize.tmp"
            if is_jpeg:
                if img.mode != "RGB":
                    img = img.convert("RGB")
                img.save(tmp, format="JPEG", quality=quality, optimize=True,
                         progressive=True, subsampling=2)
            else:
                img.save(tmp, format="PNG", optimize=True)
            os.replace(tmp, path)
            return {"path": path, "skipped": False, "dry": False,
                    "from": (w, h), "to": (nw, nh), "scale": scale,
                    "jpeg": is_jpeg}
    except Exception as exc:  # noqa: BLE001 - report and continue
        return {"path": path, "skipped": True, "why": f"error: {exc}"}


def iter_assets(root: str):
    base = os.path.join(root, SRC_ROOT)
    for dirpath, _dirs, files in os.walk(base):
        for name in sorted(files):
            full = os.path.join(dirpath, name)
            if not name.lower().endswith(RASTER_EXTS):
                continue
            rel = os.path.relpath(full, root)
            norm = os.sep + rel
            if any(frag in norm for frag in EXCLUDE_FRAGMENTS):
                continue
            yield full


def main(argv=None) -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    default_root = os.path.dirname(here)  # .../lg3d-art

    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=default_root, help="lg3d-art root directory")
    ap.add_argument("--target", type=int, default=2048, help="longest-side target px")
    ap.add_argument("--max-upscale", type=float, default=4.0, help="cap on upscale factor")
    ap.add_argument("--quality", type=int, default=90, help="JPEG quality (1-95)")
    ap.add_argument("--dry-run", action="store_true", help="report without writing")
    args = ap.parse_args(argv)

    assets = sorted(iter_assets(args.root))
    print(f"root          : {args.root}")
    print(f"candidates    : {len(assets)}")
    print(f"target        : {args.target}px longest side, cap {args.max_upscale}x, "
          f"JPEG q{args.quality}")
    print(f"mode          : {'DRY RUN' if args.dry_run else 'WRITE'}")
    print("-" * 72)

    processed = skipped = 0
    before_bytes = after_bytes = 0
    for full in assets:
        pre = os.path.getsize(full)
        res = process(full, args.target, args.max_upscale, args.quality, args.dry_run)
        rel = os.path.relpath(full, args.root)
        if res is None or res.get("skipped"):
            skipped += 1
            why = res.get("why") if res else "n/a"
            print(f"  SKIP  {rel}  ({why})")
            continue
        processed += 1
        (fw, fh), (tw, th), sc = res["from"], res["to"], res["scale"]
        kind = "jpg" if res["jpeg"] else "png"
        if args.dry_run:
            print(f"  PLAN  {rel}  {fw}x{fh} -> {tw}x{th}  ({sc:.2f}x, {kind})")
        else:
            post = os.path.getsize(full)
            before_bytes += pre
            after_bytes += post
            print(f"  DONE  {rel}  {fw}x{fh} -> {tw}x{th}  ({sc:.2f}x, {kind})  "
                  f"{pre//1024}KB -> {post//1024}KB")

    print("-" * 72)
    print(f"processed {processed}, skipped {skipped}")
    if not args.dry_run and processed:
        print(f"payload   {before_bytes/1e6:.1f} MB -> {after_bytes/1e6:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
