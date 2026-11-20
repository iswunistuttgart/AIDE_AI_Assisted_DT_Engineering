import React, { useEffect, useRef, useState } from "react";
import { AlertTriangle, CheckCircle2, FileUp, Link2, Loader2, Rocket, ShieldCheck, Sparkles } from "lucide-react";
import NavigatedViewer from "bpmn-js/lib/NavigatedViewer";
import "bpmn-js/dist/assets/diagram-js.css";
import "bpmn-js/dist/assets/bpmn-js.css";
import "bpmn-js/dist/assets/bpmn-font/css/bpmn.css";

const STATUS_LABELS = {
  bound: "bound",
  unbound: "unbound",
  review: "engineer",
  open: "not generated",
  structured: "field by field",
  unsupported: "not supported"
};

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
    body: options.body === undefined ? undefined : JSON.stringify(options.body)
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(payload.detail || `${response.status} ${response.statusText}`);
  }
  return payload;
}

export default function App() {
  const [state, setState] = useState(null);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");

  const run = async (label, path, options) => {
    setBusy(label);
    setError("");
    try {
      setState(await api(path, options));
    } catch (exc) {
      setError(exc.message);
    } finally {
      setBusy("");
    }
  };

  useEffect(() => {
    api("/api/state").then(setState).catch((exc) => setError(exc.message));
  }, []);

  useEffect(() => {
    // While a long request runs, polling shows its intermediate state, such as the draft progress.
    if (!busy) return undefined;
    const timer = setInterval(() => api("/api/state").then((next) => setState((old) => (old ? { ...old, watchdogProgress: next.watchdogProgress } : next))).catch(() => {}), 1500);
    return () => clearInterval(timer);
  }, [busy]);

  useEffect(() => {
    if (!state?.deployment) return undefined;
    const timer = setInterval(() => api("/api/state").then(setState).catch(() => {}), 5000);
    return () => clearInterval(timer);
  }, [state?.deployment?.deployedAt]);

  if (!state) {
    return <main className="app-shell">{error ? <Banner text={error} /> : "Loading…"}</main>;
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <div className="brand">
          <img src="/isw-logo.png" alt="ISW, University of Stuttgart" className="logo" />
          <div>
            <h1>AIDE: AI-Assisted DT Engineering</h1>
            <p className="subtitle">LLMs propose, deterministic checks decide, engineers resolve the rest. Model: {state.model}</p>
          </div>
        </div>
        {busy && (
          <span className="busy">
            <Loader2 size={16} className="spin" /> {busy}
          </span>
        )}
      </header>
      {error && <Banner text={error} />}
      <InputsPanel state={state} busy={busy} run={run} />
      <BindingPanel state={state} busy={busy} run={run} />
      <WatchdogPanel state={state} busy={busy} run={run} />
      <DashboardPanel state={state} busy={busy} run={run} />
      <DeployPanel state={state} busy={busy} run={run} />
    </main>
  );
}

function Banner({ text }) {
  return (
    <div className="banner">
      <AlertTriangle size={16} /> {text}
    </div>
  );
}

function Panel({ step, title, children, actions }) {
  return (
    <section className="panel">
      <div className="panel-head">
        <h2>
          <span className="step">{step}</span> {title}
        </h2>
        <div className="actions">{actions}</div>
      </div>
      {children}
    </section>
  );
}

function Errors({ errors }) {
  if (!errors?.length) return null;
  return (
    <ul className="errors">
      {errors.map((error, index) => (
        <li key={index}>
          {error.line ? `Line ${error.line}: ` : ""}
          {error.message}
        </li>
      ))}
    </ul>
  );
}

