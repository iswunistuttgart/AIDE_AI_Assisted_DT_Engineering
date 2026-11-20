package de.aide;

import com.google.gson.Gson;
import de.aide.watchdogworkflow._ast.ASTAssignment;
import de.aide.watchdogworkflow._ast.ASTBoundary;
import de.aide.watchdogworkflow._ast.ASTBranch;
import de.aide.watchdogworkflow._ast.ASTCallElement;
import de.aide.watchdogworkflow._ast.ASTChangeTrigger;
import de.aide.watchdogworkflow._ast.ASTComparison;
import de.aide.watchdogworkflow._ast.ASTConditionBoundary;
import de.aide.watchdogworkflow._ast.ASTConditionTrigger;
import de.aide.watchdogworkflow._ast.ASTConjunction;
import de.aide.watchdogworkflow._ast.ASTCycleTrigger;
import de.aide.watchdogworkflow._ast.ASTDecideElement;
import de.aide.watchdogworkflow._ast.ASTElement;
import de.aide.watchdogworkflow._ast.ASTEndElement;
import de.aide.watchdogworkflow._ast.ASTEscalateElement;
import de.aide.watchdogworkflow._ast.ASTEventGatewayElement;
import de.aide.watchdogworkflow._ast.ASTExpression;
import de.aide.watchdogworkflow._ast.ASTInclusiveJoinElement;
import de.aide.watchdogworkflow._ast.ASTInclusiveSplitElement;
import de.aide.watchdogworkflow._ast.ASTParallelElement;
import de.aide.watchdogworkflow._ast.ASTRow;
import de.aide.watchdogworkflow._ast.ASTSignalBoundary;
import de.aide.watchdogworkflow._ast.ASTSignalElement;
import de.aide.watchdogworkflow._ast.ASTSignalTrigger;
import de.aide.watchdogworkflow._ast.ASTSignalWait;
import de.aide.watchdogworkflow._ast.ASTStartElement;
import de.aide.watchdogworkflow._ast.ASTTaskElement;
import de.aide.watchdogworkflow._ast.ASTTerminateElement;
import de.aide.watchdogworkflow._ast.ASTTimerBoundary;
import de.aide.watchdogworkflow._ast.ASTTimerElement;
import de.aide.watchdogworkflow._ast.ASTUserElement;
import de.aide.watchdogworkflow._ast.ASTWorkflow;
import de.aide.watchdogworkflow._ast.ASTXorElement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serializes a checked watchdog workflow to executable BPMN for Camunda 8. The
 * generator adds element identifiers, message, signal, escalation and timer
 * definitions, threshold tables (DMN), user task forms and the layout. The
 * runtime adapter publishes value changes and met conditions as messages, runs
 * the opcua-write tasks through the binding and handles the escalation listeners.
 *
 * <p>Process variables carry the values that the conditions compare. A start
 * message on value change and every condition message carry current values; a
 * gateway or threshold table that does not directly follow such a start event is
 * preceded by a service task that reads the current values.
 */
public class BpmnGenerator {

  public record Output(String processId, String bpmn, Map<String, String> decisions, Map<String, String> forms,
      Map<String, Object> workflow) {
  }

  public static Output generate(ASTWorkflow workflow, WorkflowCheck check) {
    return new BpmnGenerator(workflow, check).build();
  }

  /** The process id of a workflow; call activities refer to it. */
  public static String processId(String workflow) {
    return "Watchdog_" + workflow;
  }

  private static final class Node {
    String type;
    String name;
    String attributes = "";
    String body = "";
    String definition = "";
    String defaultFlow;
    String host;
    int width;
    int height;
    int x;
    int y;
    final List<String> incoming = new ArrayList<>();
    final List<String> outgoing = new ArrayList<>();
  }

  private record Edge(String id, String source, String target, String name, String condition) {
  }

