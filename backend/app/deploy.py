"""Deployment: BPMN watchdogs to Camunda 8, the runtime configuration for the
adapter, and the Home Assistant dashboard. Deployment needs the engineer's approval of
the unchanged binding specification, workflows and dashboard."""

from __future__ import annotations

import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests
import yaml

from . import binding as binding_step, dashboard, watchdog, workspace

CAMUNDA_REST_URL = os.getenv("CAMUNDA_REST_URL", "http://localhost:8180/v2").rstrip("/")
HA_DASHBOARD = Path(os.getenv("HA_CONFIG_DIR", "/ha-config")) / "dt-dashboard.yaml"
DISPLAY_UNITS = {"degC": "°C", "Cel": "°C", "degF": "°F"}


def deploy(endpoint: str) -> dict[str, Any]:
    binding = workspace.read_json(workspace.BINDING_CHECK)
    if not binding or binding.get("errors") or not binding.get("resolved"):
        raise ValueError("Deployment needs a binding with at least one valid entry.")
    if not binding_step.approved():
        raise ValueError("The engineer must approve the current binding specification before deployment.")
    workflows: list[dict[str, Any]] = []
    if workspace.WATCHDOG.exists():
        check = workspace.read_json(workspace.WATCHDOG_CHECK)
        if not check or check.get("errors"):
            raise ValueError("The watchdog workflows have errors; fix them before deployment.")
        if not watchdog.approved():
            raise ValueError("The engineer must approve the current watchdog workflows before deployment.")
    if not dashboard.approved():
        raise ValueError("The engineer must approve the current dashboard before deployment.")
    open_rules: list[dict[str, Any]] = []
    if workspace.WATCHDOG.exists():
        workflows = check["workflows"]
        open_rules = check.get("openRules", [])
        files = resources(workspace.BPMN_DIR)
        processes = _deploy_bpmn(files) if files else []
    else:
        processes = []
    _retire_watchdogs({process["key"] for process in processes})

    views = dashboard.model()
    ranges = {card["attributes"][0]: (card["min"], card["max"]) for view in views for card in view["cards"]
              if card["card"] == "input" and "min" in card}
    entities = _entities(list(binding["resolved"].values()), workflows, ranges)
    workspace.write_text(HA_DASHBOARD, _dashboard(views, entities))
    deployment = {
        "deployedAt": datetime.now(timezone.utc).isoformat(),
        "endpoint": endpoint,
        "bindings": list(binding["resolved"].values()),
        # Attributes without a node: the adapter publishes no value for them and reports them as unavailable.
        "unbound": binding_step.unbound_report(),
        "openRules": open_rules,
        "workflows": workflows,
        "processes": processes,
        "entities": entities,
    }
    workspace.write_json(workspace.DEPLOYMENT, deployment)
    return deployment


def resources(directory: Path) -> list[Path]:
    """The BPMN models of a directory with their threshold tables (DMN) and user task forms."""
    return sorted(directory.glob("*.bpmn")) + sorted(directory.glob("*.dmn")) + sorted(directory.glob("*.form"))


def _deploy_bpmn(files: list[Path]) -> list[dict[str, Any]]:
    """Deploys the watchdogs with their threshold tables (DMN) and user task forms and returns
    the process versions that Camunda created."""
    resources = [("resources", (path.name, path.read_bytes(),
                                "application/json" if path.suffix == ".form" else "application/xml"))
                 for path in files]
    response = requests.post(f"{CAMUNDA_REST_URL}/deployments", files=resources, timeout=30)
    if not response.ok:
        raise RuntimeError(f"Camunda rejected the deployment ({response.status_code}): {response.text[:500]}")
    return [
        {"processId": item["processDefinition"]["processDefinitionId"],
         "version": item["processDefinition"]["processDefinitionVersion"],
         "key": str(item["processDefinition"]["processDefinitionKey"])}
        for item in response.json().get("deployments", [])
        if item.get("processDefinition")
    ]


