"""Tests of the unbound handling: an attribute that no node can provide is marked as unbound by
the engineer, the approval needs every attribute bound or unbound, and the watchdog and dashboard
checks reject every use of an unbound attribute. They run the MontiCore language tool
(DTLANG_JAR) on the robot-cell example (DT_EXAMPLES_DIR); see the README (Tests) for the command."""

from __future__ import annotations

import os
import re
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

WORKSPACE = Path(tempfile.mkdtemp(prefix="aide-test-"))
os.environ["DT_WORKSPACE"] = str(WORKSPACE / "workspace")
os.environ["DT_RUNTIME_DIR"] = str(WORKSPACE / "runtime")
os.environ["HA_CONFIG_DIR"] = str(WORKSPACE / "ha")
EXAMPLE = Path(os.getenv("DT_EXAMPLES_DIR", "/examples")) / "robot_cell"

from app import binding, dashboard, deploy, languages, watchdog, workspace  # noqa: E402

GETTER = "only available through getter method (not supported)"
BATTERY = "Robot.batteryLevel"
POWER = "Cell.totalPower"
BATTERY_RULE = ("\n5. Battery low: If the charge of the encoder backup battery falls below 20 %, "
                "escalate to the maintenance team.\n")


def load_example(fault_rules: str = "") -> None:
    shutil.rmtree(workspace.WORKSPACE, ignore_errors=True)
    shutil.rmtree(workspace.RUNTIME, ignore_errors=True)
    workspace.write_text(workspace.REQUIREMENTS, (EXAMPLE / "requirements.cd").read_text(encoding="utf-8"))
    workspace.write_text(workspace.NODESET, (EXAMPLE / "robot-cell.nodeset.xml").read_text(encoding="utf-8"))
    workspace.write_text(workspace.FAULT_RULES,
                         (EXAMPLE / "fault-rules.md").read_text(encoding="utf-8") + fault_rules)
    binding.refresh_candidates()


def reference_entries(without_unbound: bool = False) -> dict[str, str]:
    """The entries of the reference binding, by attribute key."""
    entries = {}
    for line in (EXAMPLE / "binding.bind").read_text(encoding="utf-8").splitlines():
        match = re.match(r"\s*([\w.]+)\s+(->|unbound\b)", line)
        if match and not (without_unbound and match.group(2) == "unbound"):
            entries[match.group(1)] = line.strip()
    return entries


def save_reference(without_unbound: bool = False) -> None:
    """The engineer's state after resolving the reference binding of the example. The reference
    binding also binds a workflow requirement, so the reference workflows are saved first."""
    workspace.write_text(workspace.WATCHDOG, (EXAMPLE / "watchdog.wf").read_text(encoding="utf-8"))
    workspace.write_json(workspace.CANDIDATES, languages.candidates())
    entries = reference_entries(without_unbound)
    review = {key: {"reasons": ["The LLM found no fitting candidate."]}
              for key in (BATTERY, POWER) if key not in entries}
    binding._save(entries, review)


def attribute(key: str) -> dict:
    return next(a for a in binding.attributes() if a["key"] == key)


class Candidates(unittest.TestCase):

    def setUp(self) -> None:
        load_example()

    def test_getter_is_shown_as_excluded_but_never_offered(self) -> None:
        battery = attribute(BATTERY)
        excluded = {node["nodeId"]: node["reason"] for node in battery["unsupported"]}
        self.assertEqual(excluded.get("ns=1;s=Cell/Robot/GetBatteryLevel"), GETTER)
        offered = {node["nodeId"] for node in battery["candidates"] + battery["manual"]}
        self.assertNotIn("ns=1;s=Cell/Robot/GetBatteryLevel", offered)

    def test_methods_without_return_value_are_no_getters(self) -> None:
        excluded = {node["nodeId"] for node in attribute(BATTERY)["unsupported"]}
        self.assertNotIn("ns=1;s=Cell/Robot/StopMotion", excluded)
        self.assertEqual(attribute("Robot.stopMotion")["unsupported"], [])