  private final ASTWorkflow workflow;
  private final WorkflowCheck check;
  private final String processId;
  private final Map<String, ASTElement> elements = new LinkedHashMap<>();
  private final Map<String, List<String>> predecessors = new HashMap<>();
  private final Map<String, Node> nodes = new LinkedHashMap<>();
  private final List<Edge> edges = new ArrayList<>();
  private final StringBuilder definitions = new StringBuilder();
  private final Map<String, String> signals = new LinkedHashMap<>();
  private final Map<String, String> decisions = new LinkedHashMap<>();
  private final Map<String, String> forms = new LinkedHashMap<>();
  private final Set<String> reads = new LinkedHashSet<>();
  private final List<Map<String, Object>> waits = new ArrayList<>();
  private final List<Map<String, String>> userTasks = new ArrayList<>();

  private BpmnGenerator(ASTWorkflow workflow, WorkflowCheck check) {
    this.workflow = workflow;
    this.check = check;
    this.processId = processId(workflow.getName());
    for (ASTElement element : workflow.getElementList()) {
      elements.put(WorkflowCheck.name(element), element);
      for (String next : WorkflowCheck.successors(element)) {
        predecessors.computeIfAbsent(next, k -> new ArrayList<>()).add(WorkflowCheck.name(element));
      }
    }
  }