def _camunda(path: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
    response = requests.post(f"{CAMUNDA_REST_URL}{path}", json=body or {}, timeout=30)
    if not response.ok:
        raise RuntimeError(f"Camunda rejected {path} ({response.status_code}): {response.text[:500]}")
    return response.json() if response.content else {}


def _search(kind: str, filter_: dict[str, Any]) -> list[dict[str, Any]]:
    """All items of a Camunda search, page by page."""
    items: list[dict[str, Any]] = []
    page: dict[str, Any] = {"limit": 100}
    while True:
        result = _camunda(f"/{kind}/search", {"filter": filter_, "page": page})
        items += result.get("items", [])
        cursor = (result.get("page") or {}).get("endCursor")
        if not result.get("items") or not cursor or len(result["items"]) < page["limit"]:
            return items
        page = {"limit": 100, "after": cursor}


def _retire_watchdogs(current: set[str]) -> None:
    """Removes the watchdogs of earlier deployments from Camunda: their process definitions keep
    their message start events, so every value change would still start them next to the
    deployed watchdogs, and a running instance of an earlier version blocks the start of the
    current version (one active instance per watchdog). Cancels their running instances first."""
    retired = [definition for definition in _search("process-definitions", {})
               if definition["processDefinitionId"].startswith("Watchdog_")
               and str(definition["processDefinitionKey"]) not in current]
    for definition in retired:
        for instance in _search("process-instances", {"processDefinitionKey": definition["processDefinitionKey"],
                                                      "state": "ACTIVE"}):
            if instance.get("parentProcessInstanceKey"):
                continue  # A called workflow such as SafeStop ends with its parent.
            response = requests.post(
                f"{CAMUNDA_REST_URL}/process-instances/{instance['processInstanceKey']}/cancellation",
                json={}, timeout=30)
            if not response.ok and response.status_code != 404:  # 404: the instance has just ended.
                raise RuntimeError(f"Camunda did not cancel a watchdog run ({response.status_code}): "
                                   f"{response.text[:500]}")
    # Newest version first, so that no earlier version becomes the latest one with an open start event.
    # The search keeps deleted definitions as history, so a definition of an earlier retirement
    # is found again; the engine then answers 404, since it no longer has the definition.
    for definition in sorted(retired, key=lambda d: d["version"], reverse=True):
        response = requests.post(f"{CAMUNDA_REST_URL}/resources/{definition['processDefinitionKey']}/deletion",
                                 json={}, timeout=30)
        if not response.ok and response.status_code != 404:
            raise RuntimeError(f"Camunda did not delete the watchdog {definition['processDefinitionId']} "
                               f"({response.status_code}): {response.text[:500]}")


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")


def _entities(bindings: list[dict[str, Any]], workflows: list[dict[str, Any]],
              ranges: dict[str, tuple[float, float]]) -> list[dict[str, Any]]:
    """Selects one Home Assistant entity per bound attribute by type and access mode. A method
    without argument becomes a button; a number gets the range of its dashboard input."""
    entities = []
    for item in bindings:
        key = item["attribute"]
        slug = _slug(key)
        writable = item["access"] == "write"
        if writable and "callWhen" in item:
            component = "button"
        elif writable:
            component = {"boolean": "switch", "String": "text"}.get(item["type"], "number")
        else:
            component = "binary_sensor" if item["type"] == "boolean" else "sensor"
        entity = {
            "key": key,
            "kind": "binding",
            "component": component,
            "uniqueId": f"dt_{slug}",
            "entityId": f"{component}.dt_{slug}",
            "name": key,
            "type": item["type"],
            "unit": DISPLAY_UNITS.get(item.get("unit"), item.get("unit")),
            "stateTopic": f"dt/state/{key}",
        }
        if writable:
            entity["commandTopic"] = f"dt/command/{key}"
        if component == "button":
            entity["payloadPress"] = "ON" if item["callWhen"] else "OFF"
        elif writable:
            # A method has no value to read back, so its control is optimistic.
            entity["optimistic"] = not item["readable"]
        if component == "number":
            entity["min"], entity["max"] = ranges.get(key, (-1e6, 1e6))
            entity["step"] = 1 if item["type"] in {"int", "long"} else 0.001
        entities.append(entity)
    for workflow in workflows:
        slug = _slug(workflow["name"])
        entities.append({
            "key": workflow["processId"],
            "kind": "watchdog",
            "component": "sensor",
            "uniqueId": f"dt_watchdog_{slug}",
            "entityId": f"sensor.dt_watchdog_{slug}",
            "name": f"Watchdog {workflow['name']}",
            "type": "String",
            "unit": None,
            "stateTopic": f"dt/watchdog/{workflow['processId']}",
        })
    return entities


def _label(key: str, keys: set[str]) -> str:
    """A readable card name: motorTemperature becomes Motor temperature. The owner is added only
    if another bound attribute has the same name, for example Axis1 and Axis2."""
    owner, _, name = key.rpartition(".")
    words = re.sub(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])", " ", name).split()
    label = " ".join([words[0].capitalize(), *(w if w.isupper() else w.lower() for w in words[1:])])
    if sum(k.rpartition(".")[2] == name for k in keys) > 1 and owner:
        label = f"{owner} {label[0].lower()}{label[1:]}"
    return label


# Card icons that follow from the type and the access of the bound attribute.
TEMPERATURE_UNITS = {"°C", "°F", "K"}


def _icon(entity: dict[str, Any]) -> str | None:
    if entity["kind"] == "watchdog":
        return "mdi:shield-alert-outline"
    if entity["component"] == "button":
        return "mdi:gesture-tap-button"
    if entity.get("unit") in TEMPERATURE_UNITS:
        return "mdi:thermometer"
    if entity["type"] == "String" and entity["component"] == "sensor":
        return "mdi:information-outline"
    return None


# Sections of a view, in order, and the cards that belong to each.
SECTIONS = [("Status", {"alarm", "tile", "gauge"}), ("Trend", {"history"}), ("Controls", {"toggle", "button", "input"})]


