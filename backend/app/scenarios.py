"""Scenario selection: the backend lets the simulated OPC UA server of the loaded example reset
its values and run one of the scenarios of its simulation file."""

from __future__ import annotations

import uuid
from pathlib import Path

from . import workspace


def control_dir(example: str) -> Path:
    """The directory through which the backend controls the simulator of an example."""
    return workspace.RUNTIME / "scenario" / example


def _control() -> Path:
    example = workspace.read_json(workspace.INPUT_NAMES, {}).get("example")
    if not example:
        raise ValueError("Scenarios run on the simulator of a loaded example; load an example first.")
    return control_dir(example)


def select(name: str) -> None:
    """The simulator resets its values and runs the named scenario of its simulation file."""
    workspace.write_json(_control() / "select.json", {"id": uuid.uuid4().hex, "scenario": name})
