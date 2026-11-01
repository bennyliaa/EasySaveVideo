#!/usr/bin/env python3
"""Offline code11 transfer verification. No download, installation, or overwrite."""
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import tempfile

APK_NAME = "esv-slice08-code11-commons-debug.apk"
APK_SIZE = 53333410
APK_SHA256 = "38f09f2dc752fc69cef9b2108c704c3e44e3b21d43134ee602f8b6e773c71988"
GZIP_SIZE = 18807092
GZIP_SHA256 = "9fb63b1d6940f45f3ae304ba52814bbaf88eb7bd45ee627b67af5a5d6da6dcbf"
PARTS = {'esv-slice08-code11-commons-debug.apk.gz.part01': (8388608, '7c044662ccfb7ce5061c0d60036e64ab4c6cb5606490074841a32e9d2bf6023e'), 'esv-slice08-code11-commons-debug.apk.gz.part02': (8388608, '3a72b3f9af41efda686ea3454d468bc1719541469846f849b66fc20f59d03b79'), 'esv-slice08-code11-commons-debug.apk.gz.part03': (2029876, '475a605d7b0bed93aea9157b555b82b1e90c3471389fe8fee65363d80c04909e')}
DOCUMENTS = {'FROZEN_PREDECESSORS_UNCHANGED.json', 'FINAL_APK_VERIFICATION.json', 'INDEPENDENT_POSTASSEMBLY_REVIEW.json', 'APK_SIGNER_VERIFICATION.txt', 'README.md', 'CANDIDATE_PATCH.diff', 'verify_and_extract.py', 'APK_TOOL_EXECUTION_RECEIPT.json', 'SOURCE_DELTA.json', 'code11-source-delta-on-slice08.tar.gz', 'INDEPENDENT_CANDIDATE_REVIEW.json', 'VERIFY_PASSED.json'}
DOCUMENT_LIMIT = 1024 * 1024


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
    with tempfile.TemporaryDirectory(prefix="code11-verify-", dir=base) as temporary:
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