function InputsPanel({ state, busy, run }) {
  const [example, setExample] = useState(state.example || state.examples[0]?.name || "");
  const upload = (kind) => async (event) => {
    const file = event.target.files[0];
    event.target.value = "";
    if (file) run(`Uploading ${file.name}`, `/api/inputs/${kind}`, { method: "PUT", body: { name: file.name, text: await file.text() } });
  };
  const inputs = [
    ["requirements", "Service requirements (class diagram)", ".cd,.txt"],
    ["nodeset", "OPC UA NodeSet", ".xml"],
    ["fault-rules", "Fault rules (natural language)", ".md,.txt"]
  ];
  return (
    <Panel
      step="1"
      title="Specify"
      actions={
        <>
          <select value={example} onChange={(event) => setExample(event.target.value)} disabled={!!busy}>
            {state.examples.map((item) => (
              <option key={item.name} value={item.name}>
                {item.title}
              </option>
            ))}
          </select>
          <button disabled={!example || !!busy} onClick={() => run("Loading example", `/api/examples/${example}`, { method: "POST" })}>
            Load example
          </button>
        </>
      }
    >
      <div className="inputs">
        {inputs.map(([kind, label, accept]) => (
          <label key={kind} className={`input-file ${state.inputs[kind] ? "uploaded" : ""}`}>
            <span className="input-label">{label}</span>
            <span className="input-name">{state.inputs[kind] || "not uploaded"}</span>
            <span className="icon-button">
              <FileUp size={16} /> Upload
              <input type="file" accept={accept} disabled={!!busy} onChange={upload(kind)} hidden />
            </span>
          </label>
        ))}
      </div>
      {state.example && <p className="muted">Example loaded: {state.examples.find((item) => item.name === state.example)?.title}.</p>}
      {state.nodeCount != null && <p className="muted">{state.nodeCount} nodes in the NodeSet.</p>}
      <Errors errors={state.requirementsErrors} />
    </Panel>
  );
}

function BindingPanel({ state, busy, run }) {
  const ready = state.attributes.length > 0 && !state.requirementsErrors.length;
  const counts = state.attributes.reduce((acc, a) => ({ ...acc, [a.status]: (acc[a.status] || 0) + 1 }), {});
  return (
    <Panel
      step="2"
      title="Bind & validate"
      actions={
        <>
          <button disabled={!ready || !!busy} onClick={() => run("Generating binding with the LLM", "/api/bindings/generate", { method: "POST" })}>
            <Sparkles size={16} /> Generate binding
          </button>
          <button
            className="primary"
            disabled={!state.bindingText || state.bindingErrors.length > 0 || state.bindingOpenCases.length > 0 || state.bindingApproved || !!busy}
            title={state.bindingOpenCases.length ? `Bind or mark as unbound first: ${state.bindingOpenCases.join(", ")}` : undefined}
            onClick={() => run("Approving the binding", "/api/bindings/approve", { method: "POST" })}
          >
            <ShieldCheck size={16} /> {state.bindingApproved ? "Binding approved" : "Approve binding"}
          </button>
        </>
      }
    >
      {ready ? (
        <>
          <p className="muted">
            {counts.bound || 0} bound, {counts.unbound || 0} unbound, {counts.review || 0} for the engineer, {counts.open || 0} not generated. The
            compatibility filter offers only compatible and convertible nodes to the LLM; conversions are derived by the tool. Only the engineer can
            bind a node through a value mapping, for example a method without argument, or mark an attribute as unbound if no node can provide it.
            Greyed nodes are excluded only because AIDE does not support them, for example getter methods. Workflow requirements of the watchdog
            workflows are bound in the same way. Approve the binding once every attribute is bound or marked as unbound; any change ends the
            approval.
          </p>
          {state.bindingOpenCases.length > 0 && state.bindingText && (
            <p className="muted">Still open before the approval: {state.bindingOpenCases.join(", ")}.</p>
          )}
          <table className="attributes">
            <thead>
              <tr>
                <th>#</th>
                <th>Attribute (CD)</th>
                <th>Type (CD)</th>
                <th>Binding (OPC UA)</th>
                <th>Reason</th>
                <th>Status</th>
                <th className="review-column">Engineer review</th>
              </tr>
            </thead>
            <tbody>
              {state.attributes.map((attribute, index) => (
                <AttributeRow key={attribute.key} number={index + 1} attribute={attribute} busy={busy} run={run} />
              ))}
            </tbody>
          </table>
          <Errors errors={state.bindingErrors} />
          {state.bindingText && <BindingText text={state.bindingText} attributes={state.attributes} />}
          <UnboundReport report={state.unboundReport} />
        </>
      ) : (
        <p className="muted">Upload valid service requirements and a NodeSet first.</p>
      )}
    </Panel>
  );
}

/** The part of a binding entry after the attribute: the NodeId and its conversion clauses. */
function entryBinding(entry) {
  return entry.slice(entry.indexOf("->") + 2).trim().replace(/;$/, "");
}

