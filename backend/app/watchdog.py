"""Watchdog workflows: the LLM specifies each watchdog as a BPMN graph in a compact
textual notation, using only catalogue elements and catalogue operations. The
language tool checks it and generates BPMN; its findings go back to the LLM."""

from __future__ import annotations

import json
import re

from . import binding, dashboard, languages, llm, workspace

MAX_REVISIONS = 3
# Messages of the MontiCore parser, as opposed to the checks of parsed workflows.
PARSER_ERROR = r"token recognition error|mismatched|extraneous input|no viable alternative|missing '|cannot be parsed"

# BPMN element catalogue: the elements that the language, its checks and the BPMN
# generator support. Each entry gives the notation and the usage rule for the LLM.
ELEMENT_CATALOGUE = [
    ("message start event", "start <id> on change <value>, <value> -> <next>",
     "Starts the workflow when one of the values changes. Every workflow has exactly one start event."),
    ("timer start event", "start <id> every <number> min -> <next>",
     "Starts the workflow periodically, for example for a regular check."),
    ("signal start event", "start <id> on signal \"<name>\" -> <next>",
     "Starts the workflow when another workflow throws the signal."),
    ("none start event", "start <id> -> <next>",
     "Only for a workflow that other workflows call; such a workflow may omit covers."),
    ("timer event", "timer <id> <number> s -> <next>",
     "Waits a fixed time, for example to filter short spikes before checking again."),
    ("signal throw event", "signal <id> \"<name>\" -> <next>",
     "Informs other workflows, which catch the signal."),
    ("exclusive gateway", "xor <id> <condition> ? <next if true> : <next if false>",
     "Checks the current values."),
    ("inclusive gateway", "inclusive <id> <condition> ? <next>, <condition> ? <next> : <next if none holds>\n"
     "  inclusive <id> -> <next>",
     "The split follows every branch whose condition holds; all its branches, including the last one, "
     "must meet again at one inclusive join (second form)."),
    ("event-based gateway", "eventgw <id> <condition> ? <next if met> : after <number> s <next on deadline>\n"
     "  eventgw <id> signal \"<name>\" ? <next if received> : after <number> s <next on deadline>",
     "Waits until the condition holds or the signal arrives; every wait has a deadline."),
    ("parallel gateway", "parallel <id> -> <next>, <next>",
     "Splits into branches or joins them; all branches of a split must meet again at one parallel gateway."),
    ("service task", "task <id> set <attribute> = <value> -> <next>",
     "Writes one type-correct value to a writable attribute; use one task per write."),
    ("business rule task", "decide <id> set <attribute> from <value> { > <limit> : <result>; ... else <result> } -> <next>",
     "Threshold table: the first row whose comparison holds sets the writable attribute; else is the default."),
    ("user task", "user <id> \"<question>\" yes <next> : no <next> <boundary events>",
     "Asks the operator a yes/no question."),
    ("call activity", "call <id> <WorkflowName> <boundary events> -> <next>",
     "Runs a workflow with a none start event and waits for its end."),
    ("boundary events", "after <number> s -> <next>   when <condition> -> <next>   on signal \"<name>\" -> <next>",
     "Optional after a user task or call activity; each one interrupts it, for example when the operator "
     "does not answer in time."),
    ("escalation end event", "escalate <id> \"<message>\"",
     "Ends the workflow and informs the responsible person."),
    ("terminate end event", "terminate <id>", "Ends the workflow including all parallel branches."),
    ("end event", "end <id>", "Ends the workflow."),
]

