#!/usr/bin/env python3
"""Generate sidecar thumbnails for PhoneSambaPhotoSolution.

Run this against a mounted or UNC Samba photo folder. Thumbnails are written to
.phonesamba_thumbs as JPEGs using the same hash naming that the Android app uses.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
from typing import Iterable, Optional, Set, Tuple

try:
    from PIL import Image, ImageDraw, ImageOps
except ImportError:  # pragma: no cover - user environment check
    Image = None
    ImageDraw = None
    ImageOps = None

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp"}
VIDEO_EXTENSIONS = {
    ".mp4",
    ".m4v",
    ".mov",
    ".avi",
    ".mkv",
    ".webm",
    ".3gp",
    ".3g2",
    ".wmv",
    ".mpg",
    ".mpeg",
    ".mts",
    ".m2ts",
}
MEDIA_EXTENSIONS = IMAGE_EXTENSIONS | VIDEO_EXTENSIONS
THUMB_DIR_NAME = ".phonesamba_thumbs"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate fast thumbnails for the Android Samba tab.")
    parser.add_argument("photo_dir", help="Mounted drive or UNC path to the Samba photo/video folder")
    parser.add_argument("--size", type=int, default=384, help="Maximum thumbnail width/height in pixels")
    parser.add_argument("--quality", type=int, default=82, help="JPEG quality, 1-95")
    parser.add_argument("--workers", type=int, default=4, help="Number of parallel thumbnail workers")
    parser.add_argument("--recursive", action="store_true", help="Scan subfolders too")
    parser.add_argument("--force", action="store_true", help="Regenerate thumbnails even when they already exist")
    parser.add_argument("--prune", action="store_true", help="Remove stale thumbnails that no longer match current files")
    return parser.parse_args()


def thumbnail_name(name: str, size: int, mtime_ms: int) -> str:
    payload = f"{name}|{size}|{mtime_ms}".encode("utf-8")
    return hashlib.sha1(payload).hexdigest() + ".jpg"


def iter_media(root: Path, recursive: bool) -> Iterable[Path]:
    entries = root.rglob("*") if recursive else root.iterdir()
    for path in entries:
        if not path.is_file():
            continue
        if THUMB_DIR_NAME in path.parts:
            continue
        if path.suffix.lower() in MEDIA_EXTENSIONS:
            yield path


def set_hidden(path: Path) -> None:
    if os.name != "nt":
        return
    try:
        subprocess.run(["attrib", "+h", str(path)], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        pass


def add_play_overlay(image: Image.Image) -> Image.Image:
    base = image.convert("RGBA")
    width, height = base.size
    min_dim = max(1, min(width, height))
    diameter = max(32, int(min_dim * 0.42))
    radius = diameter / 2
    center_x = width / 2
    center_y = height / 2

    overlay = Image.new("RGBA", base.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    draw.ellipse(
        (
            center_x - radius,
            center_y - radius,
            center_x + radius,
            center_y + radius,
        ),
        fill=(0, 0, 0, 118),
    )

    triangle_width = diameter * 0.38
    triangle_height = diameter * 0.46
    left = center_x - triangle_width * 0.28
    draw.polygon(
        (
            (left, center_y - triangle_height / 2),
            (left, center_y + triangle_height / 2),
            (center_x + triangle_width * 0.58, center_y),
        ),
        fill=(255, 255, 255, 215),
    )
    return Image.alpha_composite(base, overlay).convert("RGB")


def prepare_thumbnail(image: Image.Image, size: int, video: bool) -> Image.Image:
    image = ImageOps.exif_transpose(image)
    image.thumbnail((size, size), Image.Resampling.LANCZOS)
    if video:
        return add_play_overlay(image)
    if image.mode not in ("RGB", "L"):
        image = image.convert("RGB")
    return image


def save_thumbnail(image: Image.Image, target: Path, quality: int) -> None:
    temp = target.with_suffix(".tmp")
    image.save(temp, "JPEG", quality=quality, optimize=True, progressive=True)
    temp.replace(target)


def find_ffmpeg() -> Optional[str]:
    ffmpeg_path = shutil.which("ffmpeg")
    if ffmpeg_path is not None:
        return ffmpeg_path

    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


def extract_video_frame(source: Path, frame_path: Path, ffmpeg_path: str) -> Optional[str]:
    attempts = [
        [
            ffmpeg_path,
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-ss",
            "00:00:01",
            "-i",
            str(source),
            "-frames:v",
            "1",
            str(frame_path),
        ],
        [
            ffmpeg_path,
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(source),
            "-frames:v",
            "1",
            str(frame_path),
        ],
    ]
    last_error = ""
    for command in attempts:
        try:
            if frame_path.exists():
                frame_path.unlink()
            result = subprocess.run(
                command,
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                text=True,
                timeout=60,
            )
        except Exception as exc:  # keep going on one bad video
            last_error = str(exc)
            continue

        if frame_path.exists() and frame_path.stat().st_size > 0:
            return None
        last_error = (result.stderr or "").strip()
    return last_error or "ffmpeg could not extract a video frame"


def generate_image_thumbnail(source: Path, target: Path, size: int, quality: int) -> None:
    with Image.open(source) as image:
        save_thumbnail(prepare_thumbnail(image, size, video=False), target, quality)


def generate_video_thumbnail(source: Path, target: Path, size: int, quality: int, ffmpeg_path: str) -> Optional[str]:
    frame_path = target.with_suffix(".frame.jpg")
    try:
        error = extract_video_frame(source, frame_path, ffmpeg_path)
        if error is not None:
            return error
        with Image.open(frame_path) as image:
            save_thumbnail(prepare_thumbnail(image, size, video=True), target, quality)
        return None
    finally:
        try:
            if frame_path.exists():
                frame_path.unlink()
        except OSError:
            pass


def generate_one(source: Path, thumb_dir: Path, size: int, quality: int, force: bool, ffmpeg_path: Optional[str]) -> Tuple[str, str]:
    stat = source.stat()
    mtime_ms = int(stat.st_mtime * 1000)
    target = thumb_dir / thumbnail_name(source.name, stat.st_size, mtime_ms)

    if target.exists() and not force:
        return "skipped", source.name

    if Image is None or ImageDraw is None or ImageOps is None:
        return "failed", f"{source.name}: Pillow is not installed"

    try:
        if source.suffix.lower() in VIDEO_EXTENSIONS:
            if ffmpeg_path is None:
                return "failed", f"{source.name}: ffmpeg is required for video thumbnails"
            error = generate_video_thumbnail(source, target, size, quality, ffmpeg_path)
            if error is not None:
                return "failed", f"{source.name}: {error}"
        else:
            generate_image_thumbnail(source, target, size, quality)
        return "created", source.name
    except Exception as exc:  # keep going on one bad media file
        return "failed", f"{source.name}: {exc}"


def prune_stale(thumb_dir: Path, expected: Set[str]) -> int:
    removed = 0
    for thumb in thumb_dir.glob("*.jpg"):
        if thumb.name not in expected:
            try:
                thumb.unlink()
                removed += 1
            except OSError:
                pass
    return removed


def main() -> int:
    args = parse_args()
    root = Path(args.photo_dir).expanduser().resolve()
    if not root.exists() or not root.is_dir():
        print(f"Photo folder does not exist: {root}", file=sys.stderr)
        return 2

    thumb_dir = root / THUMB_DIR_NAME
    thumb_dir.mkdir(exist_ok=True)
    set_hidden(thumb_dir)

    media = list(iter_media(root, args.recursive))
    ffmpeg_path = find_ffmpeg()
    if ffmpeg_path is None and any(path.suffix.lower() in VIDEO_EXTENSIONS for path in media):
        print("Video thumbnails require ffmpeg or imageio-ffmpeg. Photo thumbnails will still be generated.")

    expected = {
        thumbnail_name(path.name, path.stat().st_size, int(path.stat().st_mtime * 1000))
        for path in media
    }

    counts = {"created": 0, "skipped": 0, "failed": 0}
    max_workers = max(1, args.workers)
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = [
            executor.submit(generate_one, path, thumb_dir, args.size, args.quality, args.force, ffmpeg_path)
            for path in media
        ]
        for future in concurrent.futures.as_completed(futures):
            status, message = future.result()
            counts[status] += 1
            if status == "failed":
                print(f"Failed: {message}")

    pruned = prune_stale(thumb_dir, expected) if args.prune else 0
    print(
        "Done. "
        f"Created {counts['created']}, skipped {counts['skipped']}, "
        f"failed {counts['failed']}, pruned {pruned}."
    )
    print(f"Thumbnail folder: {thumb_dir}")
    return 0 if counts["failed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