  private Output build() {
    List<String> triggers = new ArrayList<>();
    List<String> reactions = new ArrayList<>();
    List<Map<String, String>> escalations = new ArrayList<>();
    for (ASTElement element : workflow.getElementList()) {
      String id = WorkflowCheck.name(element);
      switch (element) {
        case ASTStartElement e -> {
          String label = "start";
          String definition = "";
          if (!e.isPresentStartTrigger()) {
            label = "called";
          } else if (e.getStartTrigger() instanceof ASTChangeTrigger t) {
            // One message start event per trigger value; the message is the value change of a bound value.
            t.getAttributeRefList().forEach(a -> triggers.add(check.key(a)));
            reads.addAll(triggers);
            for (int i = 0; i < triggers.size(); i++) {
              String key = triggers.get(i);
              String startId = i == 0 ? id : id + "_" + (i + 1);
              definitions.append("  <bpmn:message id=\"Message_").append(variable(key)).append("\" name=\"")
                  .append(changeMessage(key)).append("\" />\n");
              node(startId, "startEvent", key + " changed",
                  "<bpmn:messageEventDefinition messageRef=\"Message_" + variable(key) + "\" />");
              edge(startId, entry(e.getNext()), null, null);
            }
            continue;
          } else if (e.getStartTrigger() instanceof ASTCycleTrigger t) {
            int seconds = WorkflowCheck.seconds(t.getDuration());
            label = "every " + seconds + " s";
            definition = "<bpmn:timerEventDefinition><bpmn:timeCycle xsi:type=\"bpmn:tFormalExpression\">R/PT"
                + seconds + "S</bpmn:timeCycle></bpmn:timerEventDefinition>";
          } else if (e.getStartTrigger() instanceof ASTSignalTrigger t) {
            label = "signal " + t.getSignal().getValue();
            definition = signal(t.getSignal().getValue());
          }
          node(id, "startEvent", label, definition);
          edge(id, entry(e.getNext()), null, null);
        }
        case ASTTimerElement e -> {
          node(id, "intermediateCatchEvent", WorkflowCheck.seconds(e.getDuration()) + " s",
              timer(WorkflowCheck.seconds(e.getDuration())));
          edge(id, entry(e.getNext()), null, null);
        }
        case ASTSignalElement e -> {
          node(id, "intermediateThrowEvent", "signal " + e.getSignal().getValue(), signal(e.getSignal().getValue()));
          edge(id, entry(e.getNext()), null, null);
        }
        case ASTXorElement e -> {
          readFirst(id, attributes(e.getExpression()));
          node(id, "exclusiveGateway", text(e.getExpression()) + "?", "");
          edge(id, entry(e.getYes()), "yes", feel(e.getExpression()));
          nodes.get(id).defaultFlow = edge(id, entry(e.getNo()), "no", null);
        }
        case ASTInclusiveSplitElement e -> {
          List<String> attributes = new ArrayList<>();
          e.getBranchList().forEach(b -> attributes.addAll(attributes(b.getExpression())));
          readFirst(id, new ArrayList<>(new LinkedHashSet<>(attributes)));
          node(id, "inclusiveGateway", "", "");
          for (ASTBranch branch : e.getBranchList()) {
            edge(id, entry(branch.getNext()), text(branch.getExpression()), feel(branch.getExpression()));
          }
          nodes.get(id).defaultFlow = edge(id, entry(e.getFallback()), "otherwise", null);
        }
        case ASTInclusiveJoinElement e -> {
          node(id, "inclusiveGateway", "", "");
          edge(id, entry(e.getNext()), null, null);
        }
        case ASTEventGatewayElement e -> {
          node(id, "eventBasedGateway", "", "");
          String label;
          String definition;
          if (e.getWaitTrigger() instanceof ASTConditionTrigger t) {
            label = text(t.getExpression());
            definition = conditionMessage(id, id, t.getExpression());
          } else {
            String signal = ((ASTSignalWait) e.getWaitTrigger()).getSignal().getValue();
            label = "signal " + signal;
            definition = signal(signal);
          }
          node(id + "_met", "intermediateCatchEvent", label, definition);
          int deadline = WorkflowCheck.seconds(e.getDuration());
          node(id + "_deadline", "intermediateCatchEvent", "after " + deadline + " s", timer(deadline));
          edge(id, id + "_met", null, null);
          edge(id + "_met", entry(e.getMet()), null, null);
          edge(id, id + "_deadline", null, null);
          edge(id + "_deadline", entry(e.getDeadline()), null, null);
        }
        case ASTParallelElement e -> {
          node(id, "parallelGateway", "", "");
          e.getNextList().forEach(next -> edge(id, entry(next), null, null));
        }
        case ASTTaskElement e -> {
          ASTAssignment assignment = e.getAssignment();
          String attribute = check.key(assignment.getAttributeRef());
          Object value = WorkflowCheck.literal(assignment.getSignedLiteral());
          String label = "set " + attribute + " = " + value;
          reactions.add(label);
          serviceTask(id, label, "opcua-write", write(attribute, "value", new Gson().toJson(value)));
          edge(id, entry(e.getNext()), null, null);
        }
        case ASTDecideElement e -> {
          String input = check.key(e.getInput());
          String target = check.key(e.getTarget());
          String decisionId = "Decision_" + processId + "_" + id;
          String result = "decision_" + id;
          readFirst(id, List.of(input));
          decisions.put(processId + "_" + id, dmn(decisionId, e, input, target));
          node(id, "businessRuleTask", "threshold table for " + target, "");
          nodes.get(id).body = "<bpmn:extensionElements><zeebe:calledDecision decisionId=\"" + decisionId
              + "\" resultVariable=\"" + result + "\" /></bpmn:extensionElements>";
          nodes.get(id).width = 100;
          nodes.get(id).height = 80;
          String label = "set " + target + " from table";
          reactions.add(label);
          // The value is the result of the threshold table, which the decision stores in a process variable.
          serviceTask(id + "_set", label, "opcua-write", write(target, "variable", result));
          edge(id, id + "_set", null, null);
          edge(id + "_set", entry(e.getNext()), null, null);
        }
        case ASTUserElement e -> {
          String question = e.getQuestion().getValue();
          String formId = processId + "_" + id;
          String answer = "answer_" + id;
          forms.put(formId, form(formId, question, answer));
          reactions.add("ask \"" + question + "\"");
          userTasks.add(Map.of("element", id, "question", question));
          node(id, "userTask", question, "");
          nodes.get(id).body = "<bpmn:extensionElements><zeebe:userTask /><zeebe:formDefinition formId=\"" + formId
              + "\" /></bpmn:extensionElements>";
          nodes.get(id).width = 100;
          nodes.get(id).height = 80;
          node(id + "_answer", "exclusiveGateway", "answer?", "");
          edge(id, id + "_answer", null, null);
          edge(id + "_answer", entry(e.getYes()), "yes", "=" + answer + " = \"yes\"");
          nodes.get(id + "_answer").defaultFlow = edge(id + "_answer", entry(e.getNo()), "no", null);
          boundaries(id, e.getBoundaryList());
        }
        case ASTCallElement e -> {
          node(id, "callActivity", "call " + e.getWorkflow(), "");
          nodes.get(id).body = "<bpmn:extensionElements><zeebe:calledElement processId=\"" + processId(e.getWorkflow())
              + "\" propagateAllChildVariables=\"false\" /></bpmn:extensionElements>";
          nodes.get(id).width = 100;
          nodes.get(id).height = 80;
          edge(id, entry(e.getNext()), null, null);
          boundaries(id, e.getBoundaryList());
        }
        case ASTEscalateElement e -> {
          String message = e.getMessage().getValue();
          escalations.add(Map.of("element", id, "message", message));
          definitions.append("  <bpmn:escalation id=\"Escalation_").append(id).append("\" name=\"").append(xml(message))
              .append("\" escalationCode=\"").append(id).append("\" />\n");
          node(id, "endEvent", message, "<bpmn:escalationEventDefinition escalationRef=\"Escalation_" + id + "\" />");
          // An execution listener lets the adapter inform the responsible person when the escalation is reached.
          nodes.get(id).body = "<bpmn:extensionElements><zeebe:executionListeners><zeebe:executionListener "
              + "eventType=\"start\" retries=\"3\" type=\"watchdog-escalate\" /></zeebe:executionListeners>"
              + "</bpmn:extensionElements>";
        }
        case ASTTerminateElement e -> node(id, "endEvent", id, "<bpmn:terminateEventDefinition />");
        case ASTEndElement e -> node(id, "endEvent", id, "");
        default -> throw new IllegalArgumentException("Unknown element");
      }
    }
    layout();

    String bpmn = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
        xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" \
        xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" \
        xmlns:di="http://www.omg.org/spec/DD/20100524/DI" \
        xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" \
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
        id="Definitions_%1$s" targetNamespace="urn:aide:watchdog">
        %2$s  <bpmn:process id="%1$s" name="%3$s" isExecutable="true">
        %4$s  </bpmn:process>
          <bpmndi:BPMNDiagram id="Diagram_%1$s">
            <bpmndi:BPMNPlane id="Plane_%1$s" bpmnElement="%1$s">
        %5$s    </bpmndi:BPMNPlane>
          </bpmndi:BPMNDiagram>
        </bpmn:definitions>
        """.formatted(processId, definitions, xml("Watchdog " + workflow.getName()), renderFlow(), renderDiagram());

    Map<String, Object> json = new LinkedHashMap<>();
    json.put("name", workflow.getName());
    json.put("processId", processId);
    json.put("covers", workflow.getNatLiteralList().stream().map(n -> n.getValue()).toList());
    json.put("file", fileName(workflow.getName()));
    json.put("triggers", triggers);
    json.put("reads", new ArrayList<>(reads));
    json.put("waits", waits);
    json.put("userTasks", userTasks);
    json.put("reactions", reactions);
    json.put("escalations", escalations);
    return new Output(processId, bpmn, decisions, forms, json);
  }

  /** The message that the adapter publishes when a bound value changes; it starts every workflow triggered by it. */
  public static String changeMessage(String key) {
    return "changed-" + key;
  }

  /** The file name of a generated model, for example axis-overheat for AxisOverheat. */
  public static String fileName(String workflow) {
    return workflow.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
  }

  /** The FEEL variable that carries an attribute value, for example CncMachine_spindleTemperature. */
  public static String variable(String key) {
    return key.replace('.', '_');
  }

  /** The BPMN node that incoming flows of a workflow element target. */
  private String entry(String element) {
    return readsFirst(elements.get(element)) && needsRead(element) ? element + "_read" : element;
  }

  private static boolean readsFirst(ASTElement element) {
    return element instanceof ASTXorElement || element instanceof ASTInclusiveSplitElement
        || element instanceof ASTDecideElement;
  }

  /** Values from a start message on value change are current; otherwise the element reads them again. */
  private boolean needsRead(String element) {
    List<String> before = predecessors.getOrDefault(element, List.of());
    return before.isEmpty() || !before.stream().allMatch(p -> elements.get(p) instanceof ASTStartElement s
        && s.isPresentStartTrigger() && s.getStartTrigger() instanceof ASTChangeTrigger);
  }

  private void readFirst(String id, List<String> attributes) {
    reads.addAll(attributes);
    if (needsRead(id)) {
      serviceTask(id + "_read", "read current values", "watchdog-read",
          Map.of("attributes", String.join(",", attributes)));
      edge(id + "_read", id, null, null);
    }
  }

  /** Interrupting boundary events on a user task or call activity. */
  private void boundaries(String host, List<ASTBoundary> boundaries) {
    for (int i = 0; i < boundaries.size(); i++) {
      ASTBoundary boundary = boundaries.get(i);
      String id = host + "_boundary" + (i + 1);
      switch (boundary) {
        case ASTTimerBoundary b -> {
          int seconds = WorkflowCheck.seconds(b.getDuration());
          node(id, "boundaryEvent", "after " + seconds + " s", timer(seconds));
        }
        case ASTConditionBoundary b -> node(id, "boundaryEvent", text(b.getExpression()),
            conditionMessage(id, host, b.getExpression()));
        case ASTSignalBoundary b -> node(id, "boundaryEvent", "signal " + b.getSignal().getValue(),
            signal(b.getSignal().getValue()));
        default -> throw new IllegalArgumentException("Unknown boundary event");
      }
      nodes.get(id).attributes = " attachedToRef=\"" + host + "\" cancelActivity=\"true\"";
      nodes.get(id).host = host;
      edge(id, entry(WorkflowCheck.next(boundary)), null, null);
    }
  }

  /**
   * A message that the adapter publishes once the condition holds while the given element is active.
   * The correlation key is the process id, so the subscription needs no process variable.
   */
  private String conditionMessage(String id, String activeElement, ASTExpression expression) {
    reads.addAll(attributes(expression));
    String message = processId + "-" + id;
    definitions.append("  <bpmn:message id=\"Message_").append(id).append("\" name=\"").append(message)
        .append("\"><bpmn:extensionElements><zeebe:subscription correlationKey=\"=&quot;").append(processId)
        .append("&quot;\" /></bpmn:extensionElements></bpmn:message>\n");
    Map<String, Object> wait = new LinkedHashMap<>();
    wait.put("element", activeElement);
    wait.put("message", message);
    wait.put("condition", dnf(expression));
    waits.add(wait);
    return "<bpmn:messageEventDefinition messageRef=\"Message_" + id + "\" />";
  }

  private String signal(String name) {
    String id = signals.computeIfAbsent(name, n -> {
      String signalId = "Signal_" + (signals.size() + 1);
      definitions.append("  <bpmn:signal id=\"").append(signalId).append("\" name=\"").append(xml(n)).append("\" />\n");
      return signalId;
    });
    return "<bpmn:signalEventDefinition signalRef=\"" + id + "\" />";
  }

  private List<String> attributes(ASTExpression expression) {
    Set<String> keys = new LinkedHashSet<>();
    for (ASTConjunction conjunction : expression.getConjunctionList()) {
      conjunction.getComparisonList().forEach(c -> keys.add(check.key(c.getAttributeRef())));
    }
    return new ArrayList<>(keys);
  }

  private List<List<Map<String, Object>>> dnf(ASTExpression expression) {
    List<List<Map<String, Object>>> result = new ArrayList<>();
    for (ASTConjunction conjunction : expression.getConjunctionList()) {
      List<Map<String, Object>> terms = new ArrayList<>();
      for (ASTComparison comparison : conjunction.getComparisonList()) {
        Map<String, Object> term = new LinkedHashMap<>();
        term.put("attribute", check.key(comparison.getAttributeRef()));
        term.put("op", operator(comparison.getCompOp().name()));
        term.put("value", WorkflowCheck.literal(comparison.getSignedLiteral()));
        terms.add(term);
      }
      result.add(terms);
    }
    return result;
  }

  private static String operator(String name) {
    return switch (name) {
      case "GT" -> ">";
      case "GE" -> ">=";
      case "LT" -> "<";
      case "LE" -> "<=";
      case "EQ" -> "==";
      default -> "!=";
    };
  }

  /** FEEL condition; a missing value makes the comparison false instead of raising an incident. */
  private String feel(ASTExpression expression) {
    List<String> disjuncts = new ArrayList<>();
    for (ASTConjunction conjunction : expression.getConjunctionList()) {
      List<String> terms = new ArrayList<>();
      for (ASTComparison comparison : conjunction.getComparisonList()) {
        String name = variable(check.key(comparison.getAttributeRef()));
        String op = operator(comparison.getCompOp().name());
        terms.add("(" + name + " != null and " + name + " " + (op.equals("==") ? "=" : op) + " "
            + feelLiteral(WorkflowCheck.literal(comparison.getSignedLiteral())) + ")");
      }
      disjuncts.add("(" + String.join(" and ", terms) + ")");
    }
    return "=" + String.join(" or ", disjuncts);
  }

  private static String feelLiteral(Object value) {
    if (value instanceof String s) {
      return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    return String.valueOf(value);
  }

  private String text(ASTExpression expression) {
    List<String> disjuncts = new ArrayList<>();
    for (ASTConjunction conjunction : expression.getConjunctionList()) {
      disjuncts.add(String.join(" and ", conjunction.getComparisonList().stream()
          .map(c -> check.key(c.getAttributeRef()) + " " + operator(c.getCompOp().name()) + " "
              + feelLiteral(WorkflowCheck.literal(c.getSignedLiteral())))
          .toList()));
    }
    return String.join(" or ", disjuncts);
  }

  private static String timer(int seconds) {
    return "<bpmn:timerEventDefinition><bpmn:timeDuration xsi:type=\"bpmn:tFormalExpression\">PT" + seconds
        + "S</bpmn:timeDuration></bpmn:timerEventDefinition>";
  }

  /** A DMN decision table with hit policy FIRST: the first matching row wins, the last row is the else value. */
  private String dmn(String decisionId, ASTDecideElement decide, String input, String target) {
    StringBuilder rules = new StringBuilder();
    int index = 1;
    for (ASTRow row : decide.getRowList()) {
      String op = operator(row.getCompOp().name());
      String limit = feelLiteral(WorkflowCheck.literal(row.getLimit()));
      String test = switch (op) {
        case "==" -> limit;
        case "!=" -> "not(" + limit + ")";
        default -> op + " " + limit;
      };
      rules.append(rule(index++, test, feelLiteral(WorkflowCheck.literal(row.getValue()))));
    }
    rules.append(rule(index, "-", feelLiteral(WorkflowCheck.literal(decide.getOtherwise()))));
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="Definitions_%1$s" \
        name="%2$s" namespace="urn:aide:watchdog">
          <decision id="%1$s" name="%2$s">
            <decisionTable id="Table_%1$s" hitPolicy="FIRST">
              <input id="Input_%1$s" label="%3$s">
                <inputExpression id="InputExpression_%1$s" typeRef="%4$s"><text>%5$s</text></inputExpression>
              </input>
              <output id="Output_%1$s" label="%6$s" name="value" typeRef="%7$s" />
        %8$s      </decisionTable>
          </decision>
        </definitions>
        """.formatted(decisionId, xml("threshold table for " + target), xml(input), dmnType(check.type(input)),
        variable(input), xml(target), dmnType(check.type(target)), rules);
  }

