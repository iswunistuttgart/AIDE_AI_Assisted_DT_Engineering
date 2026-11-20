"""A deployment removes the watchdogs of earlier deployments from Camunda, so they no longer start
next to the deployed ones. See the README (Tests) for the command that runs these tests."""

from __future__ import annotations

import os
import tempfile
import unittest
from typing import Any
from unittest import mock

os.environ.setdefault("DT_WORKSPACE", tempfile.mkdtemp(prefix="aide-test-"))

from app import deploy  # noqa: E402


class Response:
    def __init__(self, status: int, body: dict[str, Any] | None = None) -> None:
        self.status_code, self.ok, self._body = status, status < 400, body or {}
        self.content, self.text = b"{}", ""

    def json(self) -> dict[str, Any]:
        return self._body


class FakeCamunda:
    def __init__(self) -> None:
        self.definitions = [
            {"processDefinitionId": "Watchdog_ErrorState", "processDefinitionKey": 1, "version": 1},
            {"processDefinitionId": "Watchdog_ErrorState", "processDefinitionKey": 2, "version": 2},
            {"processDefinitionId": "Watchdog_RobotError", "processDefinitionKey": 3, "version": 1},
            {"processDefinitionId": "Watchdog_SafeStop", "processDefinitionKey": 4, "version": 1},
            {"processDefinitionId": "OtherProcess", "processDefinitionKey": 5, "version": 1},
        ]
        self.instances = [
            {"processInstanceKey": 10, "processDefinitionKey": 1},
            {"processInstanceKey": 11, "processDefinitionKey": 4, "parentProcessInstanceKey": 10},
            {"processInstanceKey": 12, "processDefinitionKey": 2},
        ]
        self.calls: list[str] = []
        self.reject = False

    def post(self, url: str, json: dict[str, Any] | None = None, timeout: float = 0) -> Response:
        path = url.removeprefix(deploy.CAMUNDA_REST_URL)
        self.calls.append(path)
        if path == "/process-definitions/search":
            return Response(200, {"items": self.definitions, "page": {}})
        if path == "/process-instances/search":
            key = json["filter"]["processDefinitionKey"]
            return Response(200, {"items": [i for i in self.instances if i["processDefinitionKey"] == key]})
        if path in {"/process-instances/10/cancellation", "/resources/3/deletion"}:
            return Response(404)  # The run has just ended; the definition was deleted before.
        if path == "/resources/1/deletion" and self.reject:
            return Response(500)
        return Response(204)


class RetireTest(unittest.TestCase):
    def test_removes_only_watchdogs_of_earlier_deployments(self) -> None:
        camunda = FakeCamunda()
        with mock.patch.object(deploy.requests, "post", camunda.post):
            deploy._retire_watchdogs({"2"})  # The deployment created version 2 of ErrorState.
        cancelled = [c for c in camunda.calls if c.endswith("/cancellation")]
        deleted = [c for c in camunda.calls if c.endswith("/deletion")]
        # The run of the earlier version is cancelled; the called SafeStop ends with its parent,
        # and the run of the deployed version keeps running.
        self.assertEqual(cancelled, ["/process-instances/10/cancellation"])
        self.assertCountEqual(deleted, ["/resources/1/deletion", "/resources/3/deletion",
                                        "/resources/4/deletion"])
        self.assertTrue(all(c.find("/resources/5/") < 0 for c in camunda.calls))

    def test_deployment_without_workflows_removes_all_watchdogs(self) -> None:
        camunda = FakeCamunda()
        with mock.patch.object(deploy.requests, "post", camunda.post):
            deploy._retire_watchdogs(set())
        deleted = [c for c in camunda.calls if c.endswith("/deletion")]
        self.assertCountEqual(deleted, [f"/resources/{key}/deletion" for key in (1, 2, 3, 4)])

    def test_other_camunda_errors_stop_the_deployment(self) -> None:
        camunda = FakeCamunda()
        camunda.reject = True
        with mock.patch.object(deploy.requests, "post", camunda.post), self.assertRaises(RuntimeError):
            deploy._retire_watchdogs({"2"})


if __name__ == "__main__":
    unittest.main()
