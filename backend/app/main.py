"""HTTP API of AIDE."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.responses import PlainTextResponse
import requests
from pydantic import BaseModel

from . import binding, dashboard, deploy, llm, scenarios, watchdog, workspace

app = FastAPI(title="AIDE")

EXAMPLES_DIR = Path(os.getenv("DT_EXAMPLES_DIR", "/examples"))
DEFAULT_ENDPOINT = os.getenv("DEFAULT_OPCUA_ENDPOINT", "opc.tcp://opcua-robot-cell:4840/robot-cell/")
INPUT_FILES = {
    "requirements": workspace.REQUIREMENTS,
    "nodeset": workspace.NODESET,
    "fault-rules": workspace.FAULT_RULES,
}


class InputFile(BaseModel):
    name: str
    text: str


class Selection(BaseModel):
    nodeId: str


class UnboundMark(BaseModel):
    reason: str = ""


class Specification(BaseModel):
    text: str


class DeployRequest(BaseModel):
    endpoint: str


class ScenarioSelection(BaseModel):
    name: str


def examples() -> dict[str, dict[str, Any]]:
    """The runnable examples: every directory with an example.json, which names its position in
    the list, title, NodeSet file and simulator endpoint."""
    found = {path.parent.name: workspace.read_json(path) for path in EXAMPLES_DIR.glob("*/example.json")}
    return dict(sorted(found.items(), key=lambda item: item[1]["order"]))


def _simulation(example: str | None) -> dict[str, Any] | None:
    """The scenarios of the example's simulator and the one it runs."""
    if example not in examples():
        return None
    config = workspace.read_json(EXAMPLES_DIR / example / "simulation.json", {})
    selected = workspace.read_json(scenarios.control_dir(example) / "select.json", {})
    return {
        "scenarios": [{"name": name, "description": scenario.get("description", "")}
                      for name, scenario in config.get("scenarios", {}).items()],
        "active": selected.get("scenario"),
    }


def _call(function, *args) -> dict[str, Any]:
    try:
        function(*args)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except (RuntimeError, requests.RequestException) as exc:  # System errors, never review reasons.
        raise HTTPException(502, str(exc)) from exc
    return state()


@app.get("/api/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/state")
def state() -> dict[str, Any]:
    names = workspace.read_json(workspace.INPUT_NAMES, {})
    candidates = workspace.read_json(workspace.CANDIDATES, {})
    check = workspace.read_json(workspace.BINDING_CHECK, {})
    resolved = check.get("resolved", {})
    unbound = check.get("unbound", {})
    entries = binding.entries()
    review = binding.review()
    attributes = []
    for attribute in candidates.get("attributes", []):
        key = attribute["key"]
        if attribute["kind"] not in {"input", "output"}:
            status = attribute["kind"]
        elif key in resolved:
            status = "bound"
        elif key in unbound:
            status = "unbound"
        elif key in review:
            status = "review"
        else:
            status = "open"
        attributes.append(attribute | {
            "status": status,
            "entry": entries.get(key) if key in resolved else None,
            "unboundReason": unbound.get(key),
            "review": review.get(key),
        })
    watchdog_check = workspace.read_json(workspace.WATCHDOG_CHECK, {})
    dashboard_check = workspace.read_json(workspace.DASHBOARD_CHECK, {})
    example = names.get("example")
    available = examples()
    return {
        "examples": [{"name": name, "title": item["title"]} for name, item in available.items()],
        "example": example,
        "simulation": _simulation(example),
        "inputs": {kind: names.get(kind) for kind in INPUT_FILES},
        "requirementsErrors": candidates.get("errors", []),
        "nodeCount": candidates.get("nodeCount"),
        "attributes": attributes,
        "bindingText": workspace.read_text(workspace.BINDING),
        "bindingErrors": check.get("errors", []),
        "bindingApproved": binding.approved(),
        "bindingOpenCases": binding.open_cases() if candidates else [],
        "unboundReport": binding.unbound_report() if candidates else [],
        "openRules": watchdog_check.get("openRules", []),
        "faultRules": workspace.read_text(workspace.FAULT_RULES),
        "watchdogText": workspace.read_text(workspace.WATCHDOG),
        "watchdogErrors": watchdog_check.get("errors", []),
        "workflows": watchdog_check.get("workflows", []),
        "watchdogRevisions": watchdog_check.get("revisions"),
        "watchdogProgress": workspace.read_json(workspace.WATCHDOG_PROGRESS),
        "watchdogApproved": watchdog.approved(),
        "dashboardText": workspace.read_text(workspace.DASHBOARD),
        "dashboardErrors": dashboard_check.get("errors", []),
        "dashboardViews": dashboard.model(),
        "dashboardApproved": dashboard.approved(),
        "model": llm.model(),
        "defaultEndpoint": available[example]["endpoint"] if example in available else DEFAULT_ENDPOINT,
        "deployment": workspace.read_json(workspace.DEPLOYMENT),
        "runtime": workspace.read_json(workspace.RUNTIME_STATUS),
    }


