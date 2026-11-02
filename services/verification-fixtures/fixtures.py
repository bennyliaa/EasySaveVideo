"""Reproducible local fixtures and result scoring; no scraping or engine imitation."""
import argparse
import hashlib
import json
from pathlib import Path
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parent
GROUPS = [("static", "positive", 25), ("metadata", "positive", 15),
          ("dynamic", "positive", 20), ("embedded", "positive", 10),
          ("negative", "negative", 20), ("unknown", "unknown", 10)]


def build(destination):
    destination.mkdir(parents=True, exist_ok=True)
    asset = destination / "media.mp4"
    if not asset.is_file():
        raise ValueError("Missing legally supplied synthetic media.mp4 fixture")
    cases = []
    for group, label, count in GROUPS:
        for i in range(count):
            case_id = f"{group}-{i:02d}"
            if group == "static":
                body = '<video id="player" class="original" controls preload="none" src="/media.mp4"></video>'
            elif group == "dynamic":
                body = ('<div id="mount"></div><script>setTimeout(() => {'
                        'const v=document.createElement("video");v.controls=true;'
                        'v.preload="none";v.src="/media.mp4";'
                        'document.getElementById("mount").appendChild(v);},'
                        f'{100 + i * 20});</script>')
            elif group == "metadata":
                body = ('<script type="application/ld+json">'
                        '{"@context":"https://schema.org","@type":"VideoObject",'
                        '"name":"Synthetic local media","contentUrl":"/media.mp4"}'
                        '</script><p>Structured media identity</p>')
            elif group == "embedded":
                body = '<iframe title="local player" src="/static-00.html"></iframe>'
            elif group == "negative":
                body = '<p>No playable video</p><a href="/missing.mp4">Filename trap</a>'
            else:
                body = '<p>Access deliberately denied; ground truth unknown</p>'
            content = (f'<!doctype html><meta charset="utf-8"><title>{case_id}</title>'
                       f'<main>{body}</main>').encode()
            filename = case_id + ".html"
            (destination / filename).write_bytes(content)
            cases.append({"id": case_id, "path": "/" + filename,
                          "label": label, "group": group,
                          "expected_status": 403 if group == "unknown" else 200,
                          "media_identity": "media.mp4" if label == "positive" else None,
                          "resource_extractable": True if label == "positive" else None,
                          "sha256": hashlib.sha256(content).hexdigest(),
                          "label_basis": "authored local synthetic fixture; independent of A/B outputs"})
    manifest = {"schema": 1, "count": 100, "scope": "local fixtures only",
                "media_sha256": hashlib.sha256(asset.read_bytes()).hexdigest(), "cases": cases}
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest


def score(manifest, rows):
    cases = {c["id"]: c for c in manifest["cases"]}
    seen = set()
    counts = {"correct_positive": 0, "correct_negative": 0, "false_positive": 0,
              "false_negative": 0, "abstained": 0, "unknown_ground_truth": 0,
              "correct_media_identity": 0, "correct_resource_extraction": 0, "extraction_measured": 0,
              "unrun": len(cases)}
    disallowed = {"cancelled", "timeout", "error", "denied", "rate_limited", "unavailable"}
    for row in rows:
        case_id = row["id"]
        if case_id not in cases or case_id in seen:
            raise ValueError("Unknown or duplicate case id")
        seen.add(case_id)
        prediction = row["prediction"]
        if row["status"] not in disallowed | {"success"}:
            raise ValueError("Invalid execution status")
        if prediction not in {"positive", "negative", "unknown"}:
            raise ValueError("Invalid prediction")
        if row["status"] in disallowed and prediction != "unknown":
            raise ValueError("Failure/cancel/denial cannot produce a confirmed label")
        if prediction == "positive" and row.get("evidence") != "verified_media":
            raise ValueError("A candidate URL is not confirmed video evidence")
        counts["unrun"] -= 1
        truth = cases[case_id]["label"]
        if truth == "positive" and row["status"] == "success":
            if isinstance(row.get("resource_extracted"), bool):
                counts["extraction_measured"] += 1
            if row.get("media_identity") == cases[case_id]["media_identity"]:
                counts["correct_media_identity"] += 1
                if row.get("resource_extracted") is True:
                    counts["correct_resource_extraction"] += 1
        if truth == "unknown":
            counts["unknown_ground_truth"] += 1
        elif prediction == "unknown":
            counts["abstained"] += 1
        elif prediction == truth:
            counts["correct_" + prediction] += 1
        elif prediction == "positive":
            counts["false_positive"] += 1
        else:
            counts["false_negative"] += 1
    counts["positive_denominator"] = sum(c["label"] == "positive" for c in cases.values())
    counts["negative_denominator"] = sum(c["label"] == "negative" for c in cases.values())
    predicted_positive = counts["correct_positive"] + counts["false_positive"]
    known = counts["positive_denominator"] + counts["negative_denominator"]
    decided = sum(counts[k] for k in ["correct_positive", "correct_negative", "false_positive", "false_negative"])
    counts["precision"] = counts["correct_positive"] / predicted_positive if predicted_positive else None
    counts["decision_coverage_known_labels"] = decided / known
    counts["correct_positive_over_all_positive"] = counts["correct_positive"] / counts["positive_denominator"]
    counts["resource_extraction_rate"] = (counts["correct_resource_extraction"] / counts["positive_denominator"]
                                          if counts["extraction_measured"] else None)
    counts["extraction_measurement_coverage"] = counts["extraction_measured"] / counts["positive_denominator"]
    return counts