class Approval(unittest.TestCase):

    def setUp(self) -> None:
        load_example()

    def test_open_cases_block_the_approval(self) -> None:
        save_reference(without_unbound=True)
        self.assertEqual(set(binding.open_cases()), {BATTERY, POWER})
        with self.assertRaisesRegex(ValueError, "Robot.batteryLevel"):
            binding.approve()
        self.assertFalse(binding.approved())

    def test_approval_with_unbound_attributes_keeps_them_with_reason(self) -> None:
        save_reference(without_unbound=True)
        binding.mark_unbound(BATTERY, f"{GETTER}: Cell/Robot/GetBatteryLevel")
        binding.mark_unbound(POWER, "")
        self.assertEqual(binding.open_cases(), [])
        binding.approve()
        self.assertTrue(binding.approved())
        text = workspace.read_text(workspace.BINDING)
        self.assertIn(f'{BATTERY} unbound "{GETTER}: Cell/Robot/GetBatteryLevel";', text)
        self.assertIn(f"{POWER} unbound;", text)
        check = workspace.read_json(workspace.BINDING_CHECK)
        self.assertEqual(check["errors"], [])
        self.assertEqual(check["unbound"], {BATTERY: f"{GETTER}: Cell/Robot/GetBatteryLevel", POWER: ""})
        self.assertNotIn(BATTERY, check["resolved"])

    def test_reference_binding_is_complete(self) -> None:
        save_reference()
        self.assertEqual(workspace.read_json(workspace.BINDING_CHECK)["errors"], [])
        self.assertEqual(binding.open_cases(), [])
        binding.approve()
        self.assertTrue(binding.approved())

    def test_reason_with_quotes_stays_one_valid_entry(self) -> None:
        save_reference(without_unbound=True)
        binding.mark_unbound(BATTERY, 'only "GetBatteryLevel()"\nprovides it')
        binding.mark_unbound(POWER)
        check = workspace.read_json(workspace.BINDING_CHECK)
        self.assertEqual(check["errors"], [])
        self.assertEqual(check["unbound"][BATTERY], 'only "GetBatteryLevel()" provides it')

    def test_removing_the_mark_reopens_the_case(self) -> None:
        save_reference()
        binding.remove(BATTERY)
        self.assertEqual(binding.open_cases(), [BATTERY])
        with self.assertRaises(ValueError):
            binding.approve()

    def test_an_unbound_attribute_cannot_also_be_bound(self) -> None:
        entries = reference_entries()
        text = binding.header() + " {\n" + "".join(f"  {line}\n" for line in entries.values()) \
            + f'  {BATTERY} -> "ns=1;s=Cell/Robot/SpeedOverride";\n}}\n'
        errors = languages.check_binding(text)["errors"]
        self.assertTrue(any("more than once" in error["message"] for error in errors), errors)

    def test_the_llm_cannot_mark_an_attribute_as_unbound(self) -> None:
        answer = binding.header() + f' {{\n  {BATTERY} unbound "no node";\n}}\n'
        with mock.patch.object(binding.llm, "complete", return_value=answer):
            accepted, review = binding._select([attribute(BATTERY)])
        self.assertEqual(accepted, {})
        self.assertIn(BATTERY, review)