@app.put("/api/inputs/{kind}")
def put_input(kind: str, file: InputFile) -> dict[str, Any]:
    if kind not in INPUT_FILES:
        raise HTTPException(404, f"Unknown input {kind}.")
    workspace.write_text(INPUT_FILES[kind], file.text)
    names = workspace.read_json(workspace.INPUT_NAMES, {})
    if kind in {"requirements", "nodeset"}:
        names.pop("example", None)  # Own inputs no longer match an example simulator.
    workspace.write_json(workspace.INPUT_NAMES, names | {kind: file.name})
    return _call(_inputs_changed if kind != "fault-rules" else watchdog.recheck)


@app.post("/api/examples/{name}")
def load_example(name: str) -> dict[str, Any]:
    example = examples().get(name)
    if example is None:
        raise HTTPException(404, f"Unknown example {name}.")
    files = {"requirements": "requirements.cd", "nodeset": example["nodeset"], "fault-rules": "fault-rules.md"}
    for kind, file_name in files.items():
        workspace.write_text(INPUT_FILES[kind], (EXAMPLES_DIR / name / file_name).read_text(encoding="utf-8"))
    workspace.write_json(workspace.INPUT_NAMES, files | {"example": name})
    workspace.remove(workspace.WATCHDOG, workspace.DASHBOARD)
    return _call(_inputs_changed)


@app.post("/api/simulator/scenario")
def select_scenario(selection: ScenarioSelection) -> dict[str, Any]:
    """Lets the simulator of the loaded example reset its values and run the selected scenario."""
    simulation = _simulation(workspace.read_json(workspace.INPUT_NAMES, {}).get("example"))
    if simulation is None or selection.name not in {s["name"] for s in simulation["scenarios"]}:
        raise HTTPException(400, f"The loaded example has no scenario {selection.name}.")
    return _call(scenarios.select, selection.name)


def _inputs_changed() -> None:
    if workspace.REQUIREMENTS.exists() and workspace.NODESET.exists():
        binding.refresh_candidates()
        watchdog.recheck()


@app.post("/api/bindings/generate")
def generate_bindings() -> dict[str, Any]:
    if not workspace.CANDIDATES.exists():
        raise HTTPException(400, "Upload the service requirements and the NodeSet first.")
    return _call(binding.generate)


@app.put("/api/bindings/{key}")
def resolve_binding(key: str, selection: Selection) -> dict[str, Any]:
    return _call(binding.resolve, key, selection.nodeId)


@app.post("/api/bindings/approve")
def approve_binding() -> dict[str, Any]:
    return _call(binding.approve)


@app.delete("/api/bindings/{key}")
def remove_binding(key: str) -> dict[str, Any]:
    return _call(binding.remove, key)


@app.post("/api/bindings/{key}/unbound")
def mark_unbound(key: str, mark: UnboundMark) -> dict[str, Any]:
    return _call(binding.mark_unbound, key, mark.reason)


@app.post("/api/watchdog/draft")
def draft_watchdog() -> dict[str, Any]:
    return _call(watchdog.draft)


@app.put("/api/watchdog")
def save_watchdog(specification: Specification) -> dict[str, Any]:
    if not workspace.BINDING.exists():
        raise HTTPException(400, "Create the binding first.")
    return _call(watchdog.save, specification.text)


@app.post("/api/watchdog/approve")
def approve_watchdog() -> dict[str, Any]:
    return _call(watchdog.approve)


@app.get("/api/watchdog/bpmn/{name}", response_class=PlainTextResponse)
def bpmn(name: str) -> str:
    path = workspace.BPMN_DIR / f"{name}.bpmn"
    if path.parent != workspace.BPMN_DIR or not path.exists():
        raise HTTPException(404, "No such watchdog.")
    return path.read_text(encoding="utf-8")


@app.post("/api/dashboard/draft")
def draft_dashboard() -> dict[str, Any]:
    return _call(dashboard.draft)


@app.put("/api/dashboard")
def save_dashboard(specification: Specification) -> dict[str, Any]:
    if not workspace.BINDING.exists():
        raise HTTPException(400, "Create the binding first.")
    return _call(dashboard.save, specification.text)


@app.post("/api/dashboard/approve")
def approve_dashboard() -> dict[str, Any]:
    return _call(dashboard.approve)


@app.post("/api/deploy")
def deploy_package(request: DeployRequest) -> dict[str, Any]:
    return _call(deploy.deploy, request.endpoint)

