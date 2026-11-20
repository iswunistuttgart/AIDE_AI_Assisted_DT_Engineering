"""Runtime adapter of the deployed digital twin.

It subscribes to the bound OPC UA nodes, publishes value changes to Home Assistant
(MQTT) and as BPMN messages (one message per bound value) to the watchdogs in Camunda 8,
publishes a message when the condition of a waiting event-based gateway or boundary event
holds, runs the opcua-write tasks and reports escalations. Every write to the machine goes
through a validated binding, including its unit conversion, value mapping or method call.
As the data manager of the DT, it records every converted value change in SQLite.
An attribute that the engineer marked as unbound never gets a value; the adapter reports it as
unavailable on dt/unbound/<attribute> and rejects every read or write of it.
"""

from __future__ import annotations

import asyncio
import json
import os
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import paho.mqtt.client as mqtt
import requests
from asyncua import Client, ua

RUNTIME = Path(os.getenv("DT_RUNTIME_DIR", "/runtime"))
DEPLOYMENT = RUNTIME / "deployment.json"
STATUS = RUNTIME / "status.json"
RECORDER = RUNTIME / "recorder.sqlite"
CAMUNDA = os.getenv("CAMUNDA_REST_URL", "http://orchestration:8080/v2").rstrip("/")
AVAILABILITY = "dt/adapter/availability"
JOB_TYPES = ("watchdog-read", "opcua-write", "watchdog-escalate")
# A watchdog shows its last reaction this long after its run ended, then it returns to monitoring.
STATE_HOLD_SECONDS = 30
# Start messages correlate with one constant key, so each watchdog has at most one active instance.
START_CORRELATION_KEY = "dt"

VARIANTS = {name: getattr(ua.VariantType, name) for name in (
    "Boolean", "SByte", "Byte", "Int16", "UInt16", "Int32", "UInt32", "Int64", "UInt64",
    "Float", "Double", "String")}
INTEGERS = {"SByte", "Byte", "Int16", "UInt16", "Int32", "UInt32", "Int64", "UInt64"}
OPERATORS = {
    ">": lambda a, b: a > b, ">=": lambda a, b: a >= b, "<": lambda a, b: a < b,
    "<=": lambda a, b: a <= b, "==": lambda a, b: a == b, "!=": lambda a, b: a != b,
}


def cast(type_name: str, value: Any) -> Any:
    if value is None:
        return None
    if type_name == "boolean":
        return bool(value)
    if type_name in {"int", "long"}:
        return int(round(value))
    if type_name in {"float", "double"}:
        return float(value)
    return str(value)


def to_attribute(binding: dict[str, Any], raw: Any) -> Any:
    """Converts a node value into the value of the required attribute."""
    if "valueMap" in binding:
        inverse = {number: name == "true" for name, number in binding["valueMap"].items()}
        return inverse.get(raw)  # An undocumented integer state has no Boolean meaning.
    value = raw
    if "scale" in binding:
        # The factors follow the data flow: node to attribute for reads, attribute to node for writes.
        if binding["access"] == "read":
            value = raw * binding["scale"] + binding["offset"]
        else:
            value = (raw - binding["offset"]) / binding["scale"]
        # Rounding removes the floating-point residue of the conversion, e.g. 308.18 K -> 35.03 °C.
        value = round(value, 6)
    return cast(binding["type"], value)


def to_node(binding: dict[str, Any], value: Any) -> ua.Variant:
    """Converts an attribute value into the value written to the node."""
    if "valueMap" in binding:
        value = binding["valueMap"][str(bool(value)).lower()]
    if "scale" in binding:
        value = value * binding["scale"] + binding["offset"]
    data_type = binding["dataType"]
    if data_type in INTEGERS:
        value = int(round(value))
    elif data_type in {"Float", "Double"}:
        value = float(value)
    elif data_type == "Boolean":
        value = bool(value)
    else:
        value = str(value)
    return ua.Variant(value, VARIANTS[data_type])


def holds(condition: list[list[dict[str, Any]]], values: dict[str, Any]) -> bool:
    """Evaluates a condition given as a disjunction of conjunctions."""
    def compare(term: dict[str, Any]) -> bool:
        value = values.get(term["attribute"])
        return value is not None and OPERATORS[term["op"]](value, term["value"])
    return any(all(compare(term) for term in conjunction) for conjunction in condition)


def variable(key: str) -> str:
    return key.replace(".", "_")