  private static String rule(int index, String test, String value) {
    return "        <rule id=\"Rule_" + index + "\"><inputEntry id=\"In_" + index + "\"><text>" + xml(test)
        + "</text></inputEntry><outputEntry id=\"Out_" + index + "\"><text>" + xml(value)
        + "</text></outputEntry></rule>\n";
  }

  private static String dmnType(String type) {
    return switch (type) {
      case "boolean" -> "boolean";
      case "String" -> "string";
      default -> "number";
    };
  }

  /** A Camunda form with the question and a required yes/no answer. */
  private static String form(String formId, String question, String answer) {
    Map<String, Object> text = new LinkedHashMap<>();
    text.put("id", "Question");
    text.put("type", "text");
    text.put("text", "### " + question);
    Map<String, Object> radio = new LinkedHashMap<>();
    radio.put("id", "Answer");
    radio.put("type", "radio");
    radio.put("key", answer);
    radio.put("label", "Answer");
    radio.put("values", List.of(Map.of("label", "Yes", "value", "yes"), Map.of("label", "No", "value", "no")));
    radio.put("validate", Map.of("required", true));
    Map<String, Object> form = new LinkedHashMap<>();
    form.put("id", formId);
    form.put("type", "default");
    form.put("schemaVersion", 16);
    form.put("executionPlatform", "Camunda Cloud");
    form.put("components", List.of(text, radio));
    return new Gson().toJson(form);
  }

