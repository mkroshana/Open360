"""Export SVG masters and RGBA PNGs directly from Open360 Android VectorDrawables."""

from __future__ import annotations

import base64
import html
import io
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from PIL import Image

ANDROID_NS = "http://schemas.android.com/apk/res/android"
AAPT_NS = "http://schemas.android.com/aapt"

ROOT = Path(__file__).resolve().parent.parent
DRAWABLES_DIR = ROOT / "core" / "src" / "main" / "res" / "drawable"
OUT_SVG_DIR = ROOT / "assets" / "icons" / "svg"
OUT_PNG_DIR = ROOT / "assets" / "icons" / "png"
OUT_MIPMAP_DIR = OUT_PNG_DIR / "android-mipmap"

BROWSER_CANDIDATES = [
    Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe"),
    Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
    Path(r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"),
]


def _android_attr(elem: ET.Element, name: str) -> Optional[str]:
    return elem.attrib.get(f"{{{ANDROID_NS}}}{name}")


def _parse_color(raw: Optional[str], recolor_map: Optional[Dict[str, str]] = None) -> Tuple[str, Optional[float]]:
    if not raw:
        return ("none", None)
    val = raw.strip().upper()
    if recolor_map and val in recolor_map:
        val = recolor_map[val].strip().upper()
    if len(val) == 9 and val.startswith("#"):
        alpha = int(val[1:3], 16) / 255.0
        rgb = f"#{val[3:]}"
        if alpha == 0.0:
            return ("none", None)
        if alpha == 1.0:
            return (rgb, None)
        return (rgb, round(alpha, 4))
    if len(val) == 7 and val.startswith("#"):
        return (val, None)
    return (val, None)


def _extract_vector_parts(
    xml_path: Path,
    grad_prefix: str,
    recolor_map: Optional[Dict[str, str]] = None,
) -> Tuple[Tuple[float, float], List[str], List[str]]:
    """Parse an Android VectorDrawable into (viewport_size, defs_elements, body_elements)."""
    tree = ET.parse(xml_path)
    root = tree.getroot()
    vw = float(_android_attr(root, "viewportWidth") or "108")
    vh = float(_android_attr(root, "viewportHeight") or "108")

    defs: List[str] = []
    elements: List[str] = []
    grad_counter = 0

    for child in root:
        if not child.tag.endswith("path"):
            continue
        d = _android_attr(child, "pathData")
        if not d:
            continue

        fill_val: Optional[str] = None
        fill_opacity: Optional[float] = None

        # Check child <aapt:attr name="android:fillColor"><gradient .../></aapt:attr> first.
        for sub in child:
            if sub.tag == f"{{{AAPT_NS}}}attr" and sub.attrib.get("name") in ("android:fillColor", "fillColor"):
                grad = next((g for g in sub if g.tag.endswith("gradient")), None)
                if grad is not None:
                    grad_id = f"{grad_prefix}_grad_{grad_counter}"
                    grad_counter += 1
                    gtype = _android_attr(grad, "type") or "linear"
                    stops: List[str] = []
                    for item in grad:
                        if not item.tag.endswith("item"):
                            continue
                        offset = _android_attr(item, "offset") or "0"
                        stop_color, stop_opacity = _parse_color(_android_attr(item, "color"), recolor_map)
                        pct = f"{float(offset) * 100:g}%"
                        stop_attrs = [f'offset="{pct}"', f'stop-color="{stop_color}"']
                        if stop_opacity is not None:
                            stop_attrs.append(f'stop-opacity="{stop_opacity}"')
                        stops.append(f'      <stop {" ".join(stop_attrs)} />')

                    if gtype == "radial":
                        cx = _android_attr(grad, "centerX") or "0"
                        cy = _android_attr(grad, "centerY") or "0"
                        r = _android_attr(grad, "gradientRadius") or "0"
                        defs.append(
                            f'    <radialGradient id="{grad_id}" cx="{cx}" cy="{cy}" r="{r}" gradientUnits="userSpaceOnUse">\n'
                            + "\n".join(stops)
                            + "\n    </radialGradient>"
                        )
                    else:
                        x1 = _android_attr(grad, "startX") or "0"
                        y1 = _android_attr(grad, "startY") or "0"
                        x2 = _android_attr(grad, "endX") or "0"
                        y2 = _android_attr(grad, "endY") or "0"
                        defs.append(
                            f'    <linearGradient id="{grad_id}" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" gradientUnits="userSpaceOnUse">\n'
                            + "\n".join(stops)
                            + "\n    </linearGradient>"
                        )
                    fill_val = f"url(#{grad_id})"
                    break

        if fill_val is None:
            fill_val, fill_opacity = _parse_color(_android_attr(child, "fillColor"), recolor_map)

        attrs = [f'd="{d}"', f'fill="{fill_val}"']
        if fill_opacity is not None:
            attrs.append(f'fill-opacity="{fill_opacity}"')

        stroke_raw = _android_attr(child, "strokeColor")
        if stroke_raw:
            stroke_color, stroke_opacity = _parse_color(stroke_raw, recolor_map)
            if stroke_color != "none":
                attrs.append(f'stroke="{stroke_color}"')
                if stroke_opacity is not None:
                    attrs.append(f'stroke-opacity="{stroke_opacity}"')
                stroke_width = _android_attr(child, "strokeWidth")
                if stroke_width:
                    attrs.append(f'stroke-width="{stroke_width}"')
                linecap = _android_attr(child, "strokeLineCap")
                if linecap:
                    attrs.append(f'stroke-linecap="{linecap}"')
                linejoin = _android_attr(child, "strokeLineJoin")
                if linejoin:
                    attrs.append(f'stroke-linejoin="{linejoin}"')

        elements.append(f'  <path {" ".join(attrs)} />')

    return (vw, vh), defs, elements


def _build_svg(
    width: int,
    height: int,
    view_box: str,
    layers: List[Tuple[Path, str, Optional[Dict[str, str]]]],
    clip_shape: Optional[Tuple[str, str]] = None,
) -> str:
    defs: List[str] = []
    body: List[str] = []

    for xml_path, prefix, recolor_map in layers:
        _, layer_defs, layer_elems = _extract_vector_parts(xml_path, prefix, recolor_map)
        defs.extend(layer_defs)
        body.extend(layer_elems)

    if clip_shape is not None:
        clip_id, clip_geom = clip_shape
        defs.append(f'    <clipPath id="{clip_id}">\n      {clip_geom}\n    </clipPath>')
        indented_body = "\n".join(f"  {line}" for line in body)
        body_block = f'  <g clip-path="url(#{clip_id})">\n{indented_body}\n  </g>'
    else:
        body_block = "\n".join(body)

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="{view_box}">'
    ]
    if defs:
        parts.append("  <defs>")
        parts.extend(defs)
        parts.append("  </defs>")
    parts.append(body_block)
    parts.append("</svg>")
    return "\n".join(parts) + "\n"