SYSTEM_PROMPT = """You specify watchdog workflows for a machine from fault rules written by a domain
expert. A watchdog monitors the plant and reacts to deviations; it does not run the production
process. You decide the structure of each workflow: which conditions to check, how long to wait,
which reactions to try in which order, where the operator must decide, and when to escalate.
Derive the structure from the fault rule and do not copy the structure of the example below:
use a threshold table when a value depends on ranges, parallel branches for simultaneous
reactions, a signal when one workflow informs another, a periodic start for regular checks, a
called workflow for steps that several fault rules share, and a boundary event when the operator
may not answer in time.

Write one workflow per fault rule in this notation, one element per line (a workflow that
other workflows call may be shared and omits covers):

workflow <Name> uses <BindingName> covers <fault rule number> {
  <workflow requirements>
  <elements>
}

If a fault rule needs a value that the operation catalogue lacks, declare it as a workflow
requirement before the elements, in the notation of the service requirements; read-only values
are marked {readOnly}:
  requires <boolean|int|long|float|double|String> <name> [in <unit>] [{readOnly}];
The name is one identifier without a class name or dot, for example axis3MotorTemperature,
not Axis3.motorTemperature; the elements use the same name. The tool binds it to a machine node; if no node fits, it rejects the workflow.
Never declare a requirement for a value that the operation catalogue already offers; use the
catalogue attribute directly.

Unavailable values are attributes that no machine node provides. Never use them and never
declare them as workflow requirements. If a fault rule cannot be implemented without an
unavailable value, write no workflow for it and state it as an open case for the engineer,
outside any workflow:
  openrule <fault rule number> needs <attribute>, <attribute>;

Use only these BPMN elements:
{catalogue}

Every element has a unique <id>, and every <next> is the id of an element of the same workflow.
A condition compares values with literals, combined with "and" and "or", for example
  temperature > 80 and doorClosed == true
Operators: > >= < <= == !=. Booleans use == or != with true or false; strings use quoted text.
Each comparison has an attribute on the left and a literal on the right, never a second attribute.
Parentheses are not supported; "and" binds stronger than "or", so write alternatives as
  a == 1 and b == 1 or a == 2 and b == 2
An exclusive gateway has one condition and two successors: join alternatives with "or" in that
one condition, which may span several lines. Trigger values of a start event are separated by
commas. A called workflow returns no values, so check conditions in the calling workflow.
Time units are s or min. Values use the unit of the attribute.
Use only the values and attributes of the operation catalogue and your workflow requirements,
and catch only signals that a workflow throws. An attribute may be written without its class name if the name is unique.

Example for the fault rule "1. If the temperature stays above 80 °C for 30 seconds, set the
cooling to its highest level. If it does not drop below 75 °C within one minute, halve the feed
rate. If it is still too hot after two more minutes, ask the operator whether to stop the oven,
stop it if confirmed, and inform the shift supervisor in any case.":

workflow Overheat uses OvenBinding covers 1 {
  requires int    coolingLevel;
  requires double feedRate in [%];
  start    s     on change temperature -> g1
  xor      g1    temperature > 80 ? t1 : done
  timer    t1    30 s -> g2
  xor      g2    temperature > 80 ? cool : done
  task     cool  set coolingLevel = 3 -> e1
  eventgw  e1    temperature < 75 ? done : after 60 s feed
  task     feed  set feedRate = 50 -> e2
  eventgw  e2    temperature < 75 ? done : after 120 s ask
  user     ask   "Stop the oven?" yes stop : no esc
  task     stop  set emergencyStop = true -> esc
  escalate esc   "Overheat not resolved"
  end      done
}

Answer only with the workflows."""


def system_prompt() -> str:
    catalogue = "\n".join(f"- {name}: {notation}\n  {rule}" for name, notation, rule in ELEMENT_CATALOGUE)
    return SYSTEM_PROMPT.replace("{catalogue}", catalogue)


def operation_catalogue(check: dict) -> str:
    """Every readable value and every writable attribute of the service binding, with its type and
    the node it is bound to, since fault rules often name the machine's nodes and methods. Workflow
    requirements belong to their workflow and are not offered to others."""
    readable, writable = [], []
    for attribute in check["attributes"]:
        entry = check["resolved"].get(attribute["key"])
        if not entry or "workflow" in entry:
            continue
        item = {"attribute": attribute["key"], "type": attribute["type"], "unit": attribute["unit"],
                "node": entry["identifier"]}
        if "callWhen" in entry:
            item["calls"] = f"the method when set to {str(entry['callWhen']).lower()}"
        if entry["readable"]:
            readable.append(item)
        if entry["access"] == "write":
            writable.append(item)
    return (f"Readable values:\n{json.dumps(readable, indent=1, ensure_ascii=False)}\n\n"
            f"Writable attributes:\n{json.dumps(writable, indent=1, ensure_ascii=False)}")