/** All unbound attributes with their reason and who can close the gap. */
function UnboundReport({ report, title = "Unbound attributes" }) {
  if (!report?.length) return null;
  return (
    <div className="unbound-report">
      <h3>
        {title} ({report.length})
      </h3>
      <ul>
        {report.map((item) => (
          <li key={item.attribute}>
            <span className="mono">{item.attribute}</span>: {item.reason || "no reason given"}
            {item.excluded.length > 0 && <div className="muted">Excluded node: {item.excluded.join("; ")}</div>}
            {item.openRules.length > 0 && <div className="muted">Open fault rules: {item.openRules.join(", ")}</div>}
            <div className="muted">Machine builder: {item.remedies.machineBuilder}</div>
            <div className="muted">Service: {item.remedies.service}</div>
          </li>
        ))}
      </ul>
    </div>
  );
}

function AttributeRow({ number, attribute, busy, run }) {
  const review = attribute.review;
  // The dropdown shows the bound node, otherwise the rejected proposal.
  const current = attribute.entry?.match(/->\s*"([^"]+)"/)?.[1] || review?.proposal || "";
  const [selected, setSelected] = useState(current);
  useEffect(() => setSelected(current), [current]);
  const excluded = attribute.unsupported || [];
  // The reason of an exclusion by an unsupported rule is the proposed reason for marking the attribute as unbound.
  const proposedReason = excluded.length ? `${excluded[0].reason}: ${excluded[0].path}` : "";
  const [reason, setReason] = useState(proposedReason);
  useEffect(() => setReason(proposedReason), [proposedReason, attribute.status]);
  const bindable = attribute.kind === "input" || attribute.kind === "output";
  const url = `/api/bindings/${encodeURIComponent(attribute.key)}`;
  const bind = (nodeId) => run(`Binding ${attribute.key}`, url, { method: "PUT", body: { nodeId } });
  return (
    <tr>
      <td className="number">{number}</td>
      <td className="mono">
        {attribute.key}
        {attribute.workflow && <div className="muted">workflow requirement of {attribute.workflow}</div>}
      </td>
      <td>
        {attribute.type}
        {attribute.unit ? ` [${attribute.unit}]` : ""} · {attribute.access === "read" ? "read-only" : "writable"}
      </td>
      <td>
        {attribute.entry && <code>{entryBinding(attribute.entry)}</code>}
        {review?.proposal && <div className="mono">Proposal: "{review.proposal}"</div>}
      </td>
      <td>
        {/* One line per row; the full reasons appear on hover. */}
        {attribute.status === "unbound" && (
          <div className="reason" title={attribute.unboundReason || "unbound"}>
            {attribute.unboundReason || "Marked as unbound."}
          </div>
        )}
        {review && (
          <div className="reason" title={review.reasons.join("\n")}>
            {review.reasons.join("; ")}
          </div>
        )}
        {bindable && attribute.status !== "bound" && attribute.status !== "unbound" && excluded.map((node) => (
          <div key={node.nodeId} className="reason excluded" title={`${node.path}: ${node.reason}`}>
            {node.path}: {node.reason}
          </div>
        ))}
        {!bindable && attribute.note && (
          <div className="reason" title={attribute.note}>
            {attribute.note}
          </div>
        )}
      </td>
      <td>
        <span className={`badge ${attribute.status}`}>{STATUS_LABELS[attribute.status]}</span>
      </td>
      <td>
        {bindable && (
          <div className="engineer">
            <select value={selected} onChange={(event) => setSelected(event.target.value)} disabled={!!busy}>
              <option value="">Choose node ({attribute.candidates.length + attribute.manual.length})</option>
              {excluded.length > 0 && (
                <optgroup label="Excluded: not supported by AIDE">
                  {excluded.map((node) => (
                    <option key={node.nodeId} value={`excluded:${node.nodeId}`} disabled>
                      {node.path} · {node.reason}
                    </option>
                  ))}
                </optgroup>
              )}
              {attribute.candidates.map((candidate) => (
                <option key={candidate.nodeId} value={candidate.nodeId}>
                  {candidate.path} · {candidate.classification}
                  {candidate.clauses.length ? ` · ${candidate.clauses.join(", ")}` : ""}
                </option>
              ))}
              {attribute.manual.length > 0 && (
                <optgroup label="Only through a value mapping">
                  {attribute.manual.map((option) => (
                    <option key={option.nodeId} value={option.nodeId}>
                      {option.path} · {option.clauses.join(", ")}
                    </option>
                  ))}
                </optgroup>
              )}
            </select>
            <button disabled={!selected || (attribute.status === "bound" && selected === current) || !!busy} onClick={() => bind(selected)}>
              Bind
            </button>
            {/* Always shown, so Bind and Remove keep their position in every row. */}
            <button
              disabled={(attribute.status !== "bound" && attribute.status !== "unbound") || !!busy}
              title="Removes the binding or the unbound mark; the attribute becomes an open case again."
              onClick={() => run(`Removing ${attribute.key}`, url, { method: "DELETE" })}
            >
              Remove
            </button>
            {attribute.status !== "unbound" && (
              <>
                <input
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                  placeholder="Reason (optional)"
                  disabled={!!busy}
                  aria-label={`Reason why ${attribute.key} is unbound`}
                />
                <button
                  className="mark-unbound"
                  disabled={!!busy}
                  onClick={() => run(`Marking ${attribute.key} as unbound`, `${url}/unbound`, { method: "POST", body: { reason } })}
                >
                  Mark as unbound
                </button>
              </>
            )}
          </div>
        )}
      </td>
    </tr>
  );
}