def _rasterize_svgs_with_browser(jobs: List[Tuple[str, str, int, int]]) -> Dict[str, Image.Image]:
    """Rasterize (name, svg_text, width, height) jobs in one headless Chromium pass."""
    browsers = [p for p in BROWSER_CANDIDATES if p.exists()]
    if not browsers:
        raise RuntimeError("No Chromium-based browser (chrome.exe / msedge.exe) found")

    entries_html = []
    for name, svg_text, w, h in jobs:
        b64_svg = base64.b64encode(svg_text.encode("utf-8")).decode("ascii")
        entries_html.append(
            f'<div class="job" data-name="{html.escape(name)}" data-w="{w}" data-h="{h}" '
            f'data-src="data:image/svg+xml;base64,{b64_svg}"></div>'
        )

    harness_html = f"""<!doctype html>
<html>
<head><meta charset="utf-8"></head>
<body>
{''.join(entries_html)}
<div id="results"></div>
<script>
(async () => {{
  const jobs = Array.from(document.querySelectorAll('.job'));
  const results = document.getElementById('results');
  await Promise.all(jobs.map(job => new Promise((resolve, reject) => {{
    const img = new Image();
    img.onload = () => {{
      const w = parseInt(job.dataset.w, 10);
      const h = parseInt(job.dataset.h, 10);
      const canvas = document.createElement('canvas');
      canvas.width = w;
      canvas.height = h;
      const ctx = canvas.getContext('2d');
      ctx.clearRect(0, 0, w, h);
      ctx.drawImage(img, 0, 0, w, h);
      const pre = document.createElement('pre');
      pre.setAttribute('data-name', job.dataset.name);
      pre.textContent = canvas.toDataURL('image/png');
      results.appendChild(pre);
      resolve();
    }};
    img.onerror = () => reject(new Error('Failed loading SVG ' + job.dataset.name));
    img.src = job.dataset.src;
  }})));
  document.body.dataset.done = '1';
}})();
</script>
</body>
</html>
"""

    last_err = ""
    for browser in browsers:
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            harness_path = tmp_path / "harness.html"
            profile_dir = tmp_path / "profile"
            harness_path.write_text(harness_html, encoding="utf-8")

            cmd = [
                str(browser),
                "--headless=new",
                "--disable-gpu",
                "--no-first-run",
                "--no-default-browser-check",
                f"--user-data-dir={profile_dir}",
                "--virtual-time-budget=5000",
                "--dump-dom",
                harness_path.resolve().as_uri(),
            ]
            proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60)
            dom = proc.stdout
            if 'data-done="1"' not in dom:
                last_err = f"{browser.name} exited {proc.returncode}; stderr={proc.stderr[:400]}"
                continue

            pattern = re.compile(r'<pre[^>]*data-name="([^"]+)"[^>]*>(data:image/png;base64,[^<]+)</pre>')
            images: Dict[str, Image.Image] = {}
            for name, data_url in pattern.findall(dom):
                b64_png = data_url.split(",", 1)[1]
                raw_png = base64.b64decode(b64_png)
                img = Image.open(io.BytesIO(raw_png)).convert("RGBA")
                images[name] = img

            if len(images) == len(jobs):
                return images
            last_err = f"{browser.name} rendered {len(images)}/{len(jobs)} jobs"

    raise RuntimeError(f"Headless SVG rasterization failed: {last_err}")