def draft() -> None:
    """Lets the LLM specify the workflows and revise them with the check findings. Each round binds
    new workflow requirements before the checks run."""
    rules = workspace.read_text(workspace.FAULT_RULES)
    check = workspace.read_json(workspace.BINDING_CHECK)
    if not rules:
        raise ValueError("Upload the fault rules first.")
    if not check or not check.get("resolved"):
        raise ValueError("Create a valid binding first.")
    messages = [{"role": "user", "content": (
        f"Binding name: {check['name']}\n\n{operation_catalogue(check)}\n\n{unavailable(check)}"
        f"Fault rules:\n{rules}")}]
    try:
        _draft(rules, messages, check)
    finally:
        workspace.remove(workspace.WATCHDOG_PROGRESS)


def unavailable(check: dict) -> str:
    """The service attributes that the engineer marked as unbound, which no workflow may use."""
    workflow_keys = {a["key"] for a in check.get("attributes", []) if a.get("workflow")}
    missing = {key: reason for key, reason in check.get("unbound", {}).items() if key not in workflow_keys}
    if not missing:
        return ""
    items = [{"attribute": key, "reason": reason or "no node provides it"} for key, reason in missing.items()]
    return f"Unavailable values (no machine node provides them):\n{json.dumps(items, indent=1, ensure_ascii=False)}\n\n"


def duplicate_requirements(text: str, check: dict) -> list[dict]:
    """Workflow requirements that repeat an attribute of the operation catalogue or an unavailable
    attribute. Bound on their own, each one would be a new binding entry that the engineer resolves
    and approves again, or a node that replaces a value the machine cannot provide."""
    catalogue = [key for key, entry in check.get("resolved", {}).items() if "workflow" not in entry]
    workflow_keys = {a["key"] for a in check.get("attributes", []) if a.get("workflow")}
    missing = [key for key in check.get("unbound", {}) if key not in workflow_keys]
    findings = []
    for number, line in enumerate(text.splitlines(), start=1):
        match = re.match(r"\s*requires\s+\w+\s+(\w+)", line)
        key = match and next((k for k in catalogue if binding.same_value(match.group(1), k)), None)
        gap = match and not key and next((k for k in missing if binding.same_value(match.group(1), k)), None)
        if key:
            findings.append({"line": number, "message": (
                f"The requirement {match.group(1)} repeats the catalogue attribute {key}; "
                f"remove the requirement and use {key}.")})
        elif gap:
            findings.append({"line": number, "message": (
                f"The requirement {match.group(1)} repeats {gap}, which no machine node provides (unavailable). "
                f"Remove it; if the fault rule cannot be implemented without {gap}, write no workflow for it and "
                f"state openrule <number> needs {gap};.")})
    return findings


def _draft(rules: str, messages: list[dict[str, str]], check: dict) -> None:
    _progress(rules, 0, "writing")
    text = llm.complete(system_prompt(), messages)
    revisions = 0
    while True:
        _progress(rules, revisions, "checking", text)
        # Repeated catalogue attributes go back to the LLM before they reach the binding.
        errors = duplicate_requirements(text, check)
        if not errors or revisions == MAX_REVISIONS:
            binding.bind_workflow_requirements(text)
            errors = languages.check_watchdog(text)["errors"]
        _progress(rules, revisions, "checked", text, errors)
        if not errors or revisions == MAX_REVISIONS:
            break
        revisions += 1
        _progress(rules, revisions, "writing", text, errors)
        findings = "\n".join(f"- line {e['line']}: {e['message']}" if e.get("line") else f"- {e['message']}"
                             for e in errors)
        messages += [
            {"role": "assistant", "content": text},
            {"role": "user", "content": f"The checks rejected the workflows:\n{findings}\n"
                                        "Revise them and answer only with the corrected workflows."},
        ]
        text = llm.complete(system_prompt(), messages)
    workspace.write_text(workspace.WATCHDOG, text)
    recheck()
    # After three failed revisions the engineer gets the rules, the last draft and the open findings.
    result = workspace.read_json(workspace.WATCHDOG_CHECK, {})
    workspace.write_json(workspace.WATCHDOG_CHECK, result | {"revisions": revisions})


