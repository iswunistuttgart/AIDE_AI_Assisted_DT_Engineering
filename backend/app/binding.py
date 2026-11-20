"""Binding generation: compatibility filter, LLM selection, validation by CoCos with up to
three revisions, and engineer resolution and approval. Workflow requirements of the watchdog
workflows go through the same pipeline and are stored in the same binding specification."""

from __future__ import annotations

import json
import re
from concurrent.futures import ThreadPoolExecutor
from typing import Any

from . import languages, llm, watchdog, workspace

MAX_REVISIONS = 3

SYSTEM_PROMPT ="""You bind the required attributes of a digital twin service to OPC UA nodes.
For every attribute, choose exactly one node from its candidate list, based on names,
descriptions and the object hierarchy. If no candidate has the same meaning, leave the
attribute out. Several nodes that must be combined (for example a sum of phases) do not fit.
When meanings are equal, prefer a compatible candidate over a convertible one.
Use only NodeIds from the candidate lists.

Answer only with a binding specification in this textual language:

binding <Name> for <Requirements>, <NodeSet> {
  <Owner.attribute> -> "<NodeId>" <conversions>;
}

Every entry ends with a semicolon, also when it has no conversions.

After the NodeId, copy the conversions listed for the chosen candidate exactly, for example
  convert K to degC
  widen Int32 to double
  via method
Write nothing else."""


def refresh_candidates() -> None:
    """Runs the compatibility filter and discards bindings and approvals of earlier inputs."""
    workspace.write_json(workspace.CANDIDATES, languages.candidates())
    workspace.remove(workspace.BINDING, workspace.BINDING_CHECK, workspace.BINDING_REVIEW, workspace.APPROVALS)


def attributes() -> list[dict[str, Any]]:
    return workspace.read_json(workspace.CANDIDATES, {}).get("attributes", [])


def header() -> str:
    name = workspace.read_json(workspace.CANDIDATES, {})["requirements"]
    return f"binding {name}Binding for {name}, {workspace.nodeset_name()}"


def generate() -> None:
    """Binds all service attributes; earlier bindings of workflow requirements stay."""
    entries, review = _state()
    service = [a for a in attributes() if "workflow" not in a]
    for attribute in service:
        entries.pop(attribute["key"], None)
        review.pop(attribute["key"], None)
    _select_all(service, entries, review)
    _save(entries, review)


def bind_workflow_requirements(workflows: str) -> None:
    """Binds the workflow requirements that the given workflows declare and that are still open,
    and drops the bindings of requirements that no workflow declares any more."""
    workspace.write_json(workspace.CANDIDATES, languages.candidates(workflows))
    keys = {a["key"] for a in attributes()}
    entries, review = _state()
    entries = {k: v for k, v in entries.items() if k in keys}
    review = {k: v for k, v in review.items() if k in keys}
    new = [a for a in attributes() if "workflow" in a and a["key"] not in entries and a["key"] not in review]
    reused = _reuse_service_bindings(new, entries)
    _select_all([a for a in new if a["key"] not in reused], entries, review, workflows)
    _save(entries, review, workflows)


def same_value(name: str, key: str) -> bool:
    """Whether a workflow requirement name denotes the service attribute Class.attribute, written
    as attribute or as classAttribute (for example stop or robotStop for Robot.stop)."""
    return name.lower() in {key.split(".")[-1].lower(), key.replace(".", "").lower()}


def _reuse_service_bindings(group: list[dict[str, Any]], entries: dict[str, str]) -> set[str]:
    """Binds a workflow requirement that repeats a bound service attribute of the same type to the
    same node. A value mapping that the engineer confirmed for that node stays confirmed, so the
    engineer does not resolve the same method again. A requirement that repeats a service attribute
    marked as unbound stays unbound, so that no other node replaces the missing value."""
    service = {a["key"]: a for a in attributes() if "workflow" not in a and a["key"] in entries}
    reused = set()
    for attribute in group:
        name = attribute["key"].split(".", 1)[1]
        missing = next((k for k in service if same_value(name, k) and re.search(r"\sunbound\b", entries[k])), None)
        if missing:
            reason = re.search(r'unbound\s+"((?:[^"\\]|\\.)*)"', entries[missing])
            note = f"repeats {missing}, which is marked as unbound" + (f": {reason.group(1)}" if reason else "")
            entries[attribute["key"]] = unbound_text(attribute["key"], note.replace('\\"', '"'))
            reused.add(attribute["key"])
            continue
        for key, original in service.items():
            match = re.search(r'->\s*"([^"]+)"', entries[key])
            if not match or original["type"] != attribute["type"] or not same_value(name, key):
                continue
            option = _candidate(attribute, match.group(1)) or next(
                (o for o in attribute.get("manual", []) if o["nodeId"] == match.group(1)), None)
            if option:
                entries[attribute["key"]] = _entry_text(attribute["key"], option)
                reused.add(attribute["key"])
                break
    return reused


