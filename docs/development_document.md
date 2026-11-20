# Development Document

## 1. Purpose

AIDE (AI-Assisted DT Engineering Tool) maps the requirements of a digital twin (DT) service onto the interfaces
that a machine offers through OPC UA, generates BPMN watchdogs from fault rules,
and configures a Home Assistant dashboard. LLMs interpret names, descriptions and
informal text. Deterministic code filters candidates, parses and checks every LLM
answer, derives conversions and generates BPMN. Every LLM answer is a text in one
of the MontiCore languages, never JSON. The checks return their findings to the LLM
for up to three revisions. The engineer resolves the cases that the checks still do
not accept and approves the binding, the workflows and the dashboard
before deployment.

| Input | Output |
|---|---|
| Service requirements as a class diagram (`.cd`) | Binding specification (`.bind`) |
| OPC UA NodeSet2 file (`.xml`) | Executable BPMN watchdogs |
| Fault rules in natural language | Home Assistant entities and dashboard (`.dash`) |

The watchdog monitors the plant and reacts to deviations. The production process
stays in the PLC. A watchdog complements certified safety functions and does not
replace them.

## 2. Architecture

```text
frontend ──HTTP──> backend ──subprocess──> languages (dtlang.jar)
                     │  └──HTTPS──> LLM chat completions API (Groq or Mistral)
                     │
                     ├──> Camunda 8: deploy BPMN
                     ├──> generated/runtime/deployment.json ──> adapter
                     └──> docker/homeassistant/dt-dashboard.yaml

adapter <──OPC UA──> machine or simulator
adapter <──REST────> Camunda 8: messages, service-task and listener jobs
adapter <──MQTT────> Home Assistant: discovery, state, commands
backend ──files───> simulators: scenario selection (generated/runtime/scenario/<example>)
```

| Component | Responsibility |
|---|---|
| `languages/` | MontiCore grammars, NodeSet reader, unit library, compatibility filter, CoCos, BPMN generator. A command-line tool prints JSON. |
| `backend/` | Stores the inputs, calls the LLM, keeps the engineer's decisions and approvals, deploys. |
| `frontend/` | Shows the five steps: specify, bind and validate, specify watchdog workflows, specify the dashboard, deploy and monitor. It renders the generated BPMN with bpmn-js and a preview of the dashboard. |
| `adapter/` | Executes the deployed binding and the watchdog jobs, publishes value changes and met conditions to Camunda, and records value changes in SQLite. |
| `opcua_simulator/` | Serves a NodeSet with simple behavior and timed scenarios. One container runs per example. |

All checks of a language live in one place: the MontiCore tool. The backend and
the adapter do not repeat them.

The LLM is the Groq or Mistral model named by `AI_PROVIDER` and `AI_MODEL`, by default `openai/gpt-oss-120b` on Groq. The
backend logs every call with the provider,
the configured model, the model version that the API reports, a hash of the prompt,
the token usage and the time. An API or network failure is shown in the front end
as a system error and never becomes a review reason.

## 3. Languages

The four languages share `DTBasics.mc4`: qualified attribute references such as
`Robot.currentPose.x`, units and `{readOnly}`. A unit is a symbol such as `mK` or a
bracketed unit such as `[mm/s]`.

### 3.1 Service requirements

```text
classdiagram CellMonitoring {
  class CncMachine {
    double spindleTemperature in degC {readOnly};
    boolean startCycle;
  }
  class Robot {
    Pose currentPose {readOnly};
  }
  class Pose { double x in mm; double y in mm; double z in mm; }
}
```

The service requirements are a variant of CD4A: `ServiceRequirements.mc4` extends
the MontiCore grammar `CD4Analysis` and redefines only the attribute, which also
states a unit after `in` and the access mode `{readOnly}`. The tool builds the CD4A
symbol table and resolves classes and attribute types in it. The binding refers to
these classes and attributes by name, for example `Robot.currentPose.x`.

Each attribute has a type, an optional unit and an access mode. Supported types
are `boolean`, `int`, `long`, `float`, `double` and `String`.

| Attribute | Kind | Binding |
|---|---|---|
| `{readOnly}` | input | bound to one node that the service reads |
| not read-only | output | bound to one node that the service may write |
| type is a class of the diagram | structured | expanded; its fields are bound one by one and inherit `{readOnly}` |
| generic type such as `Map<String,Foo>` | unsupported | not bound |

