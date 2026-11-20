"""OPC UA simulation server built from a NodeSet2 file.

The simulation file adds behavior: measurement noise and drift, method effects, effects of
client writes, and timed scenarios. NodeIds in the simulation file use the namespace
indexes of the NodeSet file. In a method effect, {0} in a NodeId stands for the first
argument of the call, and "when" makes the effect depend on the current value of a node
(equals or notEquals). "occupancy" lists methods that occupy a place given by their
arguments: a call for an occupied place is rejected with BadInvalidState and applies the
"rejected" effects, and a "release" method frees all places. A derived node takes the value of its first rule whose conditions all
hold, otherwise its default.

AIDE selects a scenario by writing select.json into SIM_CONTROL_DIR.
The simulator then resets every variable to its NodeSet value and runs the scenario.
"""

import asyncio
import json
import os
import random
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import Any

from asyncua import Server, ua, uamethod

ENDPOINT = os.getenv("OPCUA_SIMULATOR_ENDPOINT", "opc.tcp://0.0.0.0:4840/robot-cell/")
NODESET = Path(os.getenv("OPCUA_NODESET_FILE", "/model/nodeset.xml"))
SIMULATION = Path(os.getenv("OPCUA_SIMULATION_FILE", "/model/simulation.json"))
CONTROL = Path(os.getenv("SIM_CONTROL_DIR", "/scenario"))
UA = "{http://opcfoundation.org/UA/2011/03/UANodeSet.xsd}"