def _progress(rules: str, revision: int, step: str, text: str | None = None,
              errors: list[dict] | None = None) -> None:
    """Writes the state of a running draft for the front end: the round, the step and, per fault
    rule, the workflow that covers it and its findings, which are assigned by line number."""
    blocks = []  # (first line, workflow name, covered rule numbers)
    for number, line in enumerate((text or "").splitlines(), start=1):
        match = re.match(r"\s*workflow\s+(\w+)\s+uses\s+\w+(?:\s+covers\s+([\d,\s]+))?", line)
        if match:
            blocks.append((number, match.group(1), {int(n) for n in re.findall(r"\d+", match.group(2) or "")}))
    findings = {name: 0 for _, name, _ in blocks}
    other = 0  # Findings outside a workflow or in a workflow that covers no fault rule.
    for error in errors or []:
        owner = next((name for first, name, _ in reversed(blocks) if first <= (error.get("line") or 0)), None)
        if owner:
            findings[owner] += 1
        if not owner or not next(covers for _, name, covers in blocks if name == owner):
            other += 1
    # After a syntax error the tool checks no workflow, so workflows without findings are unchecked.
    syntax = any(re.search(PARSER_ERROR, e["message"]) for e in errors or [])
    open_rules = {int(n) for n in re.findall(r"(?m)^\s*openrule\s+(\d+)", text or "")}
    items = []
    for match in re.finditer(r"(?m)^\s*(\d+)[.)]\s+([^:\n]*)", rules):
        number = int(match.group(1))
        workflows = [name for _, name, covers in blocks if number in covers]
        if text is None:
            status = "waiting"
        elif number in open_rules and not workflows:
            status = "open"
        elif not workflows:
            status = "missing"
        elif errors is None:
            status = "written"
        elif sum(findings[w] for w in workflows):
            status = "rejected"
        else:
            status = "unchecked" if syntax else "accepted"
        items.append({"number": number, "title": match.group(2).strip(), "workflows": workflows, "status": status,
                      "findings": sum(findings[w] for w in workflows)})
    workspace.write_json(workspace.WATCHDOG_PROGRESS, {
        "revision": revision, "maxRevisions": MAX_REVISIONS, "step": step, "rules": items, "otherFindings": other,
    })


def save(text: str) -> None:
    """Saves the engineer's workflows, binds their new workflow requirements and checks them."""
    if not workspace.FAULT_RULES.exists():
        raise ValueError("Upload the fault rules first; the checks trace every fault rule to a workflow.")
    workspace.write_text(workspace.WATCHDOG, text)
    try:
        binding.bind_workflow_requirements(text)
    finally:
        recheck()


def recheck() -> None:
    """Checks the workflows against the current binding and regenerates the BPMN models."""
    text = workspace.read_text(workspace.WATCHDOG)
    workspace.remove(workspace.BPMN_DIR, workspace.WATCHDOG_CHECK)
    if text is not None and workspace.BINDING.exists() and workspace.FAULT_RULES.exists():
        workspace.write_json(workspace.WATCHDOG_CHECK, languages.check_watchdog(text, workspace.BPMN_DIR))
    dashboard.recheck()


def approve() -> None:
    """The engineer approves the checked workflows after reviewing their BPMN models."""
    check = workspace.read_json(workspace.WATCHDOG_CHECK, {})
    if not check or check.get("errors"):
        raise ValueError("Only accepted workflows can be approved.")
    approvals = workspace.read_json(workspace.APPROVALS, {})
    workspace.write_json(workspace.APPROVALS, approvals | {"watchdog": current_hash()})


def approved() -> bool:
    """Whether the engineer approved the current workflows with the current binding."""
    return workspace.WATCHDOG.exists() and workspace.read_json(workspace.APPROVALS, {}).get("watchdog") == current_hash()


def current_hash() -> str:
    """Identifies the workflows together with the binding that they act through."""
    return workspace.text_hash(workspace.WATCHDOG, workspace.BINDING)
