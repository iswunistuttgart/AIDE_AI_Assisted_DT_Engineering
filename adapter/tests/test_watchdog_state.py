"""The watchdog state that the dashboard shows returns to monitoring after a run has ended. See
the README (Tests) for the command that runs these tests."""

from __future__ import annotations

import asyncio
import json
import os
import tempfile
import unittest
from unittest import mock

os.environ.setdefault("DT_RUNTIME_DIR", tempfile.mkdtemp(prefix="aide-adapter-test-"))

import adapter  # noqa: E402
from tests.test_unbound import CONFIG, FakeMqtt  # noqa: E402

PID = "Watchdog_MotorOverheating"


class WatchdogStateReset(unittest.IsolatedAsyncioTestCase):

    async def asyncSetUp(self) -> None:
        config = json.loads(json.dumps(CONFIG))
        config["workflows"] = [{"processId": PID, "reads": ["Robot.state"], "escalations": [], "userTasks": [], "waits": [
            {"element": "wait", "message": f"{PID}-wait",
             "condition": [[{"attribute": "Robot.state", "op": "==", "value": "idle"}]]}]}]
        self.session = adapter.Session(config)
        self.session.mqtt = FakeMqtt()
        self.session.set_rule_state(PID, "reacted: set Robot.stop = True")
        self.changed = self.session.rule_changed[PID]

    async def asyncTearDown(self) -> None:
        self.session.recorder.close()

    async def test_returns_to_monitoring_after_the_run_ended(self) -> None:
        self.session.reset_ended(set(), now=self.changed + adapter.STATE_HOLD_SECONDS)
        self.assertEqual(self.session.rule_states[PID], "monitoring")
        self.assertIn((f"dt/watchdog/{PID}", "monitoring", True), self.session.mqtt.published)

    async def test_keeps_the_reaction_visible_for_the_hold_time(self) -> None:
        self.session.reset_ended(set(), now=self.changed + adapter.STATE_HOLD_SECONDS - 1)
        self.assertEqual(self.session.rule_states[PID], "reacted: set Robot.stop = True")

    async def test_keeps_the_state_while_a_run_is_active(self) -> None:
        self.session.reset_ended({PID}, now=self.changed + 3600)
        self.assertEqual(self.session.rule_states[PID], "reacted: set Robot.stop = True")


    async def one_wait_check(self) -> None:
        """Runs one pass of the wait loop while the wait element is active and its condition holds."""
        self.session.values["Robot.state"] = "idle"
        active = {"items": [{"processDefinitionId": PID, "elementId": "wait"}], "page": {"hasMoreTotalItems": True}}
        sleeps = iter([None])

        async def sleep(_):
            if next(sleeps, "stop") == "stop":
                raise asyncio.CancelledError

        with mock.patch.object(adapter, "camunda", return_value=active),                 mock.patch.object(adapter.asyncio, "sleep", sleep),                 mock.patch.object(self.session, "_publish", mock.AsyncMock()) as publish:
            with self.assertRaises(asyncio.CancelledError):
                await self.session._waits()
        publish.assert_awaited_once()

    async def test_a_met_condition_without_a_reaction_raises_no_alarm(self) -> None:
        self.session.set_rule_state(PID, "monitoring")
        await self.one_wait_check()
        self.assertEqual(self.session.rule_states[PID], "monitoring")

    async def test_a_met_condition_after_a_reaction_is_reported(self) -> None:
        await self.one_wait_check()
        self.assertEqual(self.session.rule_states[PID], "condition of wait met")


if __name__ == "__main__":
    unittest.main()
