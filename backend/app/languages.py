"""Calls the MontiCore language tool (languages/), which prints one JSON object per command.
Workflow requirements in the watchdog workflows are bound like service attributes, so the
binding commands also receive the workflows."""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from . import workspace

JAR = os.getenv("DTLANG_JAR", "/app/dtlang.jar")


def _run(*args: str | Path) -> dict[str, Any]:
    completed = subprocess.run(
        ["java", "-jar", JAR, *map(str, args)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=120,
    )
    if completed.returncode != 0:
        raise RuntimeError(completed.stderr.strip()[-2000:] or "The language tool failed.")
    return json.loads(completed.stdout)


def _workflows(directory: str, workflows: str | None) -> Path:
    """The workflow file to use: the given text, or the saved workflows."""
    path = Path(directory) / "watchdog.wf"
    path.write_text(workflows if workflows is not None else workspace.read_text(workspace.WATCHDOG) or "",
                    encoding="utf-8")
    return path


def candidates(workflows: str | None = None) -> dict[str, Any]:
    """Runs the compatibility filter for the service and workflow requirements."""
    with tempfile.TemporaryDirectory() as directory:
        return _run("candidates", workspace.REQUIREMENTS, workspace.NODESET, _workflows(directory, workflows))


def nodes() -> list[dict[str, Any]]:
    """Every node of the NodeSet, unfiltered."""
    return _run("nodes", workspace.NODESET)["nodes"]


def check_binding(text: str, workflows: str | None = None) -> dict[str, Any]:
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "binding.bind"
        path.write_text(text, encoding="utf-8")
        return _run("check-binding", workspace.REQUIREMENTS, workspace.NODESET, path,
                    _workflows(directory, workflows))


def check_watchdog(text: str, bpmn_dir: Path | None = None) -> dict[str, Any]:
    """Checks watchdog workflows against the current binding and fault rules; writes BPMN
    files, threshold tables and user task forms only when they are valid."""
    with tempfile.TemporaryDirectory() as directory:
        args: list[str | Path] = [
            "check-watchdog", workspace.REQUIREMENTS, workspace.NODESET, workspace.BINDING,
            _workflows(directory, text), workspace.FAULT_RULES,
        ]
        if bpmn_dir is not None:
            args.append(bpmn_dir)
        return _run(*args)


def check_dashboard(text: str) -> dict[str, Any]:
    """Checks a dashboard against the current binding and workflows and returns its model."""
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "dashboard.dash"
        path.write_text(text, encoding="utf-8")
        return _run("check-dashboard", workspace.REQUIREMENTS, workspace.NODESET, workspace.BINDING, path,
                    _workflows(directory, None))