class Simulator:
    def __init__(self) -> None:
        self.server = Server()
        self.config: dict[str, Any] = json.loads(SIMULATION.read_text(encoding="utf-8"))
        self.scenario: dict[str, Any] = {}
        self.selection: str | None = None
        self.ignore_effects = False
        self.namespaces: dict[str, int] = {}
        # Noise varies a node around its level; scenarios and effects change the level.
        self.levels: dict[str, float] = {}
        self.tasks: set[asyncio.Task[Any]] = set()
        self.effects: set[asyncio.Task[Any]] = set()  # Pending effects and scenario steps.
        root = ElementTree.parse(NODESET).getroot()
        self.writable = [e.get("NodeId") for e in root.iter(f"{UA}UAVariable")
                         if int(e.get("AccessLevel", "1")) & 2]
        self.methods = [e.get("NodeId") for e in root.iter(f"{UA}UAMethod")]
        # Variables with a value in the NodeSet, without properties such as EngineeringUnits.
        self.variables = [e.get("NodeId") for e in root.iter(f"{UA}UAVariable")
                          if e.find(f"{UA}Value") is not None and not e.get("BrowseName", "").endswith(
                              ("EngineeringUnits", "InputArguments", "OutputArguments"))]
        self.initial: dict[str, Any] = {}
        self.own: dict[str, Any] = {}  # The last value that the simulator itself wrote to a node.
        self.occupied: set[str] = set()  # Places occupied by an "occupancy" method.
        self.scenario_task: asyncio.Task[Any] | None = None

    def node(self, node_id: str):
        """Translates a NodeSet NodeId (ns=1;...) into the server's namespace index."""
        if node_id.startswith("ns="):
            index, rest = node_id[3:].split(";", 1)
            node_id = f"ns={self.namespaces[index]};{rest}"
        return self.server.get_node(ua.NodeId.from_string(node_id))

    async def start(self) -> None:
        await self.server.init()
        self.server.set_endpoint(ENDPOINT)
        self.server.set_server_name("DT Machine Simulator")
        self.server.set_security_policy([ua.SecurityPolicyType.NoSecurity])
        await self.server.import_xml(str(NODESET))
        uris = [uri.text for uri in ElementTree.parse(NODESET).getroot().iter(f"{UA}Uri")]
        for position, uri in enumerate(uris, start=1):
            self.namespaces[str(position)] = await self.server.get_namespace_index(uri)
        for method_id in self.methods:
            effects = self.config.get("methods", {}).get(method_id, [])
            self.server.link_method(self.node(method_id), self._method(method_id, effects))
        for node_id in self.variables:
            self.initial[node_id] = await self.node(node_id).read_value()
        await self.server.start()
        print(f"Simulator listening on {ENDPOINT}", flush=True)

    def _method(self, method_id: str, effects: list[dict[str, Any]]):
        @uamethod
        async def call(parent: Any, *arguments: Any) -> None:
            values = [a.Value if isinstance(a, ua.Variant) else a for a in arguments]
            self._record("call", method_id, values)
            if self.ignore_effects:
                return None
            occupancy = self.config.get("occupancy", {})
            place = occupancy.get("occupy", {}).get(method_id)
            place = place.format(*values) if place else None
            if place in self.occupied:
                print(f"rejected {method_id} {values}: {place} is occupied", flush=True)
                await self._apply(occupancy.get("rejected", []), values)
                return ua.StatusCode(ua.StatusCodes.BadInvalidState)
            if await self._apply(effects, values):
                if place:
                    self.occupied.add(place)
                if method_id in occupancy.get("release", []):
                    self.occupied.clear()
            return None
        return call

    def _record(self, kind: str, node_id: str, value: Any) -> None:
        print(f"{kind} {node_id} {value}", flush=True)

    async def _apply(self, effects: list[dict[str, Any]], arguments: list[Any]) -> bool:
        """Applies the effects whose conditions hold and tells whether any applied."""
        applied = False
        for effect in effects:
            when = effect.get("when")
            if when:
                current = await self.node(when["node"].format(*arguments)).read_value()
                if ("equals" in when and current != when["equals"]) or \
                        ("notEquals" in when and current == when["notEquals"]):
                    continue
            value = arguments[effect["argument"]] if "argument" in effect else effect["value"]
            self._effect(effect["node"].format(*arguments), value, effect.get("afterSeconds", 0))
            applied = True
        return applied

    def _effect(self, node_id: str, value: Any, delay: float) -> None:
        task = asyncio.create_task(self._set(node_id, value, delay))
        self.effects.add(task)
        task.add_done_callback(self.effects.discard)

    async def _set(self, node_id: str, value: Any, delay: float) -> None:
        await asyncio.sleep(delay)
        node = self.node(node_id)
        variant_type = await node.read_data_type_as_variant_type()
        await node.write_value(ua.Variant(value, variant_type))
        self.own[node_id] = value
        if node_id in self.levels:
            self.levels[node_id] = value
        print(f"{node_id} = {value}", flush=True)

    async def _reset(self) -> None:
        """Cancels pending effects and scenario steps and restores the NodeSet values."""
        for task in list(self.effects):
            task.cancel()
        self.occupied.clear()
        for node_id, value in self.initial.items():
            await self._set(node_id, value, 0)

    async def _noise(self) -> None:
        """Every period, moves each drifting node's level by its rate (down to 0 at the lowest),
        e.g. the wear of a tool, and varies each noisy node around its level."""
        noise = self.config.get("noise", {})
        amplitudes: dict[str, float] = noise.get("nodes", {})
        drift: dict[str, float] = noise.get("drift", {})
        for node_id in [*amplitudes, *drift]:
            self.levels[node_id] = await self.node(node_id).read_value()
        while amplitudes or drift:
            await asyncio.sleep(noise.get("periodSeconds", 10))
            for node_id in dict.fromkeys([*drift, *amplitudes]):
                if self.levels[node_id] == 0:  # Stopped, e.g. the spindle after an emergency stop.
                    continue
                if node_id in drift:
                    self.levels[node_id] = max(0.0, round(self.levels[node_id] + drift[node_id], 6))
                node = self.node(node_id)
                variant_type = await node.read_data_type_as_variant_type()
                amplitude = amplitudes.get(node_id, 0)
                value = self.levels[node_id] + random.uniform(-amplitude, amplitude)
                value = round(value) if variant_type == ua.VariantType.Int32 else round(value, 2)
                await node.write_value(ua.Variant(value, variant_type))
                self.own[node_id] = value

    async def _scenario(self) -> None:
        await self._reset()
        period = self.scenario.get("repeatEverySeconds")
        while True:
            for step in self.scenario.get("steps", []):
                self._effect(step["node"], step["value"], step["atSeconds"])
            if not period:
                return
            await asyncio.sleep(period)

    def _start_scenario(self) -> None:
        if self.scenario_task:
            self.scenario_task.cancel()
        self.ignore_effects = bool(self.scenario.get("ignoreEffects"))
        self.scenario_task = self._spawn(self._scenario())

    async def _control(self) -> None:
        """Follows the scenario selection (select.json)."""
        select = CONTROL / "select.json"
        selection = json.loads(select.read_text(encoding="utf-8")) if select.exists() else None
        if selection and selection["id"] != self.selection:
            self.selection = selection["id"]
            self.scenario = self.config.get("scenarios", {}).get(selection["scenario"], {})
            print(f"Scenario {selection['scenario']} selected", flush=True)
            self._start_scenario()

    def _spawn(self, job) -> asyncio.Task[Any]:
        task = asyncio.create_task(job)
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        return task

    async def run(self) -> None:
        """Runs noise and the scenario, records client writes and reacts to them on watched nodes."""
        CONTROL.mkdir(parents=True, exist_ok=True)
        self._spawn(self._noise())
        watched = self.config.get("writes", {})
        nodes = list(dict.fromkeys([*self.writable, *watched]))
        last = {node_id: await self.node(node_id).read_value() for node_id in nodes}
        while True:
            await asyncio.sleep(0.2)
            await self._control()
            for node_id in nodes:
                value = await self.node(node_id).read_value()
                if value == last[node_id]:
                    continue
                last[node_id] = value
                if self.own.get(node_id) == value:
                    continue  # The simulator wrote this value itself.
                self._record("write", node_id, value)
                if not self.ignore_effects:
                    await self._apply(watched.get(node_id, {}).get(str(value), []), [])
            await self._derive()

    async def _derive(self) -> None:
        """Sets each derived node to the value of its first rule whose conditions all hold, as the
        machine's own program would, for example the winner of a game from the board."""
        for derived in self.config.get("derived", []):
            value = derived["otherwise"]
            for rule in derived["rules"]:
                values = [await self.node(c["node"]).read_value() for c in rule["when"]]
                if all(v == c["equals"] if "equals" in c else v != c["notEquals"] for v, c in zip(values, rule["when"])):
                    value = rule["value"]
                    break
            if await self.node(derived["node"]).read_value() != value:
                await self._set(derived["node"], value, 0)


async def main() -> None:
    simulator = Simulator()
    await simulator.start()
    try:
        await simulator.run()
    finally:
        await simulator.server.stop()


if __name__ == "__main__":
    asyncio.run(main())
