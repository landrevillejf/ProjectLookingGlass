#!/usr/bin/env python3
"""
Build the "Looking Glass logo" wallpaper from the desktop's own brand icon.

The mark is ``lg3d-core/src/resources/images/icon/lg3d-logo.png`` -- the
Looking-Glass mascot that the start menu, the taskbar, the 2D splash and the
About window all reflect -- set on a generated slate-blue glass backdrop.

The icon ships at 64x64 and there is no larger original anywhere in the tree,
so the upscale is the whole problem. Four things keep it honest:

  * the mark is resampled in *premultiplied* space, because the RGB under a
    transparent pixel is black and would otherwise bleed a dark fringe into the
    silhouette;
  * the silhouette is then re-cut with a melt-and-refreeze pass (nearest x4,
    blur, re-threshold) so the 64px staircase becomes a smooth vector-like
    outline, and a median + minimum filter pair evens out the magnified RGB and
    stitches the mascot's one-pixel black outline back into one stroke;
  * a much larger, heavily blurred copy of the mark glows behind the crisp one,
    which carries the icon's shape across the frame without pretending there is
    detail that the source does not have;
  * the hero copy is kept to a size where the interpolation still reads as
    soft-edged artwork rather than as a blown-up thumbnail (see
    ``HERO_HEIGHT_FRAC``).

There is deliberately no mirror image of the mark below it (``--reflection``
adds one anyway): the mascot is drawn standing on its own glass platform, so a
reflected copy stacks a second platform under the first and reads as an
artefact rather than as a floor.

The recipe lives in code -- the same reason ``GenerateAppIcons.java`` exists --
so the wallpaper can be re-cut at any resolution, from any of the icon-pack
variants, without a pixel editor.

Usage:
    python3 make_logo_wallpaper.py [--out <file>] [--width 2560] [--height 1440]
                                   [--quality 92] [--logo <file>]
                                   [--no-ghost] [--reflection] [--no-wordmark]
                                   [--preview <file>]
"""

from __future__ import annotations

import argparse
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from scipy.ndimage import label

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ART_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The brand mark lives in lg3d-core (the desktop requests it from there); this
# tool only reads it off the source tree, it does not depend on the module.
LOGO_SRC = os.path.join("lg3d-core", "src", "resources", "images", "icon",
                        "lg3d-logo.png")
OUT_DEFAULT = os.path.join("src", "resources", "images", "background",
                           "LookingGlass-Logo.jpg")


def _scale(rgb, k):
    """Brighten (k > 1) or darken a brand colour, clipped to 8-bit."""
    return tuple(max(0, min(255, int(round(c * k)))) for c in rgb)


# Palette. SLATE_BLUE / LG_RED are the two brand colours the desktop chrome is
# built around; everything else is a darkened or lightened member of those
# families, so re-tinting the frame means editing two numbers.
SLATE_BLUE = (102, 102, 153)
LG_RED = (211, 43, 48)
TOP = (38, 44, 68)
BOTTOM = (9, 11, 19)
STREAK = (150, 168, 210)
SHELF = (140, 158, 200)
TEXT = (170, 180, 208)
SHADOW = (4, 6, 12)
GLOW = _scale(SLATE_BLUE, 0.95)     # the halo behind the mark
ACCENT = _scale(LG_RED, 0.55)       # the warm rim low on the frame

# Sizing, as fractions of the canvas height.
HERO_HEIGHT_FRAC = 0.34    # the crisp mark: 490px on a 1440p frame, 7.6x the
                           # 64px source -- beyond that the upscale stops
                           # reading as artwork
GHOST_HEIGHT_FRAC = 0.66   # the blurred copy behind it
GHOST_OPACITY = 0.42
REFLECTION_FRAC = 0.34     # how much of the mark the mirror image may span
LOGO_CENTER_Y_FRAC = 0.42  # mark centre, a touch above the frame centre so the
                           # platform sits on the lit shelf and clears the
                           # wordmark

DUST = 12   # px, specks in the icon's alpha smaller than this are dropped

# Diagonal light bands: (offset of the band centre as a fraction of the height,
# band thickness as a fraction of the height, tilt in degrees, brightness).
BANDS = (
    (-0.18, 0.30, -18, 0.30),
    (0.34, 0.10, -18, 0.22),
    (0.66, 0.16, 14, 0.16),
    (1.02, 0.24, 14, 0.12),
)

# Candidate UI fonts for the wordmark; the first one found is used.
WORDMARK_FONTS = (
    "/usr/share/fonts/google-noto/NotoSans-Light.ttf",
    "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
    "C:/Windows/Fonts/segoeui.ttf",
)
WORDMARK = "PROJECT  LOOKING  GLASS"
LETTER_SPACING = 0.34   # em


