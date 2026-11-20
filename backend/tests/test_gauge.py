"""Colors of the gauge ranges in the Home Assistant dashboard. See the README (Tests) for the command that runs
these tests."""

from __future__ import annotations

import os
import tempfile
import unittest

import yaml

os.environ.setdefault("DT_WORKSPACE", tempfile.mkdtemp(prefix="aide-test-"))

from app import deploy  # noqa: E402


def gauge_severity(minimum: float, warning: float, critical: float) -> dict[str, float]:
    binding = {"attribute": "Tool.remainingLife", "access": "read", "type": "double", "unit": "%"}
    entities = deploy._entities([binding], [], {})
    view = {"title": "Tool", "cards": [{"card": "gauge", "attributes": ["Tool.remainingLife"],
                                       "min": minimum, "max": 100, "severity": [warning, critical]}]}
    text = deploy._dashboard([view], entities)
    found = []

    def walk(node):
        if isinstance(node, dict):
            if node.get("type") == "gauge":
                found.append(node["severity"])
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(yaml.safe_load(text))
    return found[0]


class GaugeColors(unittest.TestCase):

    def test_high_values_are_red_when_the_critical_value_is_above_the_warning_value(self) -> None:
        # Spindle temperature: green from 0, yellow from 70, red from 85.
        self.assertEqual(gauge_severity(0, 70, 85), {"green": 0, "yellow": 70, "red": 85})

    def test_low_values_are_red_when_the_critical_value_is_below_the_warning_value(self) -> None:
        # Remaining tool life: red from 0, yellow from 5, green from 10.
        self.assertEqual(gauge_severity(0, 10, 5), {"red": 0, "yellow": 5, "green": 10})


if __name__ == "__main__":
    unittest.main()