A class that is used as an attribute type is a structured type. All other classes
are top-level classes of the service. CoCos reject duplicate names, unknown types,
unknown units, units on non-numeric attributes, initial values, inheritance and CD4A
elements other than classes, such as associations.

### 3.2 Binding specification

```text
binding CellMonitoringBinding for CellMonitoring, RobotCellNodeSet {
  CncMachine.spindleTemperature -> "ns=1;s=Cell/CNC/SpindleTemp" convert K to degC;
  CncMachine.spindleSpeed -> "ns=1;s=Cell/CNC/SpindleSpeed" widen Int32 to double;
  SafetyController.emergencyStop -> "ns=1;s=Cell/Safety/SetEmergencyStop" via method;
  Conveyor.run -> "ns=1;s=Cell/Conveyor/RunCommand" map true -> 1, false -> 0 confirmed;
  Robot.stopMotion -> "ns=1;s=Cell/Robot/StopMotion" map true -> call confirmed;
  Robot.batteryLevel unbound "only available through getter method (not supported): Cell/Robot/GetBatteryLevel";
}
```

An entry binds a service attribute (`Class.attribute`) or a workflow requirement
(`Workflow.attribute`, Section 3.3). NodeIds use the namespace indexes of the
NodeSet file. Conversion clauses follow
the direction of the data flow: from node to attribute for inputs, and from
attribute to node for outputs.

| Clause | Meaning |
|---|---|
| `convert <unit> to <unit>` | Unit conversion. The tool derives factor and offset from the UCUM unit library. |
| `widen <type> to <type>` | Lossless type widening. |
| `via method` | The output is written by calling a method with one argument. |
| `map true -> 1, false -> 0 confirmed` | Value mapping of a Boolean output to an integer node. |
| `map true -> call confirmed` | Value mapping of a Boolean output to a method without argument: setting the output to `true` calls the method; `false` cannot be expressed. |

Only the engineer binds through a value mapping, and `confirmed` marks that
decision (Section 4.3).

`<attribute> unbound "<reason>";` records that no node can provide the attribute;
the reason is optional. Only the engineer writes such an entry (Section 4.4). An
attribute has at most one entry, bound or unbound.

### 3.3 Watchdog workflows

A watchdog workflow is a BPMN graph in a compact textual notation. Each line is
one element with a unique id; every element names its successors.

```text
workflow AxisOverheat uses CellMonitoringBinding covers 3 {
  requires double motorTemperature in degC {readOnly};
  requires double speedOverride in [%];
  start    s     on change motorTemperature -> g1
  xor      g1    motorTemperature > 80 ? t1 : done
  timer    t1    10 s -> g2
  xor      g2    motorTemperature > 80 ? slow : done
  task     slow  set speedOverride = 50 -> e1
  eventgw  e1    motorTemperature < 70 ? done : after 60 s ask
  user     ask   "Robot motor still above 70 °C. Stop the robot motion?" yes stop : no esc
  task     stop  set stopMotion = true -> esc
  escalate esc   "Robot motor overheating not resolved: inform the shift supervisor"
  end      done
}
```

`requires` declares a workflow requirement: a value that the watchdog needs but the
service requirements lack, in the attribute notation of Section 3.1 with a
primitive type. The tool binds it with the same pipeline as a service attribute
(Section 4) and stores it in the binding specification as
`<Workflow>.<attribute>`. Only its own workflow sees it.

`openrule <number> needs <attribute>, …;` stands outside any workflow. It states
that fault rule `<number>` cannot be implemented because it needs attributes
that are marked as unbound (Section 4.4); the fault rule stays an open case
instead of a workflow.

`covers` lists the numbers of the fault rules that the workflow handles. A
workflow whose start event has no trigger is only called by other workflows and
may omit `covers`. Conditions compare attributes with literals and combine them
with `and` and `or`. An attribute may omit its class name if the name is unique.
Time units are `s` and `min`. Values use the unit of the attribute.

The elements form the BPMN element catalogue. Each entry has a usage rule, a
check and a generator mapping:

| Notation | BPMN element | Usage rule and check | Generated as |
|---|---|---|---|
| `start <id> on change <values> -> <next>` | message start event | exactly one start per workflow; triggers have a known source | one message start event per trigger value, with the message `changed-<attribute>` of that bound value |
| `start <id> every <duration> -> <next>` | timer start event | positive duration | timer start event with a cycle |
| `start <id> on signal "<name>" -> <next>` | signal start event | a workflow throws the signal | signal start event |
| `start <id> -> <next>` | none start event | a call activity calls the workflow | none start event |
| `timer <id> <duration> -> <next>` | timer intermediate event | positive duration | timer catch event |
| `signal <id> "<name>" -> <next>` | signal intermediate throw event | – | signal throw event |
| `xor <id> <condition> ? <yes> : <no>` | exclusive gateway | condition over values with a known source | read task and exclusive gateway with a FEEL condition |
| `inclusive <id> <condition> ? <next>, … : <fallback>` | inclusive gateway (split) | conditions over values with a known source; all branches meet again at one inclusive join | read task and inclusive gateway with FEEL conditions and a default flow |
| `inclusive <id> -> <next>` | inclusive gateway (join) | – | inclusive gateway |
| `eventgw <id> <condition> ? <met> : after <duration> <deadline>` | event-based gateway | condition with a known source; always a deadline | event-based gateway, message catch event `<processId>-<id>`, timer catch event |
| `eventgw <id> signal "<name>" ? <met> : after <duration> <deadline>` | event-based gateway | a workflow throws the signal; always a deadline | event-based gateway, signal catch event, timer catch event |
| `parallel <id> -> <next>, …` | parallel gateway | branches of a split meet again at one parallel gateway | parallel gateway |
| `task <id> set <attribute> = <value> -> <next>` | service task | writes a value of the right type and range to a writable bound attribute | service task of type `opcua-write` with the headers `attribute`, `value` and `binding` (the NodeId) |
| `decide <id> set <attribute> from <value> { <op> <limit> : <result>; … else <result> } -> <next>` | business rule task | threshold table: input with a known source, type-correct results for a writable bound attribute | read task, business rule task with a DMN decision table (hit policy FIRST), `opcua-write` task with the header `variable` instead of `value` |
| `user <id> "<question>" yes <next> : no <next> <boundaries>` | user task | yes/no question to the operator | Camunda user task with a generated form, exclusive gateway on the answer |
| `call <id> <workflow> <boundaries> -> <next>` | call activity | the called workflow has a none start event; no call cycles | call activity on `Watchdog_<workflow>` |
| `after <duration> -> <next>` | timer boundary event | on `user` or `call` | interrupting timer boundary event |
| `when <condition> -> <next>` | message boundary event | on `user` or `call`; condition with a known source | interrupting message boundary event `<processId>-<id>` |
| `on signal "<name>" -> <next>` | signal boundary event | on `user` or `call`; a workflow throws the signal | interrupting signal boundary event |
| `escalate <id> "<message>"` | escalation end event | – | end event with an escalation definition and a start execution listener of type `watchdog-escalate` |
| `terminate <id>` | terminate end event | – | terminate end event |
| `end <id>` | end event | – | none end event |

Table 2 of the paper also lists event subprocesses, embedded subprocesses and
multi-instance activities. The notation does not offer them: the first two need
nested blocks in a language in which every element names its successors, and
multi-instance needs a collection, which the service requirements do not
support. Message and signal catch events exist only inside an event-based gateway
and as boundary events, so that every wait has a deadline; boundary events exist
only on user tasks and call activities and always interrupt them.

### 3.4 Dashboard

```text
dashboard CellDashboard uses CellMonitoringBinding {
  view "Robot" {
    gauge motorTemperature min 20 max 100 severity 70, 80;
    history motorTemperature, speedOverride over 1 h;
    alarm AxisOverheat;
    button stopMotion confirm "Stop the robot motion?";
  }
  hide currentPose.x, currentPose.y, currentPose.z;
}
```

A dashboard groups cards into views. The cards form the card catalogue
(`backend/app/dashboard.py`):