def camunda(path: str, body: dict[str, Any]) -> Any:
    response = requests.post(f"{CAMUNDA}{path}", json=body, timeout=15)
    response.raise_for_status()
    return response.json() if response.content else None


class Unavailable(LookupError):
    """A value of an attribute that the engineer marked as unbound was requested."""


def mqtt_password() -> str:
    path = os.getenv("MQTT_PASSWORD_FILE")
    return Path(path).read_text(encoding="utf-8").strip() if path else os.getenv("MQTT_PASSWORD", "")


class Session:
    """One run of the adapter for one deployment."""

    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self.bindings = {b["attribute"]: b for b in config["bindings"]}
        # Attributes that the engineer marked as unbound: no node provides them, so they never get a
        # value, not even a default; services see them as unavailable on dt/unbound/<attribute>.
        self.unbound = {u["attribute"]: u.get("reason", "") for u in config.get("unbound", [])}
        self.workflows = {w["processId"]: w for w in config["workflows"]}
        self.values: dict[str, Any] = {}
        self.disabled: dict[str, str] = {}
        self.rule_states = {pid: "monitoring" for pid in self.workflows}
        self.rule_changed = {pid: 0.0 for pid in self.workflows}
        self.escalations = {pid: 0 for pid in self.workflows}
        self.nodes: dict[str, Any] = {}
        # Several attributes may be bound to one node, for example a dashboard attribute and the
        # attribute that a watchdog reads; every one of them receives the value of the node.
        self.keys_by_node: dict[Any, list[str]] = {}
        self.connected = False
        self.last_error: str | None = None
        self.loop = asyncio.get_running_loop()
        self.messages: asyncio.Queue[tuple[str, str]] = asyncio.Queue()
        self.mqtt = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="dt-adapter")
        self.recorder = sqlite3.connect(RECORDER)
        self.recorder.execute("CREATE TABLE IF NOT EXISTS value_changes "
                              "(time TEXT NOT NULL, attribute TEXT NOT NULL, value TEXT)")

    # Home Assistant over MQTT

    def start_mqtt(self) -> None:
        self.mqtt.username_pw_set(os.getenv("MQTT_USERNAME", "adapter"), mqtt_password())
        self.mqtt.will_set(AVAILABILITY, "offline", retain=True)
        self.mqtt.on_connect = self._on_connect
        self.mqtt.on_message = lambda client, data, message: self.loop.call_soon_threadsafe(
            self.messages.put_nowait, (message.topic, message.payload.decode("utf-8")))
        self.mqtt.connect(os.getenv("MQTT_HOST", "mosquitto"), int(os.getenv("MQTT_PORT", "1883")))
        self.mqtt.loop_start()

    def _on_connect(self, client: mqtt.Client, data: Any, flags: Any, reason: Any, properties: Any) -> None:
        client.subscribe("dt/command/#", 1)
        for entity in self.config["entities"]:
            client.publish(self._discovery_topic(entity), json.dumps(self._discovery(entity)), retain=True)
        for key, reason in self.unbound.items():
            # Removes a value retained from an earlier deployment, so no stale value stands in for it.
            client.publish(f"dt/state/{key}", "", retain=True)
            client.publish(f"dt/unbound/{key}", reason or "unbound", retain=True)
        client.publish(AVAILABILITY, "online", retain=True)
        for pid, state in self.rule_states.items():
            client.publish(f"dt/watchdog/{pid}", state, retain=True)

    @staticmethod
    def _discovery_topic(entity: dict[str, Any]) -> str:
        return f"homeassistant/{entity['component']}/{entity['uniqueId']}/config"

    @staticmethod
    def _discovery(entity: dict[str, Any]) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "name": entity["name"],
            "unique_id": entity["uniqueId"],
            "default_entity_id": entity["entityId"],
            "availability_topic": AVAILABILITY,
            "device": {"identifiers": ["dt_digital_twin"], "name": "Digital Twin"},
        }
        if entity["component"] == "button":
            payload["payload_press"] = entity["payloadPress"]
        elif not entity.get("optimistic"):
            payload["state_topic"] = entity["stateTopic"]
        if entity.get("unit"):
            payload["unit_of_measurement"] = entity["unit"]
        if entity["component"] == "sensor" and entity.get("type") in {"int", "long", "float", "double"}:
            # A measurement is numeric for Home Assistant even without a unit, so gauges and
            # history graphs show a value and a curve instead of a text state.
            payload["state_class"] = "measurement"
        if "commandTopic" in entity:
            payload["command_topic"] = entity["commandTopic"]
        if entity.get("optimistic"):
            payload["optimistic"] = True
        if entity["component"] == "number":
            payload |= {"min": entity["min"], "max": entity["max"], "step": entity["step"], "mode": "box"}
        return payload

    def publish_state(self, key: str, value: Any) -> None:
        if isinstance(value, bool):
            payload = "ON" if value else "OFF"
        else:
            payload = "unknown" if value is None else str(value)
        self.mqtt.publish(f"dt/state/{key}", payload, retain=True)

    def set_rule_state(self, pid: str, state: str) -> None:
        self.rule_states[pid] = state
        self.rule_changed[pid] = time.monotonic()
        self.mqtt.publish(f"dt/watchdog/{pid}", state[:250], retain=True)
        self.write_status()

    def stop_mqtt(self, remove_entities: bool) -> None:
        if remove_entities:
            for entity in self.config["entities"]:
                self.mqtt.publish(self._discovery_topic(entity), "", retain=True)
            for key in self.unbound:
                self.mqtt.publish(f"dt/unbound/{key}", "", retain=True)
        self.mqtt.publish(AVAILABILITY, "offline", retain=True).wait_for_publish(2)
        self.mqtt.loop_stop()
        self.mqtt.disconnect()

    def write_status(self) -> None:
        status = {
            "updatedAt": datetime.now(timezone.utc).isoformat(),
            "deployedAt": self.config["deployedAt"],
            "endpoint": self.config["endpoint"],
            "connected": self.connected,
            "disabled": self.disabled,
            "unbound": self.unbound,
            "rules": self.rule_states,
            "escalations": self.escalations,
            "lastError": self.last_error,
        }
        temporary = STATUS.with_name("status.json.tmp")
        temporary.write_text(json.dumps(status, indent=2), encoding="utf-8")
        os.replace(temporary, STATUS)

    # OPC UA

    async def run(self, deployment_changed) -> None:
        self.start_mqtt()
        async with Client(self.config["endpoint"], timeout=10) as client:
            await self._resolve_nodes(client)
            subscription = await client.create_subscription(500, self)
            # One monitored item per node, even if several attributes are bound to it.
            readable = list({self.nodes[k].nodeid: self.nodes[k] for k, b in self.bindings.items()
                             if b["readable"] and k not in self.disabled}.values())
            if readable:
                await subscription.subscribe_data_change(readable)
            self.connected = True
            self.write_status()
            tasks = [asyncio.create_task(t) for t in (self._jobs(), self._waits(), self._messages())]
            try:
                while not deployment_changed():
                    await client.check_connection()
                    if any(task.done() for task in tasks):
                        for task in tasks:
                            if task.done():
                                task.result()
                    await asyncio.sleep(2)
            finally:
                for task in tasks:
                    task.cancel()
                self.connected = False

    async def _resolve_nodes(self, client: Client) -> None:
        """Checks that every bound node exists on the server with the data type of the NodeSet."""
        namespaces: dict[str, int] = {}
        for key, binding in self.bindings.items():
            try:
                uri = binding["namespaceUri"]
                if uri not in namespaces:
                    namespaces[uri] = await client.get_namespace_index(uri)
                node = client.get_node(ua.NodeId.from_string(f"ns={namespaces[uri]};{binding['identifier']}"))
                if binding["nodeClass"] == "Method":
                    if await node.read_node_class() != ua.NodeClass.Method:
                        raise ValueError("node is not a method")
                    object_uri = binding["objectNamespaceUri"]
                    if object_uri not in namespaces:
                        namespaces[object_uri] = await client.get_namespace_index(object_uri)
                    binding["_object"] = client.get_node(ua.NodeId.from_string(
                        f"ns={namespaces[object_uri]};{binding['objectIdentifier']}"))
                else:
                    data_type = await node.read_data_type()
                    name = ua.ObjectIdNames.get(data_type.Identifier) if data_type.NamespaceIndex == 0 else None
                    if name != binding["dataType"]:
                        raise ValueError(f"server data type {name or data_type} differs from {binding['dataType']}")
                self.nodes[key] = node
                if binding["readable"]:
                    self.keys_by_node.setdefault(node.nodeid, []).append(key)
            except Exception as exc:
                self.disabled[key] = f"binding disabled: {exc}"
                print(f"{key}: {self.disabled[key]}", flush=True)

    def datachange_notification(self, node: Any, value: Any, data: Any) -> None:
        for key in self.keys_by_node.get(node.nodeid, []):
            self.loop.create_task(self.on_value(key, to_attribute(self.bindings[key], value)))

    async def on_value(self, key: str, value: Any) -> None:
        self.values[key] = value
        self.publish_state(key, value)
        with self.recorder:
            self.recorder.execute("INSERT INTO value_changes VALUES (?, ?, ?)",
                                  (datetime.now(timezone.utc).isoformat(), key, json.dumps(value)))
        if any(key in workflow["triggers"] for workflow in self.workflows.values()):
            # The message of this value starts every watchdog that it triggers.
            variables = {variable(k): v for k, v in self.values.items()}
            await self._publish(f"changed-{key}", START_CORRELATION_KEY, variables, 0)

    def _variables(self, workflow: dict[str, Any]) -> dict[str, Any]:
        """Current values of everything the workflow reads."""
        return {variable(k): self.values.get(k) for k in workflow["reads"]}

    def unavailable(self, key: str) -> Unavailable:
        reason = self.unbound[key]
        return Unavailable(f"{key} is unbound{f' ({reason})' if reason else ''}: no node provides it, "
                           "so it has no value and cannot be set")

    async def write(self, key: str, value: Any) -> None:
        if key in self.unbound:
            raise self.unavailable(key)
        binding = self.bindings[key]
        if key in self.disabled:
            raise RuntimeError(f"{key}: {self.disabled[key]}")
        if "callWhen" in binding:
            # A parameterless method expresses only the value that the engineer confirmed.
            if bool(value) != binding["callWhen"]:
                raise ValueError(f"{key} is bound to a method without argument and can only be set to "
                                 f"{str(binding['callWhen']).lower()}")
            await binding["_object"].call_method(self.nodes[key].nodeid)
            print(f"called {key}", flush=True)
            return
        variant = to_node(binding, value)
        if binding["nodeClass"] == "Method":
            await binding["_object"].call_method(self.nodes[key].nodeid, variant)
        else:
            await self.nodes[key].write_attribute(ua.AttributeIds.Value, ua.DataValue(variant))
        print(f"wrote {key} = {value} ({variant.Value})", flush=True)

    # Camunda 8

    async def _publish(self, name: str, correlation_key: str, variables: dict[str, Any], ttl: int) -> None:
        body = {"name": name, "correlationKey": correlation_key, "timeToLive": ttl, "variables": variables}
        try:
            await asyncio.to_thread(camunda, "/messages/publication", body)
        except requests.RequestException as exc:
            self.last_error = f"message {name} not published: {exc}"
            self.write_status()

    async def _jobs(self) -> None:
        while True:
            for job_type in JOB_TYPES:
                try:
                    body = {"type": job_type, "worker": "dt-adapter", "timeout": 30000,
                            "maxJobsToActivate": 10, "requestTimeout": 500}
                    jobs = (await asyncio.to_thread(camunda, "/jobs/activation", body) or {}).get("jobs", [])
                except requests.RequestException as exc:
                    self.last_error = f"job activation failed: {exc}"
                    await asyncio.sleep(2)
                    continue
                for job in jobs:
                    await self._handle(job_type, job)
            await asyncio.sleep(0.5)

    async def _handle(self, job_type: str, job: dict[str, Any]) -> None:
        headers = job.get("customHeaders") or {}
        key = job["jobKey"]
        try:
            variables: dict[str, Any] = {}
            pid = job["processDefinitionId"]
            if job_type == "watchdog-read":
                keys = headers["attributes"].split(",")
                if missing := next((k for k in keys if k in self.unbound), None):
                    # The job fails visibly instead of passing a missing value as null to the watchdog.
                    raise self.unavailable(missing)
                variables = {variable(k): self.values.get(k) for k in keys}
            elif job_type == "opcua-write":
                attribute = headers["attribute"]
                if self.bindings[attribute]["nodeId"] != headers["binding"]:
                    raise ValueError(f"{attribute} is bound to {self.bindings[attribute]['nodeId']}, "
                                     f"not to {headers['binding']}")
                # A threshold table (business rule task) stores its result in a process variable.
                value = ((job.get("variables") or {}).get(headers["variable"]) if "variable" in headers
                         else json.loads(headers["value"]))
                await self.write(attribute, value)
                self.set_rule_state(pid, f"reacted: set {attribute} = {value}")
            else:
                # Execution listener of an escalation end event.
                escalation = next(e for e in self.workflows[pid]["escalations"] if e["element"] == job["elementId"])
                self.escalations[pid] += 1
                self.set_rule_state(pid, "escalated: " + escalation["message"])
            await asyncio.to_thread(camunda, f"/jobs/{key}/completion", {"variables": variables})
        except Exception as exc:
            self.last_error = f"{job_type} failed: {exc}"
            self.write_status()
            await asyncio.to_thread(camunda, f"/jobs/{key}/failure",
                                    {"errorMessage": str(exc)[:500], "retries": 0})

    async def _waits(self) -> None:
        """Publishes the message of a waiting event-based gateway or boundary event once its condition holds,
        and reports user tasks that wait for the operator."""
        while True:
            await asyncio.sleep(1)
            body = {"filter": {"state": "ACTIVE"}, "page": {"limit": 200}}
            try:
                result = await asyncio.to_thread(camunda, "/element-instances/search", body)
            except requests.RequestException as exc:
                self.last_error = f"element instance search failed: {exc}"
                continue
            active = result["items"]
            if not (result.get("page") or {}).get("hasMoreTotalItems"):
                self.reset_ended({item["processDefinitionId"] for item in active})
            for item in active:
                workflow = self.workflows.get(item["processDefinitionId"])
                if workflow is None:
                    continue
                pid = workflow["processId"]
                for wait in workflow["waits"]:
                    if wait["element"] == item["elementId"] and holds(wait["condition"], self.values):
                        await self._publish(wait["message"], pid, self._variables(workflow), 0)
                        # A met condition is only news after a reaction, such as the cooling down after a stop;
                        # otherwise it is the normal path, such as a move that completes in time, and no alarm.
                        if self.rule_states[pid] != "monitoring":
                            self.set_rule_state(pid, f"condition of {wait['element']} met")
                for task in workflow["userTasks"]:
                    state = f"waiting for operator: {task['question']}"
                    if task["element"] == item["elementId"] and self.rule_states[pid] != state:
                        self.set_rule_state(pid, state)

    def reset_ended(self, running: set[str], now: float | None = None) -> None:
        """Returns a watchdog to monitoring once no run of it is active and its last state is older than
        STATE_HOLD_SECONDS, so the alarm card of the dashboard disappears after the deviation."""
        now = time.monotonic() if now is None else now
        for pid, state in self.rule_states.items():
            if state != "monitoring" and pid not in running and now - self.rule_changed[pid] >= STATE_HOLD_SECONDS:
                self.set_rule_state(pid, "monitoring")

    async def _messages(self) -> None:
        """Handles commands from Home Assistant."""
        while True:
            topic, payload = await self.messages.get()
            parts = topic.split("/", 2)
            if len(parts) < 3:
                continue
            _, kind, key = parts
            try:
                if kind == "command" and key in self.unbound:
                    raise self.unavailable(key)
                if kind == "command" and key in self.bindings:
                    await self.write(key, self._parse(self.bindings[key]["type"], payload))
            except Exception as exc:
                self.last_error = f"{topic}: {exc}"
                self.write_status()

    @staticmethod
    def _parse(type_name: str, payload: str) -> Any:
        if type_name == "boolean":
            return payload.strip().upper() in {"ON", "TRUE", "1"}
        if type_name in {"int", "long"}:
            return int(float(payload))
        if type_name in {"float", "double"}:
            return float(payload)
        return payload


async def main() -> None:
    while True:
        if not DEPLOYMENT.exists():
            await asyncio.sleep(2)
            continue
        modified = DEPLOYMENT.stat().st_mtime
        session = Session(json.loads(DEPLOYMENT.read_text(encoding="utf-8")))
        changed = lambda: not DEPLOYMENT.exists() or DEPLOYMENT.stat().st_mtime != modified
        try:
            await session.run(changed)
        except Exception as exc:
            session.connected = False
            session.last_error = f"{type(exc).__name__}: {exc}"
            session.write_status()
            print(f"Session ended: {session.last_error}; retrying.", flush=True)
            await asyncio.sleep(5)
        finally:
            session.recorder.close()
            try:
                session.stop_mqtt(remove_entities=changed())
            except Exception:
                pass


if __name__ == "__main__":
    asyncio.run(main())
