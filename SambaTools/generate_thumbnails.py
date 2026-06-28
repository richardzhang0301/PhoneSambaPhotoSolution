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
import subprocess
import sys
from typing import Iterable, Set, Tuple

try:
    from PIL import Image, ImageOps
except ImportError:  # pragma: no cover - user environment check
    Image = None
    ImageOps = None

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp"}
THUMB_DIR_NAME = ".phonesamba_thumbs"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate fast thumbnails for the Android Samba photo tab.")
    parser.add_argument("photo_dir", help="Mounted drive or UNC path to the Samba photo folder")
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


def iter_photos(root: Path, recursive: bool) -> Iterable[Path]:
    entries = root.rglob("*") if recursive else root.iterdir()
    for path in entries:
        if not path.is_file():
            continue
        if THUMB_DIR_NAME in path.parts:
            continue
        if path.suffix.lower() in IMAGE_EXTENSIONS:
            yield path


def set_hidden(path: Path) -> None:
    if os.name != "nt":
        return
    try:
        subprocess.run(["attrib", "+h", str(path)], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        pass


def generate_one(source: Path, thumb_dir: Path, size: int, quality: int, force: bool) -> Tuple[str, str]:
    stat = source.stat()
    mtime_ms = int(stat.st_mtime * 1000)
    target = thumb_dir / thumbnail_name(source.name, stat.st_size, mtime_ms)

    if target.exists() and not force:
        return "skipped", source.name

    if Image is None or ImageOps is None:
        return "failed", f"{source.name}: Pillow is not installed"

    try:
        with Image.open(source) as image:
            image = ImageOps.exif_transpose(image)
            image.thumbnail((size, size), Image.Resampling.LANCZOS)
            if image.mode not in ("RGB", "L"):
                image = image.convert("RGB")
            temp = target.with_suffix(".tmp")
            image.save(temp, "JPEG", quality=quality, optimize=True, progressive=True)
            temp.replace(target)
        return "created", source.name
    except Exception as exc:  # keep going on one bad image
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

    photos = list(iter_photos(root, args.recursive))
    expected = {
        thumbnail_name(path.name, path.stat().st_size, int(path.stat().st_mtime * 1000))
        for path in photos
    }

    counts = {"created": 0, "skipped": 0, "failed": 0}
    max_workers = max(1, args.workers)
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = [
            executor.submit(generate_one, path, thumb_dir, args.size, args.quality, args.force)
            for path in photos
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