| Card | Check | Home Assistant card |
|---|---|---|
| `gauge <attribute> min <n> max <n> severity <warning>, <critical>;` | numeric attribute; min < max; severity values inside the range | gauge with green, yellow and red ranges; a critical value below the warning value colors low values red, such as `severity 10, 5` for the remaining tool life |
| `history <attributes> over <n> s\|min\|h;` | numeric attributes | history graph |
| `tile <attribute>;` | – | tile |
| `toggle <attribute>;` | writable `boolean` bound to a variable | tile with a toggle |
| `button <attribute> confirm "<question>" [when …];` | writable `boolean` bound via a method; always with `confirm` | button that asks before it calls the method |
| `input <attribute> [min <n> max <n>] [confirm "<question>"] [when …];` | writable attribute that is not `boolean`; `confirm` if it is bound via a method; a range needs a numeric attribute and min < max | for an integer range of at most 12 values, one button per value that asks and then sends it, for example the cells 1 to 9 of the YuMi board; otherwise a button that asks and then opens the control of the entity (method), or an entities row with the control (variable) |
| `alarm <workflow>;` | a watchdog workflow of the specification | conditional card that shows the watchdog sensor while its state is not `monitoring` |
| `hide <attributes>;` | – | – |

`when <attribute> == <value>` shows a button or input only while a readable bound
value equals the literal, for example the restart button of the YuMi only while
the robot is stopped. Deployment wraps the control in a conditional card.

Every referenced attribute must be bound, and every bound attribute must be shown
or hidden. The unit of a card comes from the binding. No card offers a control for
a read-only attribute, and every control that calls a method asks the operator
for confirmation.

## 4. Binding generation

### 4.1 Compatibility filter

The filter classifies every pair of attribute and node as compatible,
convertible or incompatible, and removes only incompatible nodes. It never uses
names.

- A read-only attribute needs a readable variable (the CurrentRead bit of
  `AccessLevel`), even if the node is also writable. Methods are incompatible
  (getter methods are not supported).
- A writable attribute needs a writable variable (CurrentWrite), or a method with
  exactly one scalar argument of a compatible type. A method without argument is
  incompatible, because a call cannot carry a value.
- Arrays, objects, properties and unsupported data types are incompatible.
- Types: an exact type pair is compatible. Lossless widening in the direction of
  the data flow is convertible. Every other pair is incompatible, including a
  writable `boolean` on an integer node.
- Units are compared by physical dimension. The same unit is compatible. The
  same dimension with another unit is convertible, if the target is a
  floating-point value. Another dimension is incompatible. A missing unit never
  removes a node. A unit that cannot be parsed keeps the node but is a finding
  that blocks a binding to it.

Two incompatible pairs can still be bound, but only by the engineer through a
value mapping: a writable `boolean` on a method without argument
(`map true -> call`), and a writable `boolean` on an integer variable or method
argument (`map true -> 1, false -> 0`). The filter lists these nodes separately as
*manual* options of the attribute; the LLM never sees them.

The NodeSet reader takes units from the `EngineeringUnits` property, as the UNECE
common code in `EUInformation.UnitId`, and translates the code to UCUM. The display
name of a unit is ignored. An unknown code is a finding. Method arguments come from
`InputArguments`. In the service requirements, common spellings such as `°C`,
`degC` and `rpm` are translated to UCUM codes.

### 4.2 Matching cases

| Requirement | OPC UA offer | Handling |
|---|---|---|
| read-only attribute | readable variable, same type and unit | bound automatically |
| read-only attribute | same dimension, other unit (ms vs. s) | bound with unit conversion |
| read-only `double` | `Int32` variable | bound with type conversion |
| read-only `int` | `Double` variable | engineer (lossy) |
| writable attribute | writable variable | bound automatically |
| writable attribute | read-only variable | engineer (no write access) |
| writable `boolean` | method `SetEmergencyStop(Boolean)` | bound as method call |
| writable `boolean` | method `StopMotion()` | engineer binds it with `map true -> call` (one value only) |
| writable `boolean` | `Int32` variable (0/1) | engineer binds it with a value mapping |
| read-only attribute | getter method only | not supported |
| structured type | object with child variables | bound field by field |
| `Map<String,Foo>` | – | not supported |
| value from several nodes | e.g. sum of three phases | not supported |

`examples/robot_cell/` contains one instance of every row except the lossy `int` reading, the
missing write access and the generic type. The two unsupported offers in it, the getter
method `GetBatteryLevel()` for `Robot.batteryLevel` and the three phases for
`Cell.totalPower`, stay open cases that the engineer marks as unbound (Section 4.4).

### 4.3 Semantic selection and validation