def _save_png(img: Image.Image, out_path: Path, size: Optional[Tuple[int, int]] = None) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    target = img
    if size is not None and img.size != size:
        target = img.resize(size, Image.Resampling.LANCZOS)
    target.convert("RGBA").save(out_path, format="PNG")


def _verify_png(path: Path, expected_size: Tuple[int, int], kind: str) -> None:
    with Image.open(path) as img:
        assert img.mode == "RGBA", f"{path}: expected RGBA, got {img.mode}"
        assert img.size == expected_size, f"{path}: expected {expected_size}, got {img.size}"

        corner = img.getpixel((0, 0))
        center = img.getpixel((img.width // 2, img.height // 2))

        if kind in ("square", "playstore", "background"):
            assert corner[3] == 255, f"{path}: expected opaque corner alpha=255, got {corner}"
        else:
            assert corner[3] == 0, f"{path}: expected transparent corner alpha=0, got {corner}"

        if kind in ("square", "playstore", "rounded", "circle", "foreground", "mipmap"):
            assert center[3] == 255 and center[0] > 180, f"{path}: expected red shutter center, got {center}"
        elif kind == "background":
            assert center[3] == 255 and max(center[:3]) < 60, f"{path}: expected obsidian center, got {center}"
        else:
            assert center[3] == 255, f"{path}: expected opaque center mark, got {center}"


def main() -> None:
    bg_xml = DRAWABLES_DIR / "ic_launcher_background.xml"
    fg_xml = DRAWABLES_DIR / "ic_launcher_foreground.xml"
    mono_xml = DRAWABLES_DIR / "ic_launcher_monochrome.xml"
    mark_xml = DRAWABLES_DIR / "ic_open360_mark.xml"

    svg_specs: Dict[str, Tuple[str, int, int]] = {
        "open360_icon_square": (
            _build_svg(1024, 1024, "18 18 72 72", [(bg_xml, "bg", None), (fg_xml, "fg", None)]),
            1024,
            1024,
        ),
        "open360_icon_rounded": (
            _build_svg(
                1024,
                1024,
                "18 18 72 72",
                [(bg_xml, "bg", None), (fg_xml, "fg", None)],
                clip_shape=("clip_rounded", '<rect x="18" y="18" width="72" height="72" rx="16" ry="16" />'),
            ),
            1024,
            1024,
        ),
        "open360_icon_circle": (
            _build_svg(
                1024,
                1024,
                "18 18 72 72",
                [(bg_xml, "bg", None), (fg_xml, "fg", None)],
                clip_shape=("clip_circle", '<circle cx="54" cy="54" r="36" />'),
            ),
            1024,
            1024,
        ),
        "open360_icon_adaptive_full": (
            _build_svg(1024, 1024, "0 0 108 108", [(bg_xml, "bg", None), (fg_xml, "fg", None)]),
            1024,
            1024,
        ),
        "open360_icon_background": (
            _build_svg(864, 864, "0 0 108 108", [(bg_xml, "bg", None)]),
            864,
            864,
        ),
        "open360_icon_foreground": (
            _build_svg(864, 864, "0 0 108 108", [(fg_xml, "fg", None)]),
            864,
            864,
        ),
        "open360_icon_monochrome": (
            _build_svg(864, 864, "0 0 108 108", [(mono_xml, "mono", None)]),
            864,
            864,
        ),
        "open360_icon_monochrome_cropped": (
            _build_svg(1024, 1024, "18 18 72 72", [(mono_xml, "mono", None)]),
            1024,
            1024,
        ),
        "open360_mark_white": (
            _build_svg(512, 512, "0 0 24 24", [(mark_xml, "mark", None)]),
            512,
            512,
        ),
        "open360_mark_dark": (
            _build_svg(512, 512, "0 0 24 24", [(mark_xml, "mark", {"#FFFFFFFF": "#FF1C1B19"})]),
            512,
            512,
        ),
    }

    OUT_SVG_DIR.mkdir(parents=True, exist_ok=True)
    raster_jobs: List[Tuple[str, str, int, int]] = []
    for name, (svg_text, w, h) in svg_specs.items():
        (OUT_SVG_DIR / f"{name}.svg").write_text(svg_text, encoding="utf-8")
        raster_jobs.append((name, svg_text, w, h))

    masters = _rasterize_svgs_with_browser(raster_jobs)

    png_outputs: List[Tuple[Path, Image.Image, Optional[Tuple[int, int]], str]] = [
        (OUT_PNG_DIR / "open360_icon_square_1024.png", masters["open360_icon_square"], (1024, 1024), "square"),
        (OUT_PNG_DIR / "open360_icon_playstore_512.png", masters["open360_icon_square"], (512, 512), "playstore"),
        (OUT_PNG_DIR / "open360_icon_rounded_1024.png", masters["open360_icon_rounded"], (1024, 1024), "rounded"),
        (OUT_PNG_DIR / "open360_icon_rounded_512.png", masters["open360_icon_rounded"], (512, 512), "rounded"),
        (OUT_PNG_DIR / "open360_icon_circle_1024.png", masters["open360_icon_circle"], (1024, 1024), "circle"),
        (OUT_PNG_DIR / "open360_icon_circle_512.png", masters["open360_icon_circle"], (512, 512), "circle"),
        (OUT_PNG_DIR / "open360_icon_circle_384.png", masters["open360_icon_circle"], (384, 384), "circle"),
        (OUT_PNG_DIR / "open360_adaptive_background_432.png", masters["open360_icon_background"], (432, 432), "background"),
        (OUT_PNG_DIR / "open360_adaptive_foreground_432.png", masters["open360_icon_foreground"], (432, 432), "foreground"),
        (OUT_PNG_DIR / "open360_adaptive_monochrome_432.png", masters["open360_icon_monochrome"], (432, 432), "monochrome"),
        (OUT_PNG_DIR / "open360_mark_white_512.png", masters["open360_mark_white"], (512, 512), "mark"),
        (OUT_PNG_DIR / "open360_mark_dark_512.png", masters["open360_mark_dark"], (512, 512), "mark"),
        (OUT_PNG_DIR / "open360_mark_white_96.png", masters["open360_mark_white"], (96, 96), "mark"),
    ]

    densities = [
        ("mipmap-mdpi", 48),
        ("mipmap-hdpi", 72),
        ("mipmap-xhdpi", 96),
        ("mipmap-xxhdpi", 144),
        ("mipmap-xxxhdpi", 192),
    ]
    for folder, px in densities:
        png_outputs.append((OUT_MIPMAP_DIR / folder / "ic_launcher.png", masters["open360_icon_rounded"], (px, px), "mipmap"))
        png_outputs.append((OUT_MIPMAP_DIR / folder / "ic_launcher_round.png", masters["open360_icon_circle"], (px, px), "mipmap"))

    for out_path, src_img, size, kind in png_outputs:
        _save_png(src_img, out_path, size)
        _verify_png(out_path, size or src_img.size, kind)

    print(f"Exported {len(svg_specs)} SVGs to {OUT_SVG_DIR}")
    print(f"Exported and verified {len(png_outputs)} PNGs to {OUT_PNG_DIR}")
    for out_path, _, _, _ in png_outputs:
        with Image.open(out_path) as verified:
            print(f"  OK {out_path.relative_to(ROOT)} ({verified.size[0]}x{verified.size[1]} {verified.mode})")


if __name__ == "__main__":
    main()
