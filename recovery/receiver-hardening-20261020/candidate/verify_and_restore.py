#!/usr/bin/env python3
"""Offline exact code12 -> code13 APK restoration. No download, signing or install."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import stat
import tempfile
import zipfile

BASE_SIZE = TARGET_SIZE = 53408774
BASE_SHA = "b41ff7c237218922ab367d2743e2ae131e9185a7fa3ca54913dc60a49466fc02"
TARGET_SHA = "6bcf764fb2faf7fd5f104d8f960a0498037917cc0178faa57af2b7b3d41e3c11"
OUTPUT_NAME = "esv-slice10-code13-budget-diagnostics-debug.apk"
PATCH_NAME = "code12-to-code13.apk.range-delta.zip"
PATCH_SIZE = 953938
PATCH_SHA = "51ac2a0a3a962ad5c258480d4d6fc594470fe659a3c35775a754b99c48d078a1"
SOURCE_NAME = "code13-source-delta-on-code12.tar.gz"
SOURCE_SIZE = 327027
SOURCE_SHA = "4179e442c1b3fb96e947e32cb4bd1ba2ec6386c04a923ba40fd72cf9b6268813"
LITERAL_SIZE = 2617664
LITERAL_SHA = "196094351e311f903f0f5f0f19dca821fec1174e8d12114662f6a9f6da00d512"
FILE_LIMIT = 1024 * 1024
RECIPE_LIMIT = 65536
FILES = {PATCH_NAME, SOURCE_NAME, "code13-verification-evidence.tar.gz", "README.md", "verify_and_restore.py"}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def unique_object(pairs):
    obj = {}
    for key, value in pairs:
        require(key not in obj, "Duplicate JSON key")
        obj[key] = value
    return obj


def parse_json(data):
    def invalid_constant(value):
        raise ValueError("Non-finite JSON value")
    return json.loads(data, object_pairs_hook=unique_object, parse_constant=invalid_constant)


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def read_regular(path, limit, exact_size=None, sha=None):
    require(not path.is_symlink() and path.is_file(), "Missing or linked input: " + path.name)
    size = path.stat().st_size
    require(0 <= size <= limit, "Input exceeds size bound")
    if exact_size is not None:
        require(size == exact_size, "Unexpected input size")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    require(len(data) == size and len(data) <= limit, "Input changed or exceeds bound")
    if sha is not None:
        require(hashlib.sha256(data).hexdigest() == sha, "Input SHA256 mismatch")
    return data


def strict_identity(actual, expected):
    require(type(actual) is dict and set(actual) == set(expected), "Wrong identity fields")
    for key, value in expected.items():
        require(type(actual[key]) is type(value) and actual[key] == value, "Wrong identity type or value")


def verify_transfer(directory):
    manifest = parse_json(read_regular(directory / "TRANSFER_MANIFEST.json", FILE_LIMIT))
    require(type(manifest) is dict and set(manifest) == {
        "schema", "base_apk", "target_apk", "patch", "source_archive", "files"
    }, "Unexpected transfer schema")
    require(type(manifest["schema"]) is int and manifest["schema"] == 1, "Unsupported schema")
    strict_identity(manifest["base_apk"], {"bytes": BASE_SIZE, "sha256": BASE_SHA})
    strict_identity(manifest["target_apk"], {"name": OUTPUT_NAME, "bytes": TARGET_SIZE, "sha256": TARGET_SHA})
    strict_identity(manifest["patch"], {"name": PATCH_NAME, "bytes": PATCH_SIZE, "sha256": PATCH_SHA})
    strict_identity(manifest["source_archive"], {"name": SOURCE_NAME, "bytes": SOURCE_SIZE, "sha256": SOURCE_SHA})
    rows = manifest["files"]
    require(type(rows) is list and len(rows) == len(FILES), "Wrong file inventory")
    seen = set()
    for row in rows:
        require(type(row) is dict and set(row) == {"name", "bytes", "sha256"}, "Invalid file metadata")
        name = row["name"]
        require(type(name) is str and name in FILES and name not in seen, "Unknown or duplicate filename")
        seen.add(name)
        require(type(row["bytes"]) is int and 0 <= row["bytes"] <= FILE_LIMIT, "Invalid file bound")
        require(type(row["sha256"]) is str and len(row["sha256"]) == 64 and
                all(c in "0123456789abcdef" for c in row["sha256"]), "Invalid file hash")
        read_regular(directory / name, FILE_LIMIT, row["bytes"], row["sha256"])
    require(seen == FILES, "Incomplete file inventory")
    read_regular(directory / SOURCE_NAME, FILE_LIMIT, SOURCE_SIZE, SOURCE_SHA)


def validate_recipe(recipe, literal):
    expected = {
        "schema": 1, "format": "ESV_EXACT_RANGE_DELTA_V1", "base_bytes": BASE_SIZE,
        "base_sha256": BASE_SHA, "target_bytes": TARGET_SIZE, "target_sha256": TARGET_SHA,
        "literal_bytes": LITERAL_SIZE, "literal_sha256": LITERAL_SHA,
    }
    require(type(recipe) is dict and set(recipe) == set(expected) | {"operations"}, "Invalid recipe fields")
    for key, value in expected.items():
        require(type(recipe[key]) is type(value) and recipe[key] == value, "Wrong recipe identity")
    require(len(literal) == LITERAL_SIZE and hashlib.sha256(literal).hexdigest() == LITERAL_SHA, "Literal mismatch")
    operations = recipe["operations"]
    require(type(operations) is list and len(operations) == 12, "Wrong operation count")
    total = 0
    literal_end = 0
    for op in operations:
        require(type(op) is list and len(op) == 3, "Invalid operation")
        kind, offset, length = op
        require(type(kind) is str and kind in {"copy", "literal"}, "Unknown operation")
        require(type(offset) is int and type(length) is int and offset >= 0 and length > 0, "Invalid range")
        bound = BASE_SIZE if kind == "copy" else LITERAL_SIZE
        require(offset <= bound and length <= bound - offset, "Range exceeds input")
        total += length
        require(total <= TARGET_SIZE, "Output exceeds exact bound")
        if kind == "literal":
            require(offset == literal_end, "Nonsequential literal range")
            literal_end += length
    require(total == TARGET_SIZE and literal_end == LITERAL_SIZE, "Incomplete output or literals")
    return operations


def decode_patch(data):
    require(len(data) == PATCH_SIZE and hashlib.sha256(data).hexdigest() == PATCH_SHA, "Delta size/SHA mismatch")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        require(len(entries) == 2 and {x.filename for x in entries} == {"RECIPE.json", "literal.bin"}, "Unexpected ZIP members")
        require(not archive.comment, "Unexpected ZIP comment")
        values = {}
        for info in entries:
            limit = RECIPE_LIMIT if info.filename == "RECIPE.json" else LITERAL_SIZE
            require(not info.is_dir() and stat.S_ISREG(info.external_attr >> 16) and
                    not info.flag_bits & 1 and info.compress_type == zipfile.ZIP_DEFLATED, "Unsafe ZIP member")
            require(0 <= info.file_size <= limit and 0 <= info.compress_size <= PATCH_SIZE, "ZIP size bound exceeded")
            with archive.open(info) as stream:
                value = stream.read(limit + 1)
                require(len(value) == info.file_size and len(value) <= limit and not stream.read(1), "Expanded size mismatch")
            values[info.filename] = value
    recipe = parse_json(values["RECIPE.json"])
    return validate_recipe(recipe, values["literal.bin"]), values["literal.bin"]


def require_published_identity(output, created):
    current = output.lstat()
    require(stat.S_ISREG(current.st_mode) and
            (current.st_dev, current.st_ino) == (created.st_dev, created.st_ino),
            "Changed publication identity")


def restore(base_apk, directory):
    output = directory / OUTPUT_NAME
    require(not output.exists() and not output.is_symlink(), "Output exists; use a fresh directory")
    require(not base_apk.is_symlink() and base_apk.is_file() and base_apk.stat().st_size == BASE_SIZE, "Wrong or linked base APK")
    require(digest(base_apk) == BASE_SHA, "Exact code12 APK required")
    operations, literal = decode_patch(read_regular(directory / PATCH_NAME, FILE_LIMIT, PATCH_SIZE, PATCH_SHA))
    with tempfile.TemporaryDirectory(prefix="code13-restore-", dir=directory) as temporary:
        rebuilt = Path(temporary) / OUTPUT_NAME
        with base_apk.open("rb") as source, rebuilt.open("xb") as target:
            for kind, offset, length in operations:
                if kind == "literal":
                    target.write(literal[offset:offset + length])
                else:
                    source.seek(offset)
                    remaining = length
                    while remaining:
                        block = source.read(min(1024 * 1024, remaining))
                        require(bool(block), "Base APK shortened during restoration")
                        target.write(block)
                        remaining -= len(block)
        require(rebuilt.stat().st_size == TARGET_SIZE and digest(rebuilt) == TARGET_SHA, "Restored APK SHA mismatch")
        require(base_apk.stat().st_size == BASE_SIZE and digest(base_apk) == BASE_SHA, "Base APK changed during restoration")
        # Exclusive output creation works on Windows without hardlinks or xattrs.
        # Never unlink the published pathname on failure: portable Python has
        # no atomic "delete only if still this file" operation. Retain uncertain
        # output for inspection and require a fresh directory for a retry.
        with rebuilt.open("rb") as source, output.open("x+b") as target:
            created = os.fstat(target.fileno())
            # Keep this handle open through verification so its identity cannot
            # be recycled after a concurrent pathname replacement.
            require(created.st_ino != 0, "Unavailable publication identity")
            shutil.copyfileobj(source, target, 1024 * 1024)
            target.flush()
            require_published_identity(output, created)
            target.seek(0)
            final_hash = hashlib.sha256()
            remaining = TARGET_SIZE
            while remaining:
                block = target.read(min(1024 * 1024, remaining))
                require(bool(block), "Final output verification failed")
                final_hash.update(block)
                remaining -= len(block)
            require(not target.read(1) and os.fstat(target.fileno()).st_size == TARGET_SIZE and
                    final_hash.hexdigest() == TARGET_SHA, "Final output verification failed")
            require_published_identity(output, created)

    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-apk", type=Path, required=True, help="Exact previously verified code12 APK; never modified")
    args = parser.parse_args()
    directory = Path(__file__).resolve().parent
    verify_transfer(directory)
    output = restore(args.base_apk, directory)
    print("VERIFIED " + output.name)
    print("SHA256 " + TARGET_SHA)
    print("No installation performed. Verify public signer with Android apksigner before installation.")


if __name__ == "__main__":
    main()