def build_recovery(destination, manifest):
    """20 frozen originals, four controlled mutations each; separate from main 100."""
    destination.mkdir(parents=True, exist_ok=True)
    cases = []
    for original in [c for c in manifest["cases"] if c["group"] == "static"][:20]:
        original_bytes = (destination.parent / original["path"].lstrip("/")).read_bytes()
        if hashlib.sha256(original_bytes).hexdigest() != original["sha256"]:
            raise ValueError("Original changed after freeze")
        variants = {
            "class-id": '<video id="new-player" class="revised" controls preload="none" src="/media.mp4"></video>',
            "wrap-reorder": '<aside>Reordered text</aside><section><article><video controls preload="none" src="/media.mp4"></video></article></section>',
            "similar-decoy": '<a id="player" href="/missing.mp4">Similar decoy</a><video controls preload="none" src="/media.mp4"></video>',
            "target-deleted": '<a id="player" href="/missing.mp4">Deleted target; decoy remains</a>'
        }
        for kind, body in variants.items():
            case_id = original["id"] + "-" + kind
            payload = (f'<!doctype html><meta charset="utf-8"><title>{case_id}</title><main>{body}</main>').encode()
            (destination / (case_id + ".html")).write_bytes(payload)
            cases.append({"id": case_id, "path": "/recovery/" + case_id + ".html",
                          "original_id": original["id"], "original_sha256": original["sha256"],
                          "mutation": kind, "label": "negative" if kind == "target-deleted" else "positive",
                          "sha256": hashlib.sha256(payload).hexdigest(),
                          "media_identity": None if kind == "target-deleted" else "media.mp4"})
    frozen = {"schema": 1, "original_count": 20, "variant_count": 80,
              "scope": "controlled local mutations only; engine recovery NOT_RUN", "cases": cases}
    (destination / "manifest.json").write_text(json.dumps(frozen, indent=2), encoding="utf-8")
    return frozen


class FixtureHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT / "corpus"), **kwargs)

    def do_GET(self):
        host = self.headers.get("Host", "").split(":")[0]
        if host not in {"localhost", "127.0.0.1"}:
            self.send_error(403)
            return
        if self.path.startswith("/unknown-"):
            self.send_error(403)
            return
        super().do_GET()

    def list_directory(self, path):
        self.send_error(403)
        return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["build", "serve", "score"])
    parser.add_argument("--results", type=Path)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if args.command == "build":
        manifest = build(ROOT / "corpus")
        recovery = build_recovery(ROOT / "corpus" / "recovery", manifest)
        print(json.dumps({"cases": manifest["count"], "recovery_variants": recovery["variant_count"]}))
    elif args.command == "score":
        if args.results is None:
            parser.error("--results is required")
        manifest = json.loads((ROOT / "corpus" / "manifest.json").read_text())
        rows = [json.loads(line) for line in args.results.read_text().splitlines() if line.strip()]
        print(json.dumps(score(manifest, rows), indent=2))
    else:
        with ThreadingHTTPServer(("127.0.0.1", args.port), FixtureHandler) as server:
            print(f"Local synthetic fixtures: http://127.0.0.1:{args.port}", flush=True)
            server.serve_forever()


if __name__ == "__main__":
    main()