def _select_all(group: list[dict[str, Any]], entries: dict[str, str], review: dict[str, Any],
                workflows: str | None = None) -> None:
    """LLM selection, one call per top-level class or workflow. The checks need the workflows that
    declare the requirements, because a draft is checked before it is saved."""
    groups: dict[str, list[dict[str, Any]]] = {}
    for attribute in group:
        if attribute["kind"] not in {"input", "output"}:
            continue
        if not attribute["candidates"]:
            review[attribute["key"]] = {"reasons": ["No candidate: the compatibility filter left no node."]}
            continue
        groups.setdefault(attribute["key"].split(".")[0], []).append(attribute)
    # An API or network failure raises here and is shown as a system error, not as a review reason.
    with ThreadPoolExecutor(max_workers=4) as pool:
        for accepted, rejected in pool.map(lambda g: _select(g, workflows), groups.values()):
            entries.update(accepted)
            review.update(rejected)


def _select(group: list[dict[str, Any]], workflows: str | None = None) -> tuple[dict[str, str], dict[str, Any]]:
    """Lets the LLM select candidates and revise only the rejected lines, at most three times."""
    by_key = {attribute["key"]: attribute for attribute in group}
    messages = [{"role": "user", "content": json.dumps(_prompt(group), indent=1, ensure_ascii=False)}]
    accepted: dict[str, str] = {}
    review: dict[str, Any] = {}
    open_keys = set(by_key)
    for revision in range(MAX_REVISIONS + 1):
        # Only the engineer may bind through a value mapping and confirm it, or mark an attribute as unbound.
        text = re.sub(r"\bconfirmed\b", "", llm.complete(SYSTEM_PROMPT, messages))
        text = re.sub(r"^\s*\S+\s+unbound\b.*$", "", text, flags=re.M)
        # The LLM often omits the semicolon of an entry without conversions; it carries no meaning.
        text = re.sub(r'^(\s*\S+\s*->.*?[^;\s])\s*$', r"\1;", text, flags=re.M)
        rejected: dict[str, dict[str, Any]] = {}
        answered = set()
        for entry in languages.check_binding(text, workflows)["entries"]:
            key = entry["attribute"]
            if key not in open_keys or key in answered:
                continue
            answered.add(key)
            if entry["errors"]:
                rejected[key] = entry
            else:
                candidate = _candidate(by_key[key], entry["nodeId"])
                accepted[key] = _entry_text(key, candidate) if candidate else entry["text"]
        for key in open_keys - answered:
            review[key] = {"reasons": ["The LLM found no fitting candidate."]}
        open_keys = set(rejected)
        if not rejected:
            break
        if revision == MAX_REVISIONS:
            for key, entry in rejected.items():
                review[key] = {
                    "proposal": entry["nodeId"],
                    "reasons": [f"Rejected after {MAX_REVISIONS} revisions: {error}" for error in entry["errors"]],
                }
            break
        findings = "\n".join(f"{entry['text']}\n  " + "\n  ".join(entry["errors"]) for entry in rejected.values())
        messages += [
            {"role": "assistant", "content": text},
            {"role": "user", "content": f"The checks rejected these lines:\n{findings}\n"
                                        "Answer only with corrected lines for these attributes; leave out an "
                                        "attribute if no candidate fits."},
        ]
    return accepted, review


def _prompt(group: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "header": header(),
        "attributes": [
            {
                "attribute": attribute["key"],
                "type": attribute["type"],
                "unit": attribute["unit"],
                "access": "read-only" if attribute["access"] == "read" else "writable",
                "candidates": [
                    {
                        key: candidate.get(key)
                        for key in ("nodeId", "path", "description", "nodeClass", "dataType",
                                    "arguments", "unit", "access", "classification")
                        if candidate.get(key) is not None
                    }
                    | {"conversions": candidate["clauses"]}
                    for candidate in attribute["candidates"]
                ],
            }
            for attribute in group
        ],
    }


def resolve(key: str, node_id: str) -> None:
    """The engineer binds an attribute to one of its candidates, or through a value mapping to a
    node that the filter offers only to the engineer; the choice confirms the value mapping."""
    attribute = next((a for a in attributes() if a["key"] == key), None)
    if attribute is None or attribute["kind"] not in {"input", "output"}:
        raise ValueError(f"{key} is not a bindable attribute.")
    candidate = _candidate(attribute, node_id) or next(
        (option for option in attribute.get("manual", []) if option["nodeId"] == node_id), None)
    if candidate is None:
        raise ValueError(f"{node_id} is neither a candidate of {key} nor bindable through a value mapping.")
    entries, review = _state()
    entries[key] = _entry_text(key, candidate)
    review.pop(key, None)
    _save(entries, review)


def remove(key: str) -> None:
    """The engineer removes the binding or the unbound mark of an attribute; it becomes an open case again."""
    entries, review = _state()
    entries.pop(key, None)
    review[key] = {"reasons": ["Removed by the engineer."]}
    _save(entries, review)