  private void node(String id, String type, String name, String definition) {
    Node node = new Node();
    node.type = type;
    node.name = name;
    node.definition = definition;
    boolean gateway = type.endsWith("Gateway");
    node.width = gateway ? 50 : 36;
    node.height = node.width;
    nodes.put(id, node);
  }

  /** Headers of an opcua-write task: the attribute, its value (or the variable that holds it), and the bound node. */
  private Map<String, String> write(String attribute, String valueKind, String value) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("attribute", attribute);
    headers.put(valueKind, value);
    headers.put("binding", (String) check.binding().resolved.get(attribute).get("nodeId"));
    return headers;
  }

  private void serviceTask(String id, String name, String jobType, Map<String, String> headers) {
    node(id, "serviceTask", name, "");
    nodes.get(id).body = extensions(jobType, headers);
    nodes.get(id).width = 100;
    nodes.get(id).height = 80;
  }

  private static String extensions(String jobType, Map<String, String> headers) {
    StringBuilder header = new StringBuilder();
    headers.forEach((key, value) -> header.append("<zeebe:header key=\"").append(key).append("\" value=\"")
        .append(xml(value)).append("\" />"));
    return "<bpmn:extensionElements><zeebe:taskDefinition type=\"" + jobType + "\" /><zeebe:taskHeaders>" + header
        + "</zeebe:taskHeaders></bpmn:extensionElements>";
  }

  /** Records a flow; targets may be declared later in the workflow. */
  private String edge(String source, String target, String name, String condition) {
    String id = "Flow_" + (edges.size() + 1);
    edges.add(new Edge(id, source, target, name, condition));
    return id;
  }

  /**
   * Places each node in the column of its distance from the start and stacks nodes of one column.
   * Boundary events sit on the lower edge of their host; their targets follow the host.
   */
  private void layout() {
    Map<String, List<String>> next = new HashMap<>();
    for (Edge edge : edges) {
      next.computeIfAbsent(edge.source, k -> new ArrayList<>()).add(edge.target);
      nodes.get(edge.source).outgoing.add(edge.id);
      nodes.get(edge.target).incoming.add(edge.id);
    }
    nodes.forEach((id, node) -> {
      if (node.host != null) {
        next.computeIfAbsent(node.host, k -> new ArrayList<>()).add(id);
      }
    });
    Map<String, Integer> column = new LinkedHashMap<>();
    Deque<String> queue = new ArrayDeque<>();
    nodes.forEach((id, node) -> {
      if (node.type.equals("startEvent")) {
        column.put(id, 0);
        queue.add(id);
      }
    });
    while (!queue.isEmpty()) {
      String current = queue.poll();
      for (String target : next.getOrDefault(current, List.of())) {
        if (!column.containsKey(target)) {
          column.put(target, column.get(current) + 1);
          queue.add(target);
        }
      }
    }
    Map<Integer, Integer> rows = new HashMap<>();
    column.forEach((id, c) -> {
      Node node = nodes.get(id);
      if (node.host != null) {
        return;
      }
      int row = rows.merge(c, 1, Integer::sum) - 1;
      node.x = 150 + c * 150 - node.width / 2;
      node.y = 150 + row * 140 - node.height / 2;
    });
    Map<String, Integer> attached = new HashMap<>();
    nodes.forEach((id, node) -> {
      if (node.host != null) {
        Node host = nodes.get(node.host);
        int index = attached.merge(node.host, 1, Integer::sum) - 1;
        node.x = host.x + host.width - node.width - index * 40;
        node.y = host.y + host.height - node.height / 2;
      }
    });
  }

  /** The Camunda BPMN model reads a node's flows from its incoming and outgoing elements. */
  private String renderFlow() {
    StringBuilder out = new StringBuilder();
    nodes.forEach((id, n) -> {
      out.append("    <bpmn:").append(n.type).append(" id=\"").append(id).append("\"");
      if (!n.name.isEmpty()) {
        out.append(" name=\"").append(xml(label(n.name))).append("\"");
      }
      if (n.defaultFlow != null) {
        out.append(" default=\"").append(n.defaultFlow).append("\"");
      }
      out.append(n.attributes).append(">").append(n.body);
      n.incoming.forEach(f -> out.append("<bpmn:incoming>").append(f).append("</bpmn:incoming>"));
      n.outgoing.forEach(f -> out.append("<bpmn:outgoing>").append(f).append("</bpmn:outgoing>"));
      out.append(n.definition).append("</bpmn:").append(n.type).append(">\n");
    });
    for (Edge edge : edges) {
      out.append("    <bpmn:sequenceFlow id=\"").append(edge.id).append("\" sourceRef=\"").append(edge.source)
          .append("\" targetRef=\"").append(edge.target).append("\"")
          .append(edge.name == null ? "" : " name=\"" + xml(label(edge.name)) + "\"");
      if (edge.condition == null) {
        out.append(" />\n");
      } else {
        out.append("><bpmn:conditionExpression xsi:type=\"bpmn:tFormalExpression\">").append(xml(edge.condition))
            .append("</bpmn:conditionExpression></bpmn:sequenceFlow>\n");
      }
    }
    return out.toString();
  }

  private String renderDiagram() {
    StringBuilder out = new StringBuilder();
    nodes.forEach((id, n) -> out.append("      <bpmndi:BPMNShape id=\"").append(id).append("_di\" bpmnElement=\"")
        .append(id).append("\"><dc:Bounds x=\"").append(n.x).append("\" y=\"").append(n.y).append("\" width=\"")
        .append(n.width).append("\" height=\"").append(n.height).append("\" /></bpmndi:BPMNShape>\n"));
    for (Edge edge : edges) {
      Node s = nodes.get(edge.source);
      Node t = nodes.get(edge.target);
      int scx = s.x + s.width / 2;
      int scy = s.y + s.height / 2;
      int tcx = t.x + t.width / 2;
      int tcy = t.y + t.height / 2;
      List<int[]> points = new ArrayList<>();
      if (s.host != null) {
        // A flow from a boundary event leaves downwards.
        points.add(new int[] {scx, s.y + s.height});
        points.add(new int[] {scx, tcy});
        points.add(new int[] {tcx > scx ? t.x : t.x + t.width, tcy});
      } else if (tcx <= scx) {
        // A flow back to an earlier column runs below both nodes.
        int below = Math.max(s.y + s.height, t.y + t.height) + 30;
        points.add(new int[] {scx, s.y + s.height});
        points.add(new int[] {scx, below});
        points.add(new int[] {tcx, below});
        points.add(new int[] {tcx, t.y + t.height});
      } else if (scy == tcy) {
        points.add(new int[] {s.x + s.width, scy});
        points.add(new int[] {t.x, tcy});
      } else {
        points.add(new int[] {scx, tcy > scy ? s.y + s.height : s.y});
        points.add(new int[] {scx, tcy});
        points.add(new int[] {t.x, tcy});
      }
      out.append("      <bpmndi:BPMNEdge id=\"").append(edge.id).append("_di\" bpmnElement=\"").append(edge.id)
          .append("\">");
      points.forEach(p -> out.append("<di:waypoint x=\"").append(p[0]).append("\" y=\"").append(p[1]).append("\" />"));
      out.append("</bpmndi:BPMNEdge>\n");
    }
    return out.toString();
  }

  /** Camunda rejects names longer than 256 characters, so a long condition is shortened in its label. */
  private static String label(String name) {
    return name.length() <= 256 ? name : name.substring(0, 255) + "…";
  }

  private static String xml(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }
}
