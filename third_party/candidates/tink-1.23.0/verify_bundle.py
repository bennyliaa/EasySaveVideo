#!/usr/bin/env python3
"""Verify the candidate bundle without compiling or executing third-party code."""
from pathlib import Path
import hashlib
import json
import sys

root = Path(__file__).resolve().parent
manifest_path = root / "IMPORT_MANIFEST.json"
manifest = json.loads(manifest_path.read_text())
expected = {entry["path"] for entry in manifest["files"]}
assert len(expected) == len(manifest["files"]), "Duplicate manifest paths"
expected |= {"IMPORT_MANIFEST.json", "HASHES.sha256"}
actual = {str(p.relative_to(root)) for p in root.rglob("*") if p.is_file()}
assert actual == expected, f"Unexpected/missing files: {actual ^ expected}"
for entry in manifest["files"]:
    path = root / entry["path"]
    assert path.resolve().is_relative_to(root), "Out-of-bundle path"
    data = path.read_bytes()
    assert len(data) == entry["bytes"], f"Size mismatch: {entry['path']}"
    assert hashlib.sha256(data).hexdigest() == entry["sha256"], f"Hash mismatch: {entry['path']}"
hash_rows = {}
for line in (root / "HASHES.sha256").read_text().splitlines():
    digest, name = line.split("  ", 1)
    assert name not in hash_rows, "Duplicate checksum path"
    hash_rows[name] = digest
assert set(hash_rows) == expected - {"HASHES.sha256"}, "Incomplete checksum coverage"
for name, digest in hash_rows.items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == digest, f"Checksum mismatch: {name}"
gate = json.loads((root / "CANDIDATE_GATE.json").read_text())
assert gate["admitted"] is False, "Candidate must not imply admission"
assert gate["substantive_license_unknowns"] == [], "Unresolved license terms remain"
print(json.dumps({"status": "PASS", "payload_files_verified": len(manifest["files"]),
                  "total_files": len(actual), "manifest_sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
                  "candidate_only": True, "admitted": False}, indent=2))
