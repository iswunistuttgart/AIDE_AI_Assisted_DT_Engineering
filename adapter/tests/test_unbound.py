"""Runtime behaviour of the adapter for attributes that the engineer marked as unbound: they never
get a value, not even a default, and every request for one fails visibly. See the README (Tests) for the
command that runs these tests."""

from __future__ import annotations

import asyncio
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

os.environ["DT_RUNTIME_DIR"] = tempfile.mkdtemp(prefix="aide-adapter-test-")

import adapter  # noqa: E402

BATTERY = "Robot.batteryLevel"
REASON = "only available through getter method (not supported): Cell/Robot/GetBatteryLevel"
CONFIG = {
    "deployedAt": "2026-11-19T00:00:00+00:00",
    "endpoint": "opc.tcp://localhost:4840/",
    "bindings": [{"attribute": "Robot.cycleTime", "type": "double", "unit": "s", "access": "read",
                  "nodeId": "ns=1;s=Cell/Robot/CycleTime", "nodeClass": "Variable", "readable": True,
                  "dataType": "Double", "scale": 0.001, "offset": 0}],
    "unbound": [{"attribute": BATTERY, "reason": REASON}],
    "workflows": [],
    "entities": [],
}


class FakeMqtt:
    def __init__(self) -> None:
        self.published: list[tuple[str, str, bool]] = []

    def publish(self, topic: str, payload: str, retain: bool = False):
        self.published.append((topic, payload, retain))
        return mock.Mock()

    def subscribe(self, *args) -> None:
        pass


class UnboundAtRuntime(unittest.IsolatedAsyncioTestCase):

    async def asyncSetUp(self) -> None:
        self.session = adapter.Session(json.loads(json.dumps(CONFIG)))
        self.mqtt = FakeMqtt()
        self.session.mqtt = self.mqtt

    async def asyncTearDown(self) -> None:
        self.session.recorder.close()

    def published(self, topic: str) -> list[tuple[str, bool]]:
        return [(payload, retain) for t, payload, retain in self.mqtt.published if t == topic]

    async def test_marks_the_value_as_unavailable_and_publishes_no_value(self) -> None:
        self.session._on_connect(self.mqtt, None, None, None, None)
        self.assertEqual(self.published(f"dt/unbound/{BATTERY}"), [(REASON, True)])
        # The only state message removes a retained value of an earlier deployment.
        self.assertEqual(self.published(f"dt/state/{BATTERY}"), [("", True)])
        self.assertNotIn(BATTERY, self.session.values)

    async def test_status_reports_unbound_attributes(self) -> None:
        self.session.write_status()
        status = json.loads(adapter.STATUS.read_text(encoding="utf-8"))
        self.assertEqual(status["unbound"], {BATTERY: REASON})

    async def test_write_fails_instead_of_doing_nothing(self) -> None:
        with self.assertRaisesRegex(adapter.Unavailable, "unbound"):
            await self.session.write(BATTERY, 50.0)

    async def test_command_from_home_assistant_is_reported(self) -> None:
        task = asyncio.create_task(self.session._messages())
        self.session.messages.put_nowait((f"dt/command/{BATTERY}", "50"))
        await asyncio.sleep(0.05)
        task.cancel()
        self.assertIn(f"{BATTERY} is unbound", self.session.last_error)

    async def test_watchdog_read_fails_instead_of_passing_null(self) -> None:
        calls = []
        job = {"jobKey": 1, "processDefinitionId": "Watchdog_X", "elementId": "read",
               "customHeaders": {"attributes": f"Robot.cycleTime,{BATTERY}"}}
        with mock.patch.object(adapter, "camunda", side_effect=lambda path, body: calls.append((path, body))):
            await self.session._handle("watchdog-read", job)
        self.assertEqual([path for path, _ in calls], ["/jobs/1/failure"])
        self.assertIn(f"{BATTERY} is unbound", calls[0][1]["errorMessage"])
        self.assertEqual(calls[0][1]["retries"], 0)

    async def test_bound_values_are_read_as_before(self) -> None:
        calls = []
        self.session.values["Robot.cycleTime"] = 1.5
        job = {"jobKey": 2, "processDefinitionId": "Watchdog_X", "elementId": "read",
               "customHeaders": {"attributes": "Robot.cycleTime"}}
        with mock.patch.object(adapter, "camunda", side_effect=lambda path, body: calls.append((path, body))):
            await self.session._handle("watchdog-read", job)
        self.assertEqual(calls, [("/jobs/2/completion", {"variables": {adapter.variable("Robot.cycleTime"): 1.5}})])


if __name__ == "__main__":
    unittest.main()