The backend sends one LLM request per top-level class, and one per workflow for
workflow requirements. The request lists each attribute with type, unit and
access, and its candidates: NodeId, browse path, description, data type, unit,
access, classification and required conversion clauses. The LLM answers with a
binding specification and leaves out attributes without a fitting candidate. The
backend removes any `confirmed` keyword and any `unbound` entry from the answer.

The answer is checked line by line: if it does not parse as a whole, every line
is parsed and checked on its own. The CoCos check every entry:

- the attribute exists in the service or workflow requirements and is an input
  or output;
- the attribute is bound at most once;
- the NodeId exists in the NodeSet;
- the pair is not incompatible, unless the entry states the value mapping of a
  manual option; the node's unit can be parsed;
- the entry states exactly the required conversions, with units that match the
  data flow;
- a value mapping is confirmed, and it maps `true` and `false` to two different
  integers or, for a method without argument, exactly one value to `call`.

Valid lines are accepted. Only the rejected lines go back to the LLM with their
findings, at most three times. Every attribute without a valid binding goes to
the engineer with one of these reasons: no candidate, the LLM found no fit, or
rejected after three revisions (with the findings).

The engineer chooses one of the attribute's candidates or manual options, or
marks the attribute as unbound if the machine cannot provide it (Section 4.4).
The backend writes the entry with the clauses from the filter and adds
`confirmed` to a value mapping, because the engineer's choice is the
confirmation. The engineer cannot choose any other incompatible node. Finally the
engineer approves the complete binding specification once, which is possible
only when every bindable attribute, including every workflow requirement, is
bound or marked as unbound. The backend stores a hash of the approved
specification; any later change ends the approval, and deployment needs an
approved, unchanged specification.

### 4.4 Unbound attributes

An attribute that no node can provide is not bound to the next type-compatible
node; the engineer marks it as unbound.

- **Excluded nodes.** The filter result lists, per attribute, the nodes that are
  excluded only by an unsupported rule (`unsupported` in `candidates.json`): a
  getter method that takes no argument and returns one scalar value of a fitting
  type (*only available through getter method (not supported)*), or an array
  variable whose element type, access and unit fit (*only available as an array
  (not supported)*). The engineer sees them greyed at the top of the node list
  and in the reason column, and cannot select them. The classification of the
  pair does not change, and the LLM never sees these nodes. Since the filter does
  not use names, such a node is listed for every attribute whose type fits; in
  the robot cell, `GetBatteryLevel()` is listed for all read-only numeric
  attributes, and the engineer decides by its description.
- **Marking.** Every attribute in the binding table has the action *Mark as
  unbound* with an optional reason. If an excluded node exists, the reason is
  prefilled with its rule and path. The backend writes `<attribute> unbound
  "<reason>";` into the binding specification (Section 3.2); *Remove* deletes the
  entry again, and the attribute becomes an open case. The LLM cannot mark an
  attribute as unbound.
- **Approval.** The binding can be approved once every attribute is bound or
  unbound. The approved specification contains the unbound attributes with
  their reasons; the approval covers them like any other entry.
- **Watchdogs.** The operation catalogue of the LLM contains only bound
  attributes. The unbound service attributes are listed separately as
  unavailable values, which the LLM must neither use nor declare as workflow
  requirements. The workflow check reports every use of an unbound attribute in a
  trigger, condition, threshold table or task (Section 5.2). A fault rule that
  cannot be implemented without an unbound attribute becomes an open case with
  `openrule <number> needs <attribute>;` (Section 3.3). A workflow requirement that
  repeats an unbound service attribute is returned to the LLM as a finding and,
  if it remains, is marked as unbound with the same reason, so that no other node
  replaces the missing value.
- **Dashboard.** The LLM receives the unbound attributes as unavailable, and the
  dashboard check reports every card that shows one. The default dashboard and the
  Home Assistant entities contain only bound attributes.
- **Runtime.** The adapter gives an unbound attribute no value (Section 7).
- **Report.** The binding step and the deployment list every unbound attribute
  with its reason, the excluded nodes, the open fault rules that need it, and who
  can close the gap: the machine builder by providing a readable variable (or, for
  an output, a writable variable or a method with one argument), or the service
  by relaxing or dropping its requirement. Deployment stores the report under
  `unbound` and the open fault rules under `openRules` in
  `generated/runtime/deployment.json`.

## 5. Watchdog workflows