# --- The mark ----------------------------------------------------------------

def trim_to_content(img: Image.Image) -> Image.Image:
    """Crop an RGBA image down to the box its alpha actually occupies."""
    alpha = np.asarray(img.getchannel("A"), dtype=np.uint8)
    rows = np.where(alpha.max(axis=1) > 8)[0]
    cols = np.where(alpha.max(axis=0) > 8)[0]
    if rows.size == 0 or cols.size == 0:
        raise SystemExit("the mark is empty - check --logo")
    return img.crop((int(cols[0]), int(rows[0]),
                     int(cols[-1]) + 1, int(rows[-1]) + 1))


def clean_alpha(img: Image.Image) -> Image.Image:
    """Drop sub-pixel specks and soften the silhouette of a tiny source icon.

    The 64px icon carries a few stray semi-transparent pixels around its edge;
    magnified 7x they read as dirt, so they are removed before any resampling.
    """
    alpha = np.asarray(img.getchannel("A"), dtype=np.uint8).copy()
    lab, n = label(alpha > 8, structure=np.ones((3, 3), dtype=np.uint8))
    if n > 1:
        sizes = np.bincount(lab.ravel())
        small = [i for i in range(1, n + 1) if sizes[i] < DUST]
        if small:
            alpha[np.isin(lab, small)] = 0
    alpha = Image.fromarray(alpha, "L").filter(ImageFilter.GaussianBlur(0.4))
    out = img.convert("RGB").copy()
    out.putalpha(alpha)
    return out


def upscale_premultiplied(img: Image.Image, height: int) -> Image.Image:
    """Resize to ``height`` px in premultiplied space, then un-premultiply.

    PIL resamples each band independently, so the (black) RGB that hides under
    a transparent pixel bleeds into the edge of the result. Multiplying by
    alpha first makes the interpolation behave like real light.
    """
    scale = height / img.size[1]
    size = (max(2, int(round(img.size[0] * scale))),
            max(2, int(round(img.size[1] * scale))))

    arr = np.asarray(img.convert("RGBA"), dtype=np.float32)
    a = arr[..., 3:4] / 255.0
    prem = np.dstack((arr[..., :3] * a, arr[..., 3]))
    res = Image.fromarray(prem.astype(np.uint8), "RGBA").resize(size, Image.LANCZOS)

    out = np.asarray(res, dtype=np.float32)
    ao = out[..., 3:4] / 255.0
    rgb = np.where(ao > 0.008, out[..., :3] / np.maximum(ao, 1e-4), 0.0)
    merged = np.dstack((np.clip(rgb, 0.0, 255.0), out[..., 3]))
    return Image.fromarray(merged.astype(np.uint8), "RGBA")


