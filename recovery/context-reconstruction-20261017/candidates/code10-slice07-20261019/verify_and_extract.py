#!/usr/bin/env python3
"""Offline code10 transfer verification. No download, installation, or overwrite."""
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import tempfile

APK_NAME = "esv-slice07-code10-debug.apk"
APK_SIZE = 53300642
APK_SHA256 = "e21952c29f8f5481611511d6b9fb47ec5db84bf7368db59c01b77313596fc262"
GZIP_SIZE = 18800732
GZIP_SHA256 = "89174b1903440e27b2be2938f97c89427ecf14dd23311aa3d5aad009ff32899e"
PARTS = {
    APK_NAME + ".gz.part01": (8388608, "33950a4c7c88a5f57a42b4d204d1a1339516f172690f78129a63ec41fbc8b729"),
    APK_NAME + ".gz.part02": (8388608, "d72b4293ac6533dd6f9094facf22fcc8536ea8da5d7515d049582e28f4f5aaca"),
    APK_NAME + ".gz.part03": (2023516, "545dc430cbe9eab0925725b0cf321010c5dae4bac34ad3055179847b18b7adc9"),
}
DOCUMENTS = {"APK_SIGNER_VERIFICATION.txt", "CANDIDATE_PATCH.diff", "FINAL_APK_VERIFICATION.json",
             "INDEPENDENT_CANDIDATE_REVIEW.json", "README.md", "VERIFY_PASSED.json", "verify_and_extract.py"}
DOCUMENT_LIMIT = 64 * 1024


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    base = Path(__file__).resolve().parent
    manifest_path = base / "TRANSFER_MANIFEST.json"
    require(manifest_path.is_file() and not manifest_path.is_symlink(), "Missing or linked manifest")
    require(manifest_path.stat().st_size <= DOCUMENT_LIMIT, "Manifest exceeds size limit")
    with manifest_path.open("rb") as stream:
        manifest_bytes = stream.read(DOCUMENT_LIMIT + 1)
    require(len(manifest_bytes) <= DOCUMENT_LIMIT, "Manifest exceeds size limit")
    manifest = json.loads(manifest_bytes)
    require(manifest["apk"] == {"name": APK_NAME, "bytes": APK_SIZE, "sha256": APK_SHA256}, "Unexpected APK identity")
    require(manifest["compression"] == {"format": "gzip", "bytes": GZIP_SIZE, "sha256": GZIP_SHA256}, "Unexpected transfer identity")
    files = manifest["files"]
    names = [entry["name"] for entry in files]
    require(len(names) == len(set(names)), "Duplicate manifest entry")
    require(set(names) == DOCUMENTS | set(PARTS), "Unexpected transfer file inventory")
    for entry in files:
        name = entry["name"]
        require(isinstance(name, str) and name not in ("", ".", "..") and "/" not in name and "\\" not in name and ":" not in name, "Unsafe transfer filename")
        path = base / name
        require(path.is_file() and not path.is_symlink(), "Missing or linked transfer file: " + name)
        require(type(entry["bytes"]) is int and entry["bytes"] >= 0, "Invalid file size")
        if name in PARTS:
            require((entry["bytes"], entry["sha256"]) == PARTS[name], "Unexpected part size or hash")
            require(path.stat().st_size == PARTS[name][0], "Unexpected actual part size")
        else:
            require(entry["bytes"] <= DOCUMENT_LIMIT and path.stat().st_size <= DOCUMENT_LIMIT, "Document exceeds size limit")
        require(path.stat().st_size == entry["bytes"] and digest(path) == entry["sha256"], "Transfer file mismatch: " + name)
    parts = manifest["parts_in_order"]
    require(parts == list(PARTS), "Unexpected part order")
    require(all(name in names for name in parts), "Missing part metadata")
    output = base / APK_NAME
    require(not output.exists() and not output.is_symlink(), "Output already exists; verify it separately or choose a fresh directory")
    with tempfile.TemporaryDirectory(prefix="code10-verify-", dir=base) as temporary:
        temp = Path(temporary)
        joined = temp / "transfer.gz"
        joined_size = 0
        with joined.open("xb") as target:
            for name in parts:
                with (base / name).open("rb") as source:
                    part_size = 0
                    while True:
                        block = source.read(min(1024 * 1024, PARTS[name][0] - part_size + 1))
                        if not block:
                            break
                        part_size += len(block)
                        joined_size += len(block)
                        require(part_size <= PARTS[name][0] and joined_size <= GZIP_SIZE, "Joined transfer exceeds exact bound")
                        target.write(block)
                    require(part_size == PARTS[name][0], "Part changed during reconstruction")
        require(joined.stat().st_size == GZIP_SIZE and digest(joined) == GZIP_SHA256, "Joined transfer mismatch")
        extracted = temp / APK_NAME
        size = 0
        with gzip.open(joined, "rb") as source, extracted.open("xb") as target:
            while True:
                block = source.read(min(1024 * 1024, APK_SIZE - size + 1))
                if not block:
                    break
                size += len(block)
                require(size <= APK_SIZE, "Expanded APK exceeds exact bound")
                target.write(block)
        require(size == APK_SIZE and digest(extracted) == APK_SHA256, "Extracted APK mismatch")
        with extracted.open("rb") as source, output.open("xb") as target:
            shutil.copyfileobj(source, target)
        require(output.stat().st_size == APK_SIZE and digest(output) == APK_SHA256, "Final APK verification failed")
    print("VERIFIED " + APK_NAME)
    print("SHA256 " + APK_SHA256)
    print("No APK installation performed. Verify the public signer using Android apksigner before installation.")


if __name__ == "__main__":
    main()
