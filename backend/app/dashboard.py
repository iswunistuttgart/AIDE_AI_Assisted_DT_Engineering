"""Dashboard: the LLM selects cards from a card catalogue and groups them into views, in the
textual Dashboard language. The language tool checks it; its findings go back to the LLM for
up to three revisions. After the third rejected revision the tool generates the default
dashboard with one card per bound attribute, so a dashboard always exists. The engineer
approves the dashboard before deployment."""

from __future__ import annotations

import json

from . import binding, languages, llm, watchdog, workspace

MAX_REVISIONS = 3

# Card catalogue: notation and usage rule of every card that the language supports.
CARD_CATALOGUE = [
    ("view", 'view "<title>" { <cards> }', "Groups cards of one machine part; a machine with few values has one view."),
    ("gauge", "gauge <attribute> min <number> max <number> severity <warning>, <critical>;",
     "A numeric value with a range; take the warning and critical values from the workflow conditions."),
    ("history", "history <attribute>, <attribute> over <number> s|min|h;", "The course of numeric values."),
    ("tile", "tile <attribute>;", "The current value of any attribute."),
    ("toggle", "toggle <attribute>;", "Switches a writable Boolean attribute that is bound to a variable."),
    ("button", 'button <attribute> confirm "<question>" [when <attribute> == <value>];',
     "Calls the method of a writable Boolean attribute bound via a method, after the operator confirms. "
     "With when, the button is shown only while the readable value equals the literal."),
    ("input", 'input <attribute> [min <number> max <number>] confirm "<question>" [when <attribute> == <value>];',
     "Lets the operator enter the value of a writable attribute that is not Boolean; confirm is required "
     "if the attribute is bound via a method. Give min and max when the machine accepts only a range, "
     "for example cells 1 to 9 named in a method description. when works as for a button."),
    ("alarm", "alarm <WorkflowName>;", "Shows a watchdog workflow while it reacts to a deviation."),
    ("hide", "hide <attribute>, <attribute>;", "Bound attributes that the dashboard does not show."),
]

SYSTEM_PROMPT = """You design the Home Assistant dashboard of a digital twin for machine operators.
Show every bound attribute with a fitting card or hide it explicitly. Operators should see
related values and controls together: use one view for a machine with few values, and
separate views only for distinct machine parts that each have many cards. Use the watchdog
workflows to place related values together and to highlight the values that a watchdog
monitors, and take critical ranges from the conditions of the workflows. If the fault rules or
the node descriptions allow a control only in a certain state, for example a start only after a
stop, show the control only in that state with when. Answer only with a dashboard in this
notation:

dashboard <Name> uses <BindingName> {
  view "<title>" {
    <cards>
  }
  hide <attributes>;
}

Use only these cards:
{catalogue}

An attribute may be written without its owner name if the name is unique.
Answer only with the dashboard."""


def system_prompt() -> str:
    catalogue = "\n".join(f"- {name}: {notation}\n  {rule}" for name, notation, rule in CARD_CATALOGUE)
    return SYSTEM_PROMPT.replace("{catalogue}", catalogue)