def mark_unbound(key: str, reason: str = "") -> None:
    """The engineer decides that no node can provide the attribute. The binding specification keeps
    the decision and its reason, so the approval covers it and later steps treat the value as missing."""
    attribute = next((a for a in attributes() if a["key"] == key), None)
    if attribute is None or attribute["kind"] not in {"input", "output"}:
        raise ValueError(f"{key} is not a bindable attribute.")
    entries, review = _state()
    entries[key] = unbound_text(key, reason)
    review.pop(key, None)
    _save(entries, review)


def unbound_text(key: str, reason: str) -> str:
    """The unbound entry of the binding language; the reason is one line of quoted text."""
    reason = " ".join(reason.split())[:300].replace("\\", "\\\\").replace('"', '\\"')
    return f'{key} unbound "{reason}";' if reason else f"{key} unbound;"


def unbound() -> dict[str, str]:
    """The attributes that the engineer marked as unbound in the checked specification, with reasons."""
    return workspace.read_json(workspace.BINDING_CHECK, {}).get("unbound", {})


def open_cases() -> list[str]:
    """Bindable attributes that are neither validly bound nor marked as unbound."""
    check = workspace.read_json(workspace.BINDING_CHECK, {})
    done = set(check.get("resolved", {})) | set(check.get("unbound", {}))
    return [a["key"] for a in attributes() if a["kind"] in {"input", "output"} and a["key"] not in done]


def approve() -> None:
    """The engineer approves the complete binding specification once every attribute is bound or
    explicitly marked as unbound."""
    check = workspace.read_json(workspace.BINDING_CHECK, {})
    if not check or check.get("errors") or not check.get("resolved"):
        raise ValueError("Only a valid binding specification with at least one entry can be approved.")
    if remaining := open_cases():
        raise ValueError("Bind every attribute or mark it as unbound before the approval; still open: "
                         + ", ".join(remaining) + ".")
    approvals = workspace.read_json(workspace.APPROVALS, {})
    workspace.write_json(workspace.APPROVALS, approvals | {"binding": workspace.text_hash(workspace.BINDING)})


def unbound_report() -> list[dict[str, Any]]:
    """Every unbound attribute with its reason and who can close the gap: the machine builder by
    providing a node that AIDE can bind, or the service by relaxing its requirement."""
    by_key = {a["key"]: a for a in attributes()}
    rules = workspace.read_json(workspace.WATCHDOG_CHECK, {}).get("openRules", [])
    report = []
    for key, reason in unbound().items():
        attribute = by_key.get(key, {})
        kind = (f"{attribute.get('type', '?')}" + (f" in {attribute['unit']}" if attribute.get("unit") else ""))
        if attribute.get("access") == "read":
            node = f"a readable variable ({kind})"
        else:
            node = f"a writable variable or a method with one argument ({kind})"
        excluded = [f"{n['path']}: {n['reason']}" for n in attribute.get("unsupported", [])]
        owner = f"workflow requirement of {attribute['workflow']}" if attribute.get("workflow") else "service requirement"
        report.append({
            "attribute": key,
            "reason": reason,
            "type": attribute.get("type"),
            "unit": attribute.get("unit"),
            "access": attribute.get("access"),
            "workflow": attribute.get("workflow"),
            "excluded": excluded,
            "openRules": [rule["number"] for rule in rules if key in rule["attributes"]],
            "remedies": {
                "machineBuilder": f"Provide {node} for {key} in the NodeSet of the machine.",
                "service": f"Relax or drop the {owner} {key}, so that the service no longer needs this value.",
            },
        })
    return report


def approved() -> bool:
    """Whether the engineer approved the current, unchanged binding specification."""
    approval = workspace.read_json(workspace.APPROVALS, {}).get("binding")
    return workspace.BINDING.exists() and approval == workspace.text_hash(workspace.BINDING)


def review() -> dict[str, Any]:
    return _state()[1]


def entries() -> dict[str, str]:
    return _state()[0]


def _candidate(attribute: dict[str, Any], node_id: str) -> dict[str, Any] | None:
    return next((c for c in attribute.get("candidates", []) if c["nodeId"] == node_id), None)


def _entry_text(key: str, candidate: dict[str, Any]) -> str:
    """Only the engineer's options contain a value mapping; choosing one confirms it."""
    clauses = [clause + " confirmed" if clause.startswith("map ") else clause for clause in candidate["clauses"]]
    return " ".join([f'{key} -> "{candidate["nodeId"]}"', *clauses]) + ";"


def _state() -> tuple[dict[str, str], dict[str, Any]]:
    state = workspace.read_json(workspace.BINDING_REVIEW, {"entries": {}, "review": {}})
    return state["entries"], state["review"]


def _save(entries: dict[str, str], review: dict[str, Any], workflows: str | None = None) -> None:
    order = [attribute["key"] for attribute in attributes()]
    entries = dict(sorted(entries.items(), key=lambda item: order.index(item[0])))
    workspace.write_json(workspace.BINDING_REVIEW, {"entries": entries, "review": review})
    text = header() + " {\n" + "".join(f"  {line}\n" for line in entries.values()) + "}\n"
    workspace.write_text(workspace.BINDING, text)
    workspace.write_json(workspace.BINDING_CHECK, languages.check_binding(text, workflows))
    if workflows is None:
        watchdog.recheck()