### 5.1 Specification by the LLM

The LLM specifies the structure of each workflow: which conditions to check, how
long to wait, which reactions to try in which order, where the operator decides,
and when to escalate. It receives:

- the fault rules, numbered by the domain expert;
- the element catalogue: notation and usage rule of each element
  (`backend/app/watchdog.py`);
- the operation catalogue: every readable value and every writable attribute of
  the service binding, with type, unit and the bound node, because fault rules
  often name the machine's nodes and methods.

If a fault rule needs a value that the catalogue lacks, the LLM declares a
workflow requirement. In each round the backend first binds new workflow
requirements (Section 4.3), then checks the workflows and returns all findings to
the LLM, which revises the workflows up to three times. After three failed
revisions the engineer gets the fault rules, the last draft and the open findings.
The engineer can edit the text and check again; saving binds new workflow
requirements the same way, so the edited text passes the binding step and the
checks again. A requirement that repeats a bound service attribute (its name is
the attribute name, `stop`, or the class and attribute name, `robotStop`, for
`Robot.stop`) is never bound on its own: in a draft, the backend returns it to the
LLM as a finding before the binding step; in a saved or last draft, it is bound to
the node of that service attribute, and a value mapping that the engineer
confirmed for that node stays confirmed. So the binding, and with it its approval,
changes only for values that the service really lacks. Finally the engineer reviews the checked workflows as BPMN diagrams
and approves them. The approval covers the workflows and the binding; a change of either
ends it.

### 5.2 Checks

The CoCos accept the workflows only if:

- each workflow refers to the current binding by name;
- every workflow requirement is valid and bound; otherwise the finding is
  *missing source*;
- no trigger, condition, threshold table or task uses an attribute that is marked
  as unbound; the finding names the reason and suggests an `openrule`;
- an `openrule` names an existing fault rule that no workflow covers, and only
  attributes that are marked as unbound;
- every value that a trigger, condition or threshold table reads has a known
  source: a service attribute or a workflow requirement of the same workflow,
  bound to a readable node;
- every caught signal has a known source: a workflow of the specification
  throws it;
- every service task and threshold table writes a writable bound attribute with
  a value of its type and inside the range of that type; an attribute bound to a
  method without argument is only set to the value that calls it;
- every comparison fits the attribute type;
- durations are positive and use `s` or `min`;
- a call activity calls an existing workflow with a none start event, calls form
  no cycle, and every workflow with a none start event is called;
- the graph is sound: exactly one start event, every successor is declared,
  every element is reachable from the start, a path from every element reaches
  an end, every event-based gateway has a deadline (the notation requires it),
  and the branches of a parallel split (inclusive split) meet again at one
  parallel gateway (inclusive join) before any end;
- traceability: every workflow with `covers` has at least one condition (`xor`,
  `inclusive`, `eventgw`, `decide`) and one reaction (`task`, `decide`, `user`,
  `escalate`), counting the elements of the workflows it calls, and every
  numbered fault rule is covered by such a workflow or stated as an `openrule`.

Every change of the binding or the fault rules checks the workflows again.

### 5.3 BPMN generation

The generator serializes each workflow to an executable BPMN process with the ID
`Watchdog_<workflow name>`, in a file named after the workflow in kebab case, for
example `axis-overheat.bpmn` (`examples/robot_cell/axis-overheat.bpmn` is the
reference output for the example binding `examples/robot_cell/binding.bind`). It adds element identifiers, message, signal,
escalation and timer definitions, one DMN file per threshold table, user task
forms and a layout that places each element in the column of its distance from
the start event. Boundary events sit on the lower edge of their host.

Process variables carry the values that conditions compare. A start message on
value change and every condition message carry the current values. An
exclusive or inclusive gateway or a threshold table that does not directly
follow such a start event is preceded by a `read current values` service task,
because a timer or a reaction has passed since the values were read. Gateways
evaluate the condition in FEEL; a missing value makes the condition false.

Each bound value has one change message, `changed-<attribute>`, which starts every
workflow that it triggers. The adapter publishes it with the constant correlation
key `dt`, so each watchdog has at most one active instance. The correlation key of
the condition messages is the process ID, written as a literal in the BPMN model,
so a subscription needs no process variable.

## 6. Dashboard

The deployment creates one Home Assistant entity per bound attribute and one
entity per watchdog workflow:

| Attribute | Entity |
|---|---|
| read-only `boolean` | binary sensor |
| read-only numeric or `String` | sensor with unit |
| writable `boolean` bound to a method without argument | button |
| writable `boolean` | switch |
| writable numeric | number, with the range of its dashboard input and step 1 for integers |
| writable `String` | text |
| watchdog workflow | sensor with the workflow state |

A button calls its method on each press and keeps no state. Any other control
that is bound to a method has no value to read back and is optimistic.

The LLM selects cards from the card catalogue (Section 3.4) and groups them into
views. It needs the approved binding and, if there are workflows, the approved
workflows. It receives the bound attributes with type, unit, access and node
class, the card catalogue and the text of the workflows, whose conditions
show which values belong together and where critical ranges start. The checks
return their findings for up to three revisions. If the checks still reject the
dashboard after the third revision, the tool writes the default dashboard
instead: one view per class with one card per bound attribute (`tile` for a
read-only attribute, `toggle`, `button` or `input` for a writable one) and a view
with one `alarm` per workflow. So a dashboard always exists. The engineer can
edit and check the dashboard and approves it after the preview; the approval
covers the dashboard, the binding and the workflows. Deployment maps the
approved dashboard to Home Assistant cards.

## 7. Runtime adapter

No code is generated from the binding. The MontiCore tool checks the binding
specification and resolves each entry into parameters: node, data type, unit
factor and offset, value mapping and method call. Deployment writes these
parameters to `generated/runtime/deployment.json`. The adapter is one fixed,
hand-written program that interprets this file. A new binding changes only the
file, not the adapter.

A service therefore uses the binding through its attribute names, not through
NodeIds. The adapter publishes each bound input under the name and unit of the
service requirements, for example `dt/state/CncMachine.spindleTemperature` in
`degC`, and writes a command on `dt/command/<attribute>` to the bound node. Home
Assistant and the watchdogs in Camunda are such services. The same service runs
on another machine after its requirements are bound to that machine's NodeSet.

The adapter reads `generated/runtime/deployment.json` and restarts its session
when the file changes.

- **Connection check.** For each binding, it resolves the namespace URI and
  checks that the node exists on the server. Variables must have the data type of
  the NodeSet. A failed binding is disabled and reported; it is never written.
- **Values.** It subscribes to readable bound variables, converts each value
  (unit factor and offset, value mapping, type) and publishes it to
  `dt/state/<attribute>`. It records each converted value with its time and
  attribute in the table `value_changes` of `generated/runtime/recorder.sqlite`.
- **Watchdog messages.** A change of a trigger attribute publishes its change
  message `changed-<attribute>` with all current values.
- **Waiting conditions.** Once per second, the adapter asks Camunda which
  elements are active. For a waiting event-based gateway, user task or call
  activity whose condition (event-based gateway or `when` boundary event) holds,
  it publishes the condition's message. Signals, timers and threshold tables run
  inside Camunda.
- **Jobs.** `watchdog-read` returns current values. `opcua-write` writes one
  attribute through the binding: the fixed value of the `value` header or the
  threshold table result named by the `variable` header; the `binding` header must
  name the bound node. A method without argument is called only for the value
  that its value mapping maps to `call`. The execution listener `watchdog-escalate` of an escalation end event
  sets the workflow state to escalated and counts the escalation. A failed job
  raises an incident in Camunda.
