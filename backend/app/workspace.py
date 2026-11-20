"""Files of the single engineering workspace and the runtime deployment."""

from __future__ import annotations

import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any

WORKSPACE = Path(os.getenv("DT_WORKSPACE", "/generated/workspace"))
RUNTIME = Path(os.getenv("DT_RUNTIME_DIR", "/generated/runtime"))

REQUIREMENTS = WORKSPACE / "inputs" / "requirements.cd"
NODESET = WORKSPACE / "inputs" / "nodeset.xml"
FAULT_RULES = WORKSPACE / "inputs" / "fault-rules.md"
INPUT_NAMES = WORKSPACE / "inputs" / "names.json"
CANDIDATES = WORKSPACE / "candidates.json"
BINDING = WORKSPACE / "binding.bind"
BINDING_CHECK = WORKSPACE / "binding-check.json"
BINDING_REVIEW = WORKSPACE / "binding-review.json"
WATCHDOG = WORKSPACE / "watchdog.wf"
WATCHDOG_CHECK = WORKSPACE / "watchdog-check.json"
WATCHDOG_PROGRESS = WORKSPACE / "watchdog-progress.json"  # Only while the LLM drafts the workflows.
DASHBOARD = WORKSPACE / "dashboard.dash"
DASHBOARD_CHECK = WORKSPACE / "dashboard-check.json"
# Hashes of the binding specification, workflows and dashboard that the engineer approved.
APPROVALS = WORKSPACE / "approvals.json"
BPMN_DIR = WORKSPACE / "bpmn"
DEPLOYMENT = RUNTIME / "deployment.json"
RUNTIME_STATUS = RUNTIME / "status.json"


def read_text(path: Path) -> str | None:
    return path.read_text(encoding="utf-8") if path.exists() else None


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(text, encoding="utf-8")
    os.replace(temporary, path)


def read_json(path: Path, default: Any = None) -> Any:
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default


def write_json(path: Path, value: Any) -> None:
    write_text(path, json.dumps(value, indent=2, ensure_ascii=False))


def remove(*paths: Path) -> None:
    for path in paths:
        if path.is_dir():
            for child in path.iterdir():
                child.unlink()
            path.rmdir()
        elif path.exists():
            path.unlink()


def text_hash(*paths: Path) -> str:
    """Identifies the current content of the given files, so that an approval ends when they change."""
    digest = hashlib.sha256()
    for path in paths:
        digest.update((read_text(path) or "").encode("utf-8"))
    return digest.hexdigest()


def nodeset_name() -> str:
    """Name of the NodeSet in the binding header, derived from the uploaded file name."""
    stem = read_json(INPUT_NAMES, {}).get("nodeset", "NodeSet").split(".")[0]
    parts = [part for part in re.split(r"[^A-Za-z0-9]+", stem) if part]
    name = "".join(part[:1].upper() + part[1:] for part in parts) or "Machine"
    return name if name.endswith("NodeSet") else name + "NodeSet"