def draft() -> None:
    """The LLM selects the cards from the approved binding and the approved workflows."""
    check = workspace.read_json(workspace.BINDING_CHECK)
    if not check or not check.get("resolved") or not binding.approved():
        raise ValueError("Approve the binding first.")
    workflows = workspace.read_text(workspace.WATCHDOG)
    if workflows is not None and not watchdog.approved():
        raise ValueError("Approve the watchdog workflows first.")
    # The node descriptions name value ranges and states, such as the cells of a method argument or
    # the state that a control depends on.
    nodes = {node["nodeId"]: node for node in languages.nodes()}
    bound = [
        {key: entry.get(key) for key in ("attribute", "type", "unit", "access", "nodeClass")}
        | {key: nodes[entry["nodeId"]][key] for key in ("description", "arguments")
           if nodes.get(entry["nodeId"], {}).get(key)}
        for entry in check["resolved"].values()
    ]
    messages = [{"role": "user", "content": (
        f"Binding name: {check['name']}\n\n"
        f"Bound attributes:\n{json.dumps(bound, indent=1, ensure_ascii=False)}\n\n"
        f"{_unavailable(check)}"
        f"Watchdog workflows:\n{workflows or 'none'}\n\n"
        f"Fault rules:\n{workspace.read_text(workspace.FAULT_RULES) or 'none'}")}]
    text = llm.complete(system_prompt(), messages)
    errors = languages.check_dashboard(text)["errors"]
    for _ in range(MAX_REVISIONS):
        if not errors:
            break
        findings = "\n".join(f"- line {e['line']}: {e['message']}" if e.get("line") else f"- {e['message']}"
                             for e in errors)
        messages += [
            {"role": "assistant", "content": text},
            {"role": "user", "content": f"The checks rejected the dashboard:\n{findings}\n"
                                        "Revise it and answer only with the corrected dashboard."},
        ]
        text = llm.complete(system_prompt(), messages)
        errors = languages.check_dashboard(text)["errors"]
    save(default_text() if errors else text)


def _unavailable(check: dict) -> str:
    """Attributes marked as unbound: no node provides them, so no card may show them."""
    keys = list(check.get("unbound", {}))
    return f"Unavailable attributes (no node provides them; show no card for them): {', '.join(keys)}\n\n" if keys else ""


def default_text() -> str:
    """The default dashboard: one card per bound attribute, a view per class, and the watchdogs."""
    check = workspace.read_json(workspace.BINDING_CHECK)
    views: dict[str, list[str]] = {}
    for entry in check["resolved"].values():
        key = entry["attribute"]
        method = entry["nodeClass"] == "Method"
        if entry["access"] == "read":
            card = f"tile {key};"
        elif entry["type"] == "boolean":
            card = f'button {key} confirm "Set {key}?";' if method else f"toggle {key};"
        else:
            card = f'input {key} confirm "Send {key} to the machine?";' if method else f"input {key};"
        views.setdefault(key.split(".")[0], []).append(card)
    workflows = workspace.read_json(workspace.WATCHDOG_CHECK, {}).get("workflows", [])
    if workflows:
        views["Watchdogs"] = [f"alarm {workflow['name']};" for workflow in workflows]
    body = "".join(f'  view "{title}" {{\n' + "".join(f"    {card}\n" for card in cards) + "  }\n"
                   for title, cards in views.items())
    name = check["name"].removesuffix("Binding")
    return f"/* Default dashboard */\ndashboard {name}Dashboard uses {check['name']} {{\n{body}}}\n"


def save(text: str) -> None:
    workspace.write_text(workspace.DASHBOARD, text)
    recheck()


def recheck() -> None:
    """Checks the dashboard against the current binding and workflows."""
    text = workspace.read_text(workspace.DASHBOARD)
    workspace.remove(workspace.DASHBOARD_CHECK)
    if text is not None and workspace.BINDING.exists():
        workspace.write_json(workspace.DASHBOARD_CHECK, languages.check_dashboard(text))


def model() -> list[dict] | None:
    """The views of the valid dashboard, or None if no valid dashboard exists."""
    check = workspace.read_json(workspace.DASHBOARD_CHECK)
    return check["views"] if check and not check.get("errors") else None


def approve() -> None:
    """The engineer approves the generated or the default dashboard after reviewing its preview."""
    if model() is None:
        raise ValueError("Only a valid dashboard can be approved.")
    approvals = workspace.read_json(workspace.APPROVALS, {})
    workspace.write_json(workspace.APPROVALS, approvals | {"dashboard": _hash()})


def approved() -> bool:
    """Whether the engineer approved the current dashboard with the current binding and workflows."""
    return workspace.DASHBOARD.exists() and workspace.read_json(workspace.APPROVALS, {}).get("dashboard") == _hash()


def _hash() -> str:
    return workspace.text_hash(workspace.DASHBOARD, workspace.BINDING, workspace.WATCHDOG)