def smooth_silhouette(img: Image.Image, frac: float = 0.020,
                      lo: int = 90, hi: int = 165) -> Image.Image:
    """Re-cut the mark's alpha with a melt-and-refreeze pass.

    Upscale by four in *nearest* (so every source pixel becomes a solid block),
    blur the blocks until they overlap into one shape, then re-threshold across
    a band so the edge lands back at half coverage. The result is a smooth
    vector-like outline instead of the staircase the 64px grid leaves behind;
    the band between ``lo`` and ``hi`` is what keeps the edge anti-aliased.

    The mark is trimmed tight, so its silhouette touches every image edge:
    without transparent padding the blur would run out of canvas there and
    re-threshold into a hard straight cut along the bounding box.
    """
    alpha = img.getchannel("A")
    radius = max(4.0, alpha.width * frac)
    pad = int(alpha.width * frac) + 4
    box = Image.new("L", (alpha.width + 2 * pad, alpha.height + 2 * pad), 0)
    box.paste(alpha, (pad, pad))
    big = box.resize((box.width * 4, box.height * 4), Image.NEAREST)
    big = big.filter(ImageFilter.GaussianBlur(radius))
    big = big.point(lambda v: 0 if v < lo else (
        255 if v > hi else int((v - lo) * 255 // (hi - lo))))
    big = big.resize(box.size, Image.LANCZOS).crop(
        (pad, pad, pad + alpha.width, pad + alpha.height))
    out = img.convert("RGB").copy()
    out.putalpha(big)
    return out


def soften_edges(img: Image.Image, blur: float = 0.5, sharpen: float = 1.25):
    """Even out the magnified RGB; the silhouette is already handled.

    Two passes matter here. The median collapses the blocky interpolation steps
    a blur would only smear. The minimum filter then thickens the dark channel,
    which is what turns the mascot's one-pixel black outline -- magnified into a
    dashed chain of half-covered pixels -- back into a continuous stroke.
    """
    rgb = img.convert("RGB").filter(ImageFilter.MedianFilter(3))
    rgb = rgb.filter(ImageFilter.MinFilter(3))
    rgb = rgb.filter(ImageFilter.GaussianBlur(blur))
    rgb = rgb.filter(ImageFilter.UnsharpMask(radius=blur * 2.0, percent=int(
        (sharpen - 1.0) * 200), threshold=1))
    return rgb, img.getchannel("A")


def tinted_blur(img: Image.Image, radius: float, opacity: float,
                tint=(255, 255, 255)) -> Image.Image:
    """A blurred, flat-coloured copy of the mark, for the glow behind the hero.

    The colour is replaced outright: keeping any of the source shading would
    just magnify the fact that the icon is 64px across. The blur is done in a
    padded box for the same reason as :func:`smooth_silhouette`, so a mark that
    touches its own bounding box does not leave a rectangular halo behind.
    """
    alpha = np.asarray(img.getchannel("A"), dtype=np.float32) * opacity
    shape = Image.fromarray(np.clip(alpha, 0, 255).astype(np.uint8), "L")
    pad = int(radius * 3) + 2
    padded = Image.new("L", (img.width + 2 * pad, img.height + 2 * pad), 0)
    padded.paste(shape, (pad, pad))
    flat = Image.new("RGBA", padded.size, tint + (0,))
    flat.putalpha(padded)
    return flat.filter(ImageFilter.GaussianBlur(radius))


def seat_in_scene(img: Image.Image, alpha: Image.Image) -> Image.Image:
    """Cool and slightly darken the mark so it reads as lit by the frame rather
    than as a sticker pasted over it."""
    arr = np.asarray(img.convert("RGB"), dtype=np.float32)
    rgb = np.clip(arr * 0.95 + np.array((6.0, 9.0, 18.0), dtype=np.float32),
                  0.0, 255.0)
    out = Image.fromarray(rgb.astype(np.uint8), "RGB")
    out.putalpha(alpha)
    return out


# --- Backdrop ----------------------------------------------------------------

def paint_backdrop(w: int, h: int) -> Image.Image:
    """Vertical gradient + radial glow + light bands + shelf, as an RGB image."""
    ramp = np.linspace(0.0, 1.0, h, dtype=np.float32)[:, None, None]
    base = (1.0 - ramp) * np.array(TOP, dtype=np.float32) \
        + ramp * np.array(BOTTOM, dtype=np.float32)
    base = np.broadcast_to(base, (h, w, 3)).astype(np.float32)

    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)

    # Glow behind the mark, so it reads as lit from inside the glass.
    d = ((xx - w / 2.0) / (w * 0.40)) ** 2 + ((yy - h * 0.42) / (h * 0.44)) ** 2
    base += np.exp(-d * 1.7, dtype=np.float32)[..., None] \
        * np.array(GLOW, dtype=np.float32) * 0.62

    # Polished-glass bands, added as blurred diagonal light.
    for offset, thick_frac, angle, strength in BANDS:
        base += soft_band(w, h, offset, thick_frac, angle, strength)[..., None] \
            * np.array(STREAK, dtype=np.float32)

    # The shelf the reflection sits on, warmed by a hair of the brand red so
    # the bottom of the frame is not a single cold hue.
    shelf = np.exp(-(((yy - h * 0.815) / (h * 0.045)) ** 2), dtype=np.float32)
    base += shelf[..., None] * np.array(SHELF, dtype=np.float32) * 0.10
    base += shelf[..., None] * np.array(ACCENT, dtype=np.float32) * 0.035

    # Dither: a dark gradient this wide bands badly without it.
    rng = np.random.default_rng(20060529)
    base += rng.normal(0.0, 1.4, (h, w, 1)).astype(np.float32)
    return Image.fromarray(np.clip(base, 0, 255).astype(np.uint8), "RGB")


def soft_band(w: int, h: int, offset_frac: float, thick_frac: float,
              angle_deg: float, strength: float) -> np.ndarray:
    """One blurred diagonal light band, returned as an (h, w) float mask."""
    thick = max(8, int(h * thick_frac))
    band = Image.new("L", (w, h), 0)
    dr = ImageDraw.Draw(band)
    y = int(h * offset_frac)
    dr.rectangle((-w, y - thick // 2, 2 * w, y + thick // 2),
                 fill=int(255 * strength))
    band = band.rotate(angle_deg, resample=Image.BICUBIC, fillcolor=0)
    band = band.filter(ImageFilter.GaussianBlur(thick * 0.55))
    return np.asarray(band, dtype=np.float32) / 255.0


def apply_vignette(img: Image.Image, strength: float = 0.55) -> Image.Image:
    arr = np.asarray(img.convert("RGB"), dtype=np.float32)
    h, w = arr.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    d = np.sqrt(((xx - w / 2.0) / (w * 0.62)) ** 2
                + ((yy - h / 2.0) / (h * 0.62)) ** 2)
    vig = np.clip(1.0 - strength * np.maximum(0.0, d - 0.62) ** 1.7, 0.0, 1.0)
    return Image.fromarray(
        (np.clip(arr * vig[..., None], 0, 255)).astype(np.uint8), "RGB")


# --- Foreground --------------------------------------------------------------

def drop_shadow(canvas: Image.Image, mask: Image.Image, x: int, y: int,
                radius: float, opacity: float) -> None:
    """Paste a blurred dark copy of ``mask`` offset by twice its blur radius.

    The mask is padded first so the blur is not clipped by the (tight) mark
    bounding box.
    """
    pad = int(radius * 3)
    padded = Image.new("L", (mask.size[0] + 2 * pad, mask.size[1] + 2 * pad), 0)
    padded.paste(mask, (pad, pad))
    soft = padded.filter(ImageFilter.GaussianBlur(radius)).point(
        lambda v: int(v * opacity))
    layer = Image.new("RGBA", padded.size, SHADOW + (0,))
    layer.putalpha(soft)
    canvas.alpha_composite(layer,
                           (x - pad + int(radius * 2), y - pad + int(radius * 2)))


def reflection(logo: Image.Image, canvas: Image.Image, x: int, y: int,
               gap: int, fade: float = 0.16, blur: float = 3.5) -> Image.Image | None:
    """The mark's mirror image below ``logo``, faded out over what survives.

    The fade ramp is computed *after* the crop so the image reaches zero
    exactly at its cut edge and no hard line is left behind.

    Off by default -- see the module docstring: the icon already carries its
    own platform, so mirroring it doubles that platform.
    """
    ref = logo.transpose(Image.FLIP_TOP_BOTTOM)
    top = y + logo.size[1] + gap
    span = min(canvas.size[1] - top, int(logo.size[1] * REFLECTION_FRAC))
    if span <= 4:
        return None
    ref = ref.crop((0, 0, ref.size[0], span)).filter(ImageFilter.GaussianBlur(blur))
    alpha = np.asarray(ref.getchannel("A"), dtype=np.float32)
    ramp = np.linspace(1.0, 0.0, span, dtype=np.float32)[:, None] ** 2.6
    ref.putalpha(Image.fromarray(
        np.clip(alpha * ramp * fade * 255.0, 0, 255).astype(np.uint8), "L"))
    canvas.alpha_composite(ref, (x, top))
    return ref


def draw_wordmark(canvas: Image.Image, w: int, h: int, size_px: int,
                  center_y: float) -> None:
    """Letter-spaced uppercase wordmark, centred below the mark."""
    path = next((p for p in WORDMARK_FONTS if os.path.exists(p)), None)
    if path is None:
        print("  note  : no UI font found, wordmark skipped")
        return
    font = ImageFont.truetype(path, size_px)
    spacing = int(round(size_px * LETTER_SPACING))
    layer = Image.new("L", canvas.size, 0)
    dr = ImageDraw.Draw(layer)
    widths = [dr.textlength(ch, font=font) for ch in WORDMARK]
    total = int(sum(widths) + spacing * (len(WORDMARK) - 1))
    x = (w - total) / 2.0
    y = center_y * h - size_px / 2.0
    for ch, cw in zip(WORDMARK, widths):
        dr.text((x, y), ch, font=font, fill=255)
        x += cw + spacing
    tint = Image.new("RGBA", canvas.size, TEXT + (0,))
    tint.putalpha(layer)
    canvas.alpha_composite(tint)


# --- Assembly ----------------------------------------------------------------

def build(width: int, height: int, logo_path: str, with_ghost: bool,
          with_reflection: bool, with_wordmark: bool) -> Image.Image:
    with Image.open(logo_path) as src:
        mark_src = trim_to_content(clean_alpha(src.convert("RGBA")))

    hero_h = max(2, int(round(height * HERO_HEIGHT_FRAC)))
    hero = upscale_premultiplied(mark_src, hero_h)
    rgb, alpha = soften_edges(smooth_silhouette(hero))
    hero = seat_in_scene(rgb, alpha)

    canvas = Image.new("RGBA", (width, height))
    canvas.paste(paint_backdrop(width, height), (0, 0))

    x = (width - hero.size[0]) // 2
    y = int(height * LOGO_CENTER_Y_FRAC - hero.size[1] / 2)

    # The oversized blurred copy behind the mark: it carries the silhouette
    # across the frame while hiding that the source is 64px square.
    if with_ghost:
        ghost = upscale_premultiplied(mark_src, int(height * GHOST_HEIGHT_FRAC))
        ghost = tinted_blur(smooth_silhouette(ghost), radius=height * 0.055,
                            opacity=GHOST_OPACITY, tint=GLOW)
        gx = (width - ghost.size[0]) // 2
        gy = int(height * LOGO_CENTER_Y_FRAC - ghost.size[1] / 2)
        canvas.alpha_composite(ghost, (gx, gy))

    if with_reflection:
        gap = int(height * 0.014)
        if reflection(hero, canvas, x, y, gap) is None:
            print("  note  : no room below the mark, reflection skipped")

    drop_shadow(canvas, hero.getchannel("A"), x, y,
                radius=max(3.0, height * 0.006), opacity=0.55)
    canvas.alpha_composite(hero, (x, y))

    if with_wordmark:
        draw_wordmark(canvas, width, height,
                      size_px=max(14, int(height * 0.040)),
                      center_y=0.905)

    print(f"  mark    : {hero.size}px from a {mark_src.size[0]}px source "
          f"({hero.size[1] / mark_src.size[1]:.1f}x), ghost "
          f"{'on' if with_ghost else 'off'}")
    return apply_vignette(canvas.convert("RGB"))


def save_image(img: Image.Image, path: str, quality: int) -> None:
    """Encode by extension.

    JPEG is the default because that is what the rest of the wallpaper
    collection is, and a dithered 1440p gradient costs 3 MB as a lossless PNG
    against ~350 KB here. 4:4:4 (``subsampling=0``) keeps the mark's thin dark
    edges from bleeding into the chroma downsample.
    """
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    if path.lower().endswith(".png"):
        img.save(path, format="PNG", optimize=True)
    else:
        img.convert("RGB").save(path, format="JPEG", quality=quality, optimize=True,
                                progressive=True, subsampling=0)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=ART_ROOT, help="lg3d-art root directory")
    ap.add_argument("--logo", default=None,
                    help=f"brand mark (default: {os.path.join(REPO_ROOT, LOGO_SRC)})")
    ap.add_argument("--out", default=None, help=f"output file (default: {OUT_DEFAULT})")
    ap.add_argument("--width", type=int, default=2560, help="canvas width px")
    ap.add_argument("--height", type=int, default=1440, help="canvas height px")
    ap.add_argument("--quality", type=int, default=92,
                    help="JPEG quality for a .jpg/.jpeg target (1-95)")
    ap.add_argument("--ghost", action=argparse.BooleanOptionalAction, default=True,
                    help="the oversized blurred copy behind the mark (on)")
    ap.add_argument("--reflection", action=argparse.BooleanOptionalAction,
                    default=False,
                    help="mirror image below the mark (off: the mascot already "
                         "stands on its own glass platform)")
    ap.add_argument("--wordmark", action=argparse.BooleanOptionalAction, default=True,
                    help="the PROJECT LOOKING GLASS caption (on)")
    ap.add_argument("--preview", default=None,
                    help="also write a downscaled preview PNG to this path")
    args = ap.parse_args(argv)

    if args.width < 320 or args.height < 240:
        raise SystemExit("canvas is too small to carry the mark")

    logo_path = args.logo or os.path.join(REPO_ROOT, LOGO_SRC)
    out_path = args.out or os.path.join(args.root, OUT_DEFAULT)
    if not os.path.exists(logo_path):
        raise SystemExit(f"brand mark not found: {logo_path}")

    print(f"logo      : {logo_path}")
    print(f"canvas    : {args.width}x{args.height} "
          f"({args.width / args.height:.3f} aspect)")
    img = build(args.width, args.height, logo_path,
                with_ghost=args.ghost,
                with_reflection=args.reflection,
                with_wordmark=args.wordmark)

    save_image(img, out_path, args.quality)
    print(f"wrote     : {out_path}  {img.size[0]}x{img.size[1]}  "
          f"{os.path.getsize(out_path) // 1024}KB")

    if args.preview:
        pre = img.copy()
        pre.thumbnail((960, 960), Image.LANCZOS)
        pre.save(args.preview, format="PNG", optimize=True)
        print(f"preview   : {args.preview}  {pre.size[0]}x{pre.size[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