- **Operator decisions.** User tasks appear in Camunda Tasklist
  (<http://localhost:8180/tasklist>), where the operator answers yes or no.
- **Commands.** Home Assistant writes to `dt/command/<attribute>`. The adapter
  writes the value through the binding.

- **Unbound attributes.** The adapter neither subscribes to nor publishes a value
  for an attribute that is marked as unbound, not even a default such as `0` or
  `null`. On connect it removes a value of the attribute that an earlier
  deployment retained on `dt/state/<attribute>` and publishes the reason on the
  retained topic `dt/unbound/<attribute>`, so a service can tell that the value is
  unavailable. A command on `dt/command/<attribute>` and a `watchdog-read` job that
  asks for the attribute fail with the reason (the job raises an incident) instead
  of passing a missing value on.

Workflow states are published to `dt/watchdog/<processId>`. Once no run of a
workflow is active and its last state is 30 s old, the state returns to
`monitoring`, so the alarm card disappears after the deviation. The adapter writes
its connection state, disabled bindings, unbound attributes and workflow states to
`generated/runtime/status.json`.

## 8. Safety boundaries

Real OPC UA writes happen only through a deployed binding:

- every entry passed the CoCos, including stated conversions;
- only the engineer binds through a value mapping, which the entry marks as
  confirmed;
- the engineer approved the unchanged binding specification, workflows and
  dashboard;
- watchdog workflows write only writable bound attributes;
- deployment is blocked while the workflows have errors;
- at runtime, a node that is missing or has another data type is not written;
- an attribute marked as unbound has no node, so it is never read or written.

The checks ensure that a reaction is technically valid, not that it has the
intended physical effect.

## 9. Files and state

| Location | Content |
|---|---|
| `generated/workspace/inputs/` | Uploaded requirements, NodeSet, fault rules |
| `generated/workspace/candidates.json` | Filter result per attribute |
| `generated/workspace/binding.bind` | Current binding specification |
| `generated/workspace/binding-review.json` | Accepted entries and attributes for the engineer |
| `generated/workspace/watchdog.wf`, `bpmn/` | Watchdog workflows, generated BPMN models and forms |
| `generated/workspace/dashboard.dash` | Dashboard |
| `generated/workspace/approvals.json` | Hashes of the approved binding, workflows and dashboard |
| `generated/runtime/scenario/<example>/` | Selected simulator scenario |
| `generated/runtime/deployment.json` | Active deployment for the adapter, with the report of unbound attributes and open fault rules |
| `generated/runtime/status.json` | Adapter status |
| `generated/runtime/recorder.sqlite` | Recorded value changes of the bound attributes |
| `docker/homeassistant/dt-dashboard.yaml` | Generated dashboard |

New requirements or a new NodeSet reset the binding and the approvals. A new deployment replaces
the previous one. Watchdog processes of earlier deployments stay in Camunda but
receive no messages.

## 10. Building and checking the languages

The backend image builds `dtlang.jar` with Gradle. To build and call it locally
with Java 21 and Gradle 8:

```bash
cd languages
gradle cliJar
java -jar build/libs/dtlang.jar candidates ../examples/robot_cell/requirements.cd ../examples/robot_cell/robot-cell.nodeset.xml ../examples/robot_cell/watchdog.wf
java -jar build/libs/dtlang.jar check-binding <requirements.cd> <nodeset.xml> <binding.bind> [watchdog.wf]
java -jar build/libs/dtlang.jar check-watchdog <requirements.cd> <nodeset.xml> <binding.bind> <watchdog.wf> <fault-rules.md> [bpmn-directory]
java -jar build/libs/dtlang.jar check-dashboard <requirements.cd> <nodeset.xml> <binding.bind> <dashboard.dash> <watchdog.wf>
```

Each command prints one JSON object with the errors and results. `candidates` and
`check-binding` take the watchdog workflows as an optional last argument, so that
workflow requirements are bound like service attributes.

The tests of the unbound handling run the backend against the language tool and
the adapter against a fake MQTT client and Camunda; the README (Tests) gives the commands.

## 11. Simulator scenarios

Each example in `examples/` has an `example.json` with its title, NodeSet file and
simulator endpoint, and one simulator container. The simulation file
`simulation.json` describes measurement noise, drift (a steady change per noise
period, e.g. the wear of the milling tool), the effects of client writes and
method calls, and named scenarios of timed value changes. In a method effect,
`{0}` in a NodeId stands for the first argument, and `when` makes the effect
depend on the current value of a node. The YuMi uses `when`: `PlaceX` sets
`State` to `moving` and back to `idle` only while the robot is idle, `Stop` sets
`stopped`, and `Start` sets `idle` only after a stop. `occupancy` names the
methods that occupy a place given by their argument: the simulator rejects
`PlaceX` or `PlaceO` for an occupied cell with `BadInvalidState` and sets `State`
to `error` (the `rejected` effects), which starts the ErrorState watchdog, until
`ClearBoard` frees the board. A derived node takes the
value of its first rule whose conditions all hold, the way the machine program
would compute it.

When the engineer starts a scenario, the backend writes
`generated/runtime/scenario/<example>/select.json`. The simulator cancels pending
effects, resets every variable to its NodeSet value and runs the scenario, so
every run starts from the same state.