class FollowingSteps(unittest.TestCase):

    def setUp(self) -> None:
        load_example(BATTERY_RULE)
        save_reference()
        binding.approve()

    def reference_workflows(self) -> str:
        return (EXAMPLE / "watchdog.wf").read_text(encoding="utf-8")

    def test_the_llm_gets_unbound_attributes_only_as_unavailable(self) -> None:
        check = workspace.read_json(workspace.BINDING_CHECK)
        self.assertNotIn(BATTERY, watchdog.operation_catalogue(check))
        self.assertIn(BATTERY, watchdog.unavailable(check))
        self.assertIn(GETTER, watchdog.unavailable(check))

    def test_workflow_reading_an_unbound_attribute_is_a_violation(self) -> None:
        text = self.reference_workflows() + (
            "\nworkflow BatteryLow uses CellMonitoringBinding covers 5 {\n"
            "  start s every 1 min -> g\n"
            "  xor g batteryLevel < 20 ? esc : done\n"
            '  escalate esc "Battery low"\n'
            "  end done\n}\n")
        errors = languages.check_watchdog(text)["errors"]
        self.assertTrue(any(f"{BATTERY} is marked as unbound" in e["message"] for e in errors), errors)

    def test_fault_rule_needing_an_unbound_attribute_is_an_open_case(self) -> None:
        result = languages.check_watchdog(self.reference_workflows() + f"\nopenrule 5 needs {BATTERY};\n")
        self.assertEqual(result["errors"], [])
        self.assertEqual(result["openRules"], [{"number": 5, "attributes": [BATTERY], "reasons": [
            f"{GETTER}: Cell/Robot/GetBatteryLevel"]}])

    def test_open_rule_with_a_bound_attribute_is_a_violation(self) -> None:
        result = languages.check_watchdog(self.reference_workflows() + "\nopenrule 5 needs Robot.cycleTime;\n")
        self.assertTrue(any("not marked as unbound" in e["message"] for e in result["errors"]), result["errors"])

    def test_uncovered_fault_rule_stays_a_violation(self) -> None:
        errors = languages.check_watchdog(self.reference_workflows())["errors"]
        self.assertTrue(any("Fault rule 5 is not covered" in e["message"] for e in errors), errors)

    def test_workflow_requirement_repeating_an_unbound_attribute_stays_unbound(self) -> None:
        text = self.reference_workflows().replace(
            "workflow AxisOverheat uses CellMonitoringBinding covers 4 {",
            "workflow AxisOverheat uses CellMonitoringBinding covers 4 {\n  requires double batteryLevel in [%] {readOnly};")
        check = workspace.read_json(workspace.BINDING_CHECK)
        findings = watchdog.duplicate_requirements(text, check)
        self.assertTrue(any("which no machine node provides" in f["message"] for f in findings), findings)
        with mock.patch.object(binding.llm, "complete", side_effect=AssertionError("no LLM call expected")):
            binding.bind_workflow_requirements(text)
        self.assertIn("AxisOverheat.batteryLevel", workspace.read_json(workspace.BINDING_CHECK)["unbound"])

    def test_dashboard_card_with_an_unbound_attribute_is_a_violation(self) -> None:
        text = (EXAMPLE / "dashboard.dash").read_text(encoding="utf-8").replace(
            "    tile cycleTime;", "    tile cycleTime;\n    tile batteryLevel;")
        errors = languages.check_dashboard(text)["errors"]
        self.assertTrue(any(f"{BATTERY} is marked as unbound" in e["message"] for e in errors), errors)

    def test_reference_dashboard_needs_no_card_for_unbound_attributes(self) -> None:
        errors = languages.check_dashboard((EXAMPLE / "dashboard.dash").read_text(encoding="utf-8"))["errors"]
        self.assertEqual(errors, [])

    def test_deployment_lists_unbound_attributes_and_gives_them_no_value(self) -> None:
        watchdog.save(self.reference_workflows() + f"\nopenrule 5 needs {BATTERY};\n")
        watchdog.approve()
        dashboard.save((EXAMPLE / "dashboard.dash").read_text(encoding="utf-8"))
        dashboard.approve()
        with mock.patch.object(deploy, "_deploy_bpmn", return_value=[]), mock.patch.object(deploy, "_retire_watchdogs"):
            result = deploy.deploy("opc.tcp://localhost:4840/")
        self.assertNotIn(BATTERY, {b["attribute"] for b in result["bindings"]})
        self.assertNotIn(BATTERY, {e["key"] for e in result["entities"]})
        report = {item["attribute"]: item for item in result["unbound"]}
        self.assertEqual(set(report), {BATTERY, POWER})
        self.assertEqual(report[BATTERY]["reason"], f"{GETTER}: Cell/Robot/GetBatteryLevel")
        self.assertEqual(report[BATTERY]["openRules"], [5])
        self.assertIn("readable variable", report[BATTERY]["remedies"]["machineBuilder"])
        self.assertIn("Relax or drop", report[BATTERY]["remedies"]["service"])
        self.assertEqual(result["openRules"][0]["number"], 5)


if __name__ == "__main__":
    unittest.main()