/** The binding specification with the row number of the table in front of each entry. */
function BindingText({ text, attributes }) {
  const numbers = Object.fromEntries(attributes.map((attribute, index) => [attribute.key, index + 1]));
  const lines = text.replace(/\n$/, "").split("\n");
  return (
    <table className="spec-lines">
      <tbody>
        {lines.map((line, index) => {
          const key = line.trim().split(/\s+(?:->|unbound\b)/)[0];
          return (
            <tr key={index}>
              <td className="number">{numbers[key] || ""}</td>
              <td>{line}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function WatchdogPanel({ state, busy, run }) {
  const [text, setText] = useState(state.watchdogText || "");
  useEffect(() => setText(state.watchdogText || ""), [state.watchdogText]);
  const ready = !!state.bindingText;
  const accepted = !!state.watchdogText && state.watchdogErrors.length === 0 && (state.workflows.length > 0 || state.openRules.length > 0);
  return (
    <Panel
      step="3"
      title="Specify watchdog workflows"
      actions={
        <>
          <button disabled={!ready || !state.faultRules || !!busy} onClick={() => run("The LLM specifies the workflows", "/api/watchdog/draft", { method: "POST" })}>
            <Sparkles size={16} /> Specify from fault rules
          </button>
          <button disabled={!ready || !state.faultRules || !text.trim() || !!busy} onClick={() => run("Checking the workflows", "/api/watchdog", { method: "PUT", body: { text } })}>
            <CheckCircle2 size={16} /> Check and generate BPMN
          </button>
          <button className="primary" disabled={!accepted || state.watchdogApproved || !!busy} onClick={() => run("Approving the workflows", "/api/watchdog/approve", { method: "POST" })}>
            <ShieldCheck size={16} /> {state.watchdogApproved ? "Workflows approved" : "Approve workflows"}
          </button>
        </>
      }
    >
      {!ready && <p className="muted">Create a binding first; the watchdog may act only through validated bindings.</p>}
      {ready && (
        <p className="muted">
          The checks accept only workflows that read bound values, write writable attributes and end on every path. Review the BPMN models and
          approve them.
        </p>
      )}
      {busy && state.watchdogProgress && <DraftProgress progress={state.watchdogProgress} />}
      <div className="watchdog">
        <div>
          <h3>Fault rules</h3>
          <pre className="spec">{state.faultRules || "No fault rules uploaded."}</pre>
        </div>
        <div>
          <h3>Workflow editor</h3>
          <textarea value={text} onChange={(event) => setText(event.target.value)} spellCheck={false} rows={16} />
        </div>
      </div>
      {state.watchdogErrors.length > 0 && state.watchdogRevisions === 3 && (
        <p className="muted">The checks still reject the workflows after three revisions by the LLM. Resolve the open findings below.</p>
      )}
      <Errors errors={state.watchdogErrors} />
      {state.openRules.length > 0 && (
        <div className="unbound-report">
          <h3>Open fault rules ({state.openRules.length})</h3>
          <ul>
            {state.openRules.map((rule) => (
              <li key={rule.number}>
                Fault rule {rule.number} needs {rule.attributes.join(", ")}, which no node provides
                {rule.reasons.some(Boolean) ? ` (${rule.reasons.filter(Boolean).join("; ")})` : ""}. No watchdog implements it; it stays an open
                case.
              </li>
            ))}
          </ul>
        </div>
      )}
      {state.workflows.map((workflow) => (
        <div key={workflow.processId} className="workflow">
          <p>
            <strong>{workflow.name}</strong> {workflow.covers.length > 0 ? `covers fault rule ${workflow.covers.join(", ")}` : "is called by other workflows"}:{" "}
            {workflow.reactions.join(" → ")}
            {workflow.escalations.length > 0 && <> → escalate “{workflow.escalations.map((e) => e.message).join("”, “")}”</>}{" "}
            <a href={`/api/watchdog/bpmn/${workflow.file}`} target="_blank" rel="noreferrer">
              <Link2 size={14} /> BPMN
            </a>
          </p>
          <BpmnView file={workflow.file} text={state.watchdogText} />
        </div>
      ))}
    </Panel>
  );
}

const DRAFT_STATUS = {
  waiting: ["waiting", ""],
  written: ["written, checking", ""],
  accepted: ["accepted", "bound"],
  rejected: ["rejected", "review"],
  unchecked: ["not checked: syntax error", ""],
  open: ["open case: needs an unbound value", "unbound"],
  missing: ["no workflow yet", "unsupported"]
};

/** Progress of the LLM draft: the round and, per fault rule, the state of its workflow. */
function DraftProgress({ progress }) {
  const rounds = progress.maxRevisions + 1;
  const accepted = progress.rules.filter((rule) => rule.status === "accepted" || rule.status === "open").length;
  const step = progress.step === "writing" ? (progress.revision ? "The LLM revises the workflows" : "The LLM writes the workflows") : "The checks run";
  return (
    <div className="draft-progress">
      <div className="progress-label">
        <span>
          Round {progress.revision + 1} of at most {rounds}: {step}…
        </span>
        <span>
          {accepted} of {progress.rules.length} fault rules accepted or open
        </span>
      </div>
      <div className="progress-track">
        <div className="progress-fill" style={{ width: `${(accepted / Math.max(progress.rules.length, 1)) * 100}%` }} />
        {progress.step === "writing" && <div className="progress-pulse" />}
      </div>
      <ul className="rules">
        {progress.rules.map((rule) => (
          <li key={rule.number}>
            <span className={`badge ${DRAFT_STATUS[rule.status][1]}`}>{DRAFT_STATUS[rule.status][0]}</span> {rule.number}. {rule.title}
            {rule.workflows.length > 0 && <span className="muted"> · {rule.workflows.join(", ")}</span>}
            {rule.findings > 0 && <span className="muted"> · {rule.findings} findings</span>}
          </li>
        ))}
      </ul>
      {progress.otherFindings > 0 && (
        <p className="muted">{progress.otherFindings} findings outside a workflow or in helper workflows that cover no fault rule.</p>
      )}
    </div>
  );
}

function BpmnView({ file, text }) {
  const container = useRef(null);
  useEffect(() => {
    const viewer = new NavigatedViewer({ container: container.current });
    const canvas = viewer.get("canvas");
    fetch(`/api/watchdog/bpmn/${file}`)
      .then((response) => response.text())
      .then((xml) => viewer.importXML(xml))
      .then(() => canvas.zoom("fit-viewport"))
      .catch(() => {});
    // The window can be dragged larger at its bottom corner; the diagram then fills the new size.
    const observer = new ResizeObserver(() => {
      canvas.resized();
      canvas.zoom("fit-viewport");
    });
    observer.observe(container.current);
    return () => {
      observer.disconnect();
      viewer.destroy();
    };
  }, [file, text]);
  return <div className="bpmn" ref={container} />;
}

function DashboardPanel({ state, busy, run }) {
  const [text, setText] = useState(state.dashboardText || "");
  useEffect(() => setText(state.dashboardText || ""), [state.dashboardText]);
  const ready = state.bindingApproved && (!state.watchdogText || state.watchdogApproved);
  const valid = !!state.dashboardText && state.dashboardErrors.length === 0;
  return (
    <Panel
      step="4"
      title="Specify dashboard"
      actions={
        <>
          <button disabled={!ready || !!busy} onClick={() => run("The LLM specifies the dashboard", "/api/dashboard/draft", { method: "POST" })}>
            <Sparkles size={16} /> Specify with the LLM
          </button>
          <button disabled={!state.bindingText || !text.trim() || !!busy} onClick={() => run("Checking the dashboard", "/api/dashboard", { method: "PUT", body: { text } })}>
            <CheckCircle2 size={16} /> Check dashboard
          </button>
          <button className="primary" disabled={!valid || state.dashboardApproved || !!busy} onClick={() => run("Approving the dashboard", "/api/dashboard/approve", { method: "POST" })}>
            <ShieldCheck size={16} /> {state.dashboardApproved ? "Dashboard approved" : "Approve dashboard"}
          </button>
        </>
      }
    >
      <p className="muted">
        {ready
          ? "The LLM selects the cards from the approved binding and workflows. If the checks still reject its dashboard after three revisions, the tool generates the default dashboard with one card per bound attribute. Approve the dashboard after the preview; any change ends the approval."
          : "Approve the binding and the watchdog workflows first."}
      </p>
      <textarea value={text} onChange={(event) => setText(event.target.value)} spellCheck={false} rows={12} />
      <Errors errors={state.dashboardErrors} />
      {state.dashboardViews && <DashboardPreview state={state} />}
    </Panel>
  );
}

/** A sketch of the Home Assistant views that deployment would write. */
function DashboardPreview({ state }) {
  const units = Object.fromEntries(state.attributes.map((attribute) => [attribute.key, attribute.unit]));
  return (
    <div className="preview">
      <h3>Preview of the dashboard</h3>
      {state.dashboardViews.map((view) => (
        <div key={view.title} className="preview-view">
          <h3>{view.title}</h3>
          <div className="preview-cards">
            {view.cards.map((card, index) => (
              <PreviewCard key={index} card={card} units={units} />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

function PreviewCard({ card, units }) {
  const keys = card.attributes || [];
  const unit = keys[0] && units[keys[0]] ? ` ${units[keys[0]]}` : "";
  let body = null;
  if (card.card === "gauge") {
    const span = card.max - card.min || 1;
    const percent = (value) => `${Math.min(100, Math.max(0, ((value - card.min) / span) * 100))}%`;
    const [warning, critical] = card.severity || [card.max, card.max];
    body = (
      <>
        <div className="gauge-bar" style={{ "--warning": percent(warning), "--critical": percent(critical) }} />
        <span className="muted">
          {card.min} – {card.max}
          {unit}
          {card.severity && ` · warning ${warning}, critical ${critical}`}
        </span>
      </>
    );
  } else if (card.card === "history") {
    body = <span className="muted">over {card.hours >= 1 ? `${card.hours} h` : `${Math.round(card.hours * 60)} min`}</span>;
  } else if (card.card === "button" || card.card === "input") {
    const visible = card.visible?.attribute ? ` · shown while ${card.visible.attribute} = ${JSON.stringify(card.visible.value)}` : "";
    body = (
      <span className="muted">
        {card.confirm ? `asks “${card.confirm}”` : "no confirmation"}
        {visible}
      </span>
    );
  } else if (card.card === "alarm") {
    body = <span className="muted">shown while the watchdog reacts</span>;
  } else if (card.card === "toggle") {
    body = <span className="muted">on / off</span>;
  } else if (unit) {
    body = <span className="muted">in{unit}</span>;
  }
  return (
    <div className="preview-card">
      <span className="card-kind">{card.card}</span>
      <span className="card-name">{card.card === "alarm" ? card.workflow : keys.join(", ")}</span>
      {body}
    </div>
  );
}

function DeployPanel({ state, busy, run }) {
  const [endpoint, setEndpoint] = useState(state.deployment?.endpoint || state.defaultEndpoint);
  const loadedExample = useRef(state.example);
  useEffect(() => {
    // A newly loaded example brings its own simulator endpoint.
    if (state.example !== loadedExample.current) setEndpoint(state.defaultEndpoint);
    loadedExample.current = state.example;
  }, [state.example]);
  const runtime = state.runtime;
  const approved = state.bindingApproved && (!state.watchdogText || state.watchdogApproved) && state.dashboardApproved;
  const simulation = state.simulation;
  const [scenario, setScenario] = useState(simulation?.active || "");
  useEffect(() => setScenario(simulation?.active || simulation?.scenarios[0]?.name || ""), [simulation?.active, state.example]);
  const description = simulation?.scenarios.find((item) => item.name === scenario)?.description;
  return (
    <Panel
      step="5"
      title="Deploy & monitor"
      actions={
        <>
          <button className="primary" disabled={!approved || !!busy} onClick={() => run("Deploying", "/api/deploy", { method: "POST", body: { endpoint } })}>
            <Rocket size={16} /> Deploy
          </button>
        </>
      }
    >
      {!approved && <p className="muted">Deployment needs the approved binding, the approved workflows if there are any, and the approved dashboard.</p>}
      <label className="endpoint">
        OPC UA endpoint
        <input value={endpoint} onChange={(event) => setEndpoint(event.target.value)} />
      </label>
      {simulation && (
        <div className="scenario-picker">
          <span>Simulator scenario</span>
          <select value={scenario} onChange={(event) => setScenario(event.target.value)} disabled={!!busy}>
            {simulation.scenarios.map((item) => (
              <option key={item.name} value={item.name}>
                {item.name}
              </option>
            ))}
          </select>
          <button disabled={!scenario || !!busy} onClick={() => run(`Starting scenario ${scenario}`, "/api/simulator/scenario", { method: "POST", body: { name: scenario } })}>
            <Rocket size={16} /> Start scenario
          </button>
          {simulation.active && <span className="muted">running: {simulation.active}</span>}
          {description && <p className="muted">{description} The simulator resets all values to the NodeSet values first.</p>}
        </div>
      )}
      {state.deployment ? (
        <div className="runtime">
          <p>
            Deployed {new Date(state.deployment.deployedAt).toLocaleString()}: {state.deployment.bindings.length} bindings, {state.deployment.workflows.length} watchdog workflows.
            Open the <a href="http://localhost:8123/digital-twin">Home Assistant dashboard</a> or{" "}
            <a href="http://localhost:8180/operate/processes?active=true&incidents=true&completed=true&canceled=true">Camunda Operate</a>.
            Operators answer watchdog questions in <a href="http://localhost:8180/tasklist">Camunda Tasklist</a>.
          </p>
          <UnboundReport report={state.deployment.unbound} title="Unbound attributes: no value at runtime" />
          {state.deployment.openRules?.length > 0 && (
            <p className="muted">Open fault rules without a watchdog: {state.deployment.openRules.map((rule) => rule.number).join(", ")}.</p>
          )}
          {state.deployment.processes?.length > 0 && (
            <p className="muted">
              Deployed to Camunda: {state.deployment.processes.map((p) => `${p.processId} (version ${p.version})`).join(", ")}. A watchdog
              instance runs only while its condition holds; instances that end at once are listed under finished instances.
            </p>
          )}
          {runtime ? (
            <>
              <p>
                Adapter: <span className={`badge ${runtime.connected ? "bound" : "review"}`}>{runtime.connected ? "connected" : "disconnected"}</span>
                {runtime.lastError && <span className="muted"> · last error: {runtime.lastError}</span>}
              </p>
              {Object.entries(runtime.disabled || {}).map(([key, reason]) => (
                <div key={key} className="banner">
                  <AlertTriangle size={16} /> {key}: {reason}
                </div>
              ))}
              <ul className="rules">
                {Object.entries(runtime.rules || {}).map(([processId, ruleState]) => (
                  <li key={processId}>
                    <span className="mono">{processId}</span>: {ruleState}
                  </li>
                ))}
              </ul>
            </>
          ) : (
            <p className="muted">Waiting for the runtime adapter.</p>
          )}
        </div>
      ) : (
        <p className="muted">Deployment sends the BPMN watchdogs to Camunda 8, configures the runtime adapter and writes the Home Assistant dashboard.</p>
      )}
    </Panel>
  );
}