def _dashboard(views: list[dict[str, Any]], entities: list[dict[str, Any]]) -> str:
    """Maps the checked dashboard model to a Home Assistant sections view per dashboard view. In each
    view the cards are grouped under headings into status, trend and controls; alarms come first."""
    by_key = {entity["key"]: entity for entity in entities}
    ids = {key: entity["entityId"] for key, entity in by_key.items()}
    keys = {entity["key"] for entity in entities if entity["kind"] == "binding"}
    integers = {entity["key"] for entity in entities if entity.get("step") == 1}

    def tile(key: str, **extra: Any) -> dict[str, Any]:
        result = {"type": "tile", "entity": ids[key], "name": _label(key, keys)}
        if icon := _icon(by_key[key]):
            result["icon"] = icon
        return result | extra

    def card(item: dict[str, Any]) -> dict[str, Any]:
        result = control(item)
        visible = item.get("visible")
        if not visible:
            return result
        # The adapter publishes Booleans as ON and OFF, which Home Assistant shows as on and off.
        value = visible["value"]
        state = ("on" if value else "off") if isinstance(value, bool) else str(value)
        return result | {"visibility": [{"condition": "state", "entity": ids[visible["attribute"]], "state": state}]}

    def control(item: dict[str, Any]) -> dict[str, Any]:
        keys_ = item.get("attributes", [])
        key = keys_[0] if keys_ else None
        entity = ids.get(key) if key else None
        match item["card"]:
            case "gauge":
                gauge = {"type": "gauge", "entity": entity, "name": _label(key, keys), "min": item["min"],
                         "max": item["max"], "needle": True, "grid_options": {"columns": 6}}
                if "severity" in item:
                    warning, critical = item["severity"]
                    # Home Assistant colors from each start value up to the next one. A critical value below
                    # the warning value means that low values are bad, such as the remaining tool life.
                    gauge["severity"] = ({"green": item["min"], "yellow": warning, "red": critical} if warning <= critical
                                         else {"red": item["min"], "yellow": critical, "green": warning})
                return gauge
            case "history":
                return {"type": "history-graph", "entities": [{"entity": ids[k], "name": _label(k, keys)} for k in keys_],
                        "hours_to_show": item["hours"], "grid_options": {"columns": "full"}}
            case "tile":
                return tile(key)
            case "toggle":
                return tile(key, features=[{"type": "toggle"}])
            case "button":
                service = "button.press" if entity.startswith("button.") else "switch.turn_on"
                action = {"action": "perform-action", "perform_action": service,
                          "target": {"entity_id": entity}, "confirmation": {"text": item["confirm"]}}
                return tile(key, tap_action=action, icon_tap_action=action)
            case "input" if key in integers and "min" in item and item["max"] - item["min"] < 12:
                # A small integer range: one button per value, for example per cell of a board.
                buttons = []
                for value in range(int(item["min"]), int(item["max"]) + 1):
                    action = {"action": "perform-action", "perform_action": "number.set_value",
                              "target": {"entity_id": entity}, "data": {"value": value}}
                    if item["confirm"]:
                        action["confirmation"] = {"text": item["confirm"]}
                    buttons.append({"type": "button", "name": str(value), "show_icon": False, "tap_action": action})
                return {"type": "grid", "title": _label(key, keys), "columns": 3, "square": True, "cards": buttons,
                        "grid_options": {"columns": 6}}
            case "input" if item["confirm"]:
                # The operator confirms and then enters the value in the control of the entity.
                action = {"action": "more-info", "confirmation": {"text": item["confirm"]}}
                return tile(key, icon="mdi:form-textbox", tap_action=action, icon_tap_action=action)
            case "input":
                return tile(key, features=[{"type": "numeric-input", "style": "buttons"}])
            case "alarm":
                # Shown only while the watchdog reacts to a deviation.
                watchdog_entity = ids[item["processId"]]
                return {"type": "tile", "entity": watchdog_entity, "icon": "mdi:shield-alert-outline",
                        "color": "red", "grid_options": {"columns": 12},
                        "visibility": [{"condition": "state", "entity": watchdog_entity, "state_not": "monitoring"}]}
        raise ValueError(f"Unknown card {item['card']}")

    def sections(view: dict[str, Any]) -> list[dict[str, Any]]:
        result = []
        for heading, kinds in SECTIONS:
            items = [item for item in view["cards"] if item["card"] in kinds]
            items.sort(key=lambda item: item["card"] != "alarm")
            if items:
                result.append({"type": "grid", "cards": [{"type": "heading", "heading": heading},
                                                         *(card(item) for item in items)]})
        return result

    return yaml.safe_dump(
        {
            "title": "Digital Twin",
            "views": [{"title": view["title"], "path": _slug(view["title"]) or "view", "type": "sections",
                       "max_columns": 3, "sections": sections(view)} for view in views],
        },
        sort_keys=False,
        allow_unicode=True,
    )
