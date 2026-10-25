import hashlib,json,re
from pathlib import Path
base=Path(__file__).resolve().parent
manifest=json.loads((base/"TRANSFER_MANIFEST.json").read_text(encoding="utf-8"))
assert manifest["archive_sha256"]=="339b2f76342399445b93ffd2cc263bf00f78b611b934b73f3b50264d6363ae0e"
assert manifest["archive_bytes"]==30715370 and len(manifest["parts"])==4
name=manifest["archive_name"]
assert name=="ESV-code12-frozen-diagnostic-recovery-20261025.tar.gz"
output=base/name
assert not output.exists(), "Refuse existing output"
digest=hashlib.sha256();size=0
with output.open("xb") as target:
    for index,part in enumerate(manifest["parts"],1):
        assert part["name"]==name+f".part{index:02d}"
        data=(base/part["name"]).read_bytes()
        assert len(data)==part["bytes"] and len(data)<=8388608
        assert hashlib.sha256(data).hexdigest()==part["sha256"]
        target.write(data);digest.update(data);size+=len(data)
assert size==30715370 and digest.hexdigest()==manifest["archive_sha256"]
assert hashlib.sha256((base/"MANIFEST.json").read_bytes()).hexdigest()==manifest["payload_manifest_sha256"]
print("VERIFIED",name,size,digest.hexdigest())
print("No extraction, build, signing or device operation performed.")
