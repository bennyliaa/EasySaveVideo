#!/usr/bin/env python3
"""Offline code12 transfer verification. No download, installation, or overwrite."""
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import tempfile

APK_NAME = "esv-slice09-code12-diagnostics-debug.apk"
APK_SIZE = 53408774
APK_SHA256 = "b41ff7c237218922ab367d2743e2ae131e9185a7fa3ca54913dc60a49466fc02"
GZIP_SIZE = 18833704
GZIP_SHA256 = "003629b5328f67825b1b8428d9d9edb614a288a702754da290cddd826e206759"
PARTS = {'esv-slice09-code12-diagnostics-debug.apk.gz.part01': (8388608, 'd131ee9b52ca5bad4f207136568e154b53f3cebd6a0e8d089fd8e6401299702b'), 'esv-slice09-code12-diagnostics-debug.apk.gz.part02': (8388608, '5645bcfe34f671d9ab421b6b6a34fd43e976af2cc025637c48288a5c48f49814'), 'esv-slice09-code12-diagnostics-debug.apk.gz.part03': (2056488, '801ec62f7b24b91afab5f27c64d5dab9e952cc68c160b271e210128d1fb6ddb6')}
DOCUMENTS = {'INDEPENDENT_RELEASE_COMPILED_REVIEW.json', 'FINAL_APK_VERIFICATION.json', 'APK_SIGNER_VERIFICATION.txt', 'SOURCE_DELTA.json', 'INDEPENDENT_POSTASSEMBLY_REVIEW.json', 'SLICE09_INDEPENDENT_FINAL_REVIEW.txt', 'INDEPENDENT_SOURCE_RUNNER_REVIEW.json', 'verify_and_extract.py', 'VERIFY_PASSED.json', 'CANDIDATE_PATCH.diff', 'README.md', 'APK_TOOL_EXECUTION_RECEIPT.json', 'commons-stage-diagnostics-slice09-delta-20261019T0353Z.tar.gz', 'FROZEN_PREDECESSORS_UNCHANGED.json', 'code12-source-delta-on-slice09.tar.gz', 'INDEPENDENT_CANDIDATE_REVIEW.json'}
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
    with tempfile.TemporaryDirectory(prefix="code12-verify-", dir=base) as temporary:
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
