package de.aide;

import de.aide.dtbasics._ast.ASTAttributeRef;
import de.aide.dtbasics._visitor.DTBasicsVisitor2;
import de.aide.watchdogworkflow.WatchdogWorkflowMill;
import de.aide.watchdogworkflow._ast.ASTAssignment;
import de.aide.watchdogworkflow._ast.ASTBoundary;
import de.aide.watchdogworkflow._ast.ASTBranch;
import de.aide.watchdogworkflow._ast.ASTCallElement;
import de.aide.watchdogworkflow._ast.ASTChangeTrigger;
import de.aide.watchdogworkflow._ast.ASTComparison;
import de.aide.watchdogworkflow._ast.ASTConditionBoundary;
import de.aide.watchdogworkflow._ast.ASTDecideElement;
import de.aide.watchdogworkflow._ast.ASTDuration;
import de.aide.watchdogworkflow._ast.ASTElement;
import de.aide.watchdogworkflow._ast.ASTEndElement;
import de.aide.watchdogworkflow._ast.ASTEscalateElement;
import de.aide.watchdogworkflow._ast.ASTEventGatewayElement;
import de.aide.watchdogworkflow._ast.ASTInclusiveJoinElement;
import de.aide.watchdogworkflow._ast.ASTInclusiveSplitElement;
import de.aide.watchdogworkflow._ast.ASTOpenRule;
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
import de.aide.watchdogworkflow._ast.ASTWorkflowRequirement;
import de.aide.watchdogworkflow._ast.ASTWorkflowSpec;
import de.aide.watchdogworkflow._ast.ASTXorElement;
import de.aide.watchdogworkflow._cocos.WatchdogWorkflowCoCoChecker;
import de.monticore.literals.mccommonliterals._ast.ASTBooleanLiteral;
import de.monticore.literals.mccommonliterals._ast.ASTSignedBasicDoubleLiteral;
import de.monticore.literals.mccommonliterals._ast.ASTSignedLiteral;
import de.monticore.literals.mccommonliterals._ast.ASTSignedNatLiteral;
import de.monticore.literals.mccommonliterals._ast.ASTStringLiteral;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses watchdog workflows and decides whether the tool accepts them:
 * every value has a known source (a bound readable attribute of the service or
 * workflow requirements, or a signal that a workflow throws), every workflow
 * requirement is bound, every write targets a writable bound attribute with a
 * value of the right type and range, the graph is sound, and every numbered fault rule is
 * covered by a workflow with at least one condition and one reaction. An attribute that the
 * engineer marked as unbound has no value, so no workflow may use it; a fault rule that cannot
 * be implemented without it is stated as an open rule and stays an open case.
 */
public class WorkflowCheck {

  private static final Pattern FAULT_RULE = Pattern.compile("(?m)^\\s*(\\d+)[.)]\\s");

  public final Diagnostics diagnostics = new Diagnostics();
  /** Fault rules that stay open cases, each with the unbound attributes it needs and their reasons. */
  public final List<Map<String, Object>> openRules = new ArrayList<>();
  public ASTWorkflowSpec ast;
  /** The qualified attribute key of every attribute reference; a workflow may omit the class name. */
  final Map<ASTAttributeRef, String> keys = new IdentityHashMap<>();
  /** The workflow that contains each attribute reference. */
  private final Map<ASTAttributeRef, String> scopes = new IdentityHashMap<>();

  private final Requirements requirements;
  private final BindingCheck binding;

  private WorkflowCheck(Requirements requirements, BindingCheck binding) {
    this.requirements = requirements;
    this.binding = binding;
  }

  public static WorkflowCheck run(Requirements requirements, BindingCheck binding, String text, String faultRules)
      throws Exception {
    WorkflowCheck check = new WorkflowCheck(requirements, binding);
    WatchdogWorkflowMill.init();
    var parser = WatchdogWorkflowMill.parser();
    Optional<ASTWorkflowSpec> ast = parser.parse_String(text);
    if (parser.hasErrors() || ast.isEmpty()) {
      check.diagnostics.takeLogFindings();
      if (check.diagnostics.isEmpty()) {
        check.diagnostics.error("The watchdog workflows cannot be parsed.", null);
      }
      return check;
    }
    check.ast = ast.get();
    check.scopeReferences();
    check.check(faultRules);
    return check;
  }

  private void check(String faultRules) {
    Diagnostics d = diagnostics;
    WatchdogWorkflowCoCoChecker checker = new WatchdogWorkflowCoCoChecker();
    checker.addCoCo((ASTWorkflowSpec spec) -> {
      checkCalls(spec);
      checkSignals(spec);
      checkTraceability(spec, faultRules);
    });
    checker.addCoCo((ASTWorkflow workflow) -> {
      checkRequirements(workflow);
      if (binding.name != null && !workflow.getBinding().equals(binding.name)) {
        d.error("Workflow " + workflow.getName() + " uses " + workflow.getBinding() + ", but the binding is "
            + binding.name + ".", workflow.get_SourcePositionStart());
      }
      checkGraph(workflow);
    });
    checker.addCoCo((ASTChangeTrigger trigger) -> trigger.getAttributeRefList().forEach(this::readable));
    checker.addCoCo((ASTComparison comparison) -> {
      String key = readable(comparison.getAttributeRef());
      if (key != null) {
        checkValue(key, comparison.getSignedLiteral(), equality(comparison.getCompOp().name()), comparison);
      }
    });
    checker.addCoCo((ASTAssignment assignment) -> {
      String key = writable(assignment.getAttributeRef());
      if (key != null) {
        checkValue(key, assignment.getSignedLiteral(), true, assignment);
        checkCall(key, assignment.getSignedLiteral(), assignment);
      }
    });
    checker.addCoCo((ASTDecideElement decide) -> {
      String input = readable(decide.getInput());
      if (input != null) {
        for (ASTRow row : decide.getRowList()) {
          checkValue(input, row.getLimit(), equality(row.getCompOp().name()), row);
        }
      }
      String target = writable(decide.getTarget());
      if (target != null) {
        decide.getRowList().forEach(row -> {
          checkValue(target, row.getValue(), true, row);
          checkCall(target, row.getValue(), row);
        });
        checkValue(target, decide.getOtherwise(), true, decide);
        checkCall(target, decide.getOtherwise(), decide);
      }
    });
    checker.addCoCo((ASTDuration duration) -> {
      if (!duration.getUnit().equals("s") && !duration.getUnit().equals("min")) {
        d.error("Time unit " + duration.getUnit() + " is not supported; use s or min.",
            duration.get_SourcePositionStart());
      }
      if (duration.getNatLiteral().getValue() <= 0) {
        d.error("A duration must be greater than zero.", duration.get_SourcePositionStart());
      }
    });
    checker.checkAll(ast);
  }

  /** Records the workflow of every attribute reference, since a workflow sees only its own requirements. */
  private void scopeReferences() {
    for (ASTWorkflow workflow : ast.getWorkflowList()) {
      var traverser = WatchdogWorkflowMill.traverser();
      traverser.add4DTBasics(new DTBasicsVisitor2() {
        @Override
        public void visit(ASTAttributeRef ref) {
          scopes.put(ref, workflow.getName());
        }
      });
      workflow.accept(traverser);
    }
  }

  /** Workflow requirements are valid and bound; an unbound one is a missing source. */
  private void checkRequirements(ASTWorkflow workflow) {
    Set<String> names = new HashSet<>();
    for (ASTWorkflowRequirement requirement : workflow.getWorkflowRequirementList()) {
      String key = workflow.getName() + "." + requirement.getName();
      String error = requirements.workflowRequirementError(requirement, key);
      if (!names.add(requirement.getName())) {
        error = "Workflow requirement " + key + " is declared twice.";
      }
      if (error != null) {
        diagnostics.error(error, requirement.get_SourcePositionStart());
      } else if (binding.unbound.containsKey(key)) {
        diagnostics.error("Workflow " + workflow.getName() + ": " + unboundError(key), requirement.get_SourcePositionStart());
      } else if (!binding.resolved.containsKey(key)) {
        diagnostics.error("Workflow " + workflow.getName() + ": missing source: no node is bound to the workflow "
            + "requirement " + requirement.getName() + ".", requirement.get_SourcePositionStart());
      }
    }
  }

  private static boolean equality(String op) {
    return op.equals("EQ") || op.equals("NE");
  }

  /** A workflow without a start trigger is called by others: calls name such workflows and never form a cycle. */
  private void checkCalls(ASTWorkflowSpec spec) {
    Map<String, ASTWorkflow> workflows = new LinkedHashMap<>();
    spec.getWorkflowList().forEach(w -> workflows.putIfAbsent(w.getName(), w));
    Set<String> called = new HashSet<>();
    for (ASTWorkflow workflow : spec.getWorkflowList()) {
      for (String target : calls(workflow)) {
        called.add(target);
        ASTWorkflow callee = workflows.get(target);
        if (callee == null) {
          diagnostics.error("Workflow " + workflow.getName() + " calls " + target + ", which does not exist.",
              workflow.get_SourcePositionStart());
        } else if (!callable(callee)) {
          diagnostics.error("Workflow " + workflow.getName() + " calls " + target
              + ", but only a workflow whose start event has no trigger can be called.",
              workflow.get_SourcePositionStart());
        } else if (calledWorkflows(callee, workflows).contains(workflow.getName())) {
          diagnostics.error("Workflow " + workflow.getName() + " calls itself through " + target + ".",
              workflow.get_SourcePositionStart());
        }
      }
    }
    for (ASTWorkflow workflow : spec.getWorkflowList()) {
      if (callable(workflow) && !called.contains(workflow.getName())) {
        diagnostics.error("Workflow " + workflow.getName() + " has no start trigger, so another workflow must call it.",
            workflow.get_SourcePositionStart());
      }
      if (!callable(workflow) && workflow.getNatLiteralList().isEmpty()) {
        diagnostics.error("Workflow " + workflow.getName() + " must state which fault rules it covers.",
            workflow.get_SourcePositionStart());
      }
    }
  }

  static boolean callable(ASTWorkflow workflow) {
    return workflow.getElementList().stream()
        .anyMatch(e -> e instanceof ASTStartElement s && !s.isPresentStartTrigger());
  }

  private static List<String> calls(ASTWorkflow workflow) {
    return workflow.getElementList().stream()
        .filter(e -> e instanceof ASTCallElement).map(e -> ((ASTCallElement) e).getWorkflow()).toList();
  }

  /** The workflows that a workflow calls, directly or indirectly. */
  private static Set<String> calledWorkflows(ASTWorkflow workflow, Map<String, ASTWorkflow> workflows) {
    Set<String> seen = new HashSet<>();
    Deque<ASTWorkflow> queue = new ArrayDeque<>(List.of(workflow));
    while (!queue.isEmpty()) {
      for (String target : calls(queue.pop())) {
        if (seen.add(target) && workflows.containsKey(target)) {
          queue.push(workflows.get(target));
        }
      }
    }
    return seen;
  }

  /** A caught signal has a known source only if a workflow of the specification throws it. */
  private void checkSignals(ASTWorkflowSpec spec) {
    Set<String> thrown = new HashSet<>();
    List<ASTStringLiteral> caught = new ArrayList<>();
    for (ASTWorkflow workflow : spec.getWorkflowList()) {
      for (ASTElement element : workflow.getElementList()) {
        switch (element) {
          case ASTSignalElement e -> thrown.add(e.getSignal().getValue());
          case ASTStartElement e when e.isPresentStartTrigger() && e.getStartTrigger() instanceof ASTSignalTrigger t ->
              caught.add(t.getSignal());
          case ASTEventGatewayElement e when e.getWaitTrigger() instanceof ASTSignalWait w -> caught.add(w.getSignal());
          default -> {
          }
        }
        for (ASTBoundary boundary : boundaries(element)) {
          if (boundary instanceof ASTSignalBoundary b) {
            caught.add(b.getSignal());
          }
        }
      }
    }
    for (ASTStringLiteral signal : caught) {
      if (!thrown.contains(signal.getValue())) {
        diagnostics.error("Signal \"" + signal.getValue() + "\" has no known source: no workflow throws it.",
            signal.get_SourcePositionStart());
      }
    }
  }

  /** Every numbered fault rule maps to a workflow with at least one condition and one reaction. */
  private void checkTraceability(ASTWorkflowSpec spec, String faultRules) {
    Set<Integer> rules = faultRuleNumbers(faultRules);
    Map<String, ASTWorkflow> workflows = new LinkedHashMap<>();
    Set<Integer> covered = new TreeSet<>();
    for (ASTWorkflow workflow : spec.getWorkflowList()) {
      if (workflows.putIfAbsent(workflow.getName(), workflow) != null) {
        diagnostics.error("Workflow " + workflow.getName() + " is declared twice.", workflow.get_SourcePositionStart());
      }
    }
    for (ASTWorkflow workflow : spec.getWorkflowList()) {
      if (workflow.getNatLiteralList().isEmpty()) {
        continue;
      }
      // The elements of called workflows count for the caller.
      List<ASTElement> elements = new ArrayList<>(workflow.getElementList());
      calledWorkflows(workflow, workflows).stream().filter(workflows::containsKey)
          .forEach(name -> elements.addAll(workflows.get(name).getElementList()));
      boolean condition = elements.stream().anyMatch(e -> e instanceof ASTXorElement
          || e instanceof ASTInclusiveSplitElement || e instanceof ASTEventGatewayElement
          || e instanceof ASTDecideElement);
      boolean reaction = elements.stream().anyMatch(e -> e instanceof ASTTaskElement || e instanceof ASTDecideElement
          || e instanceof ASTUserElement || e instanceof ASTEscalateElement);
      for (var number : workflow.getNatLiteralList()) {
        if (!rules.contains(number.getValue())) {
          diagnostics.error("Workflow " + workflow.getName() + " covers fault rule " + number.getValue()
              + ", which does not exist.", workflow.get_SourcePositionStart());
        } else if (condition && reaction) {
          covered.add(number.getValue());
        }
      }
      if (!condition || !reaction) {
        diagnostics.error("Workflow " + workflow.getName() + " needs at least one condition (xor, inclusive, eventgw, "
            + "decide) and one reaction (task, decide, user, escalate).", workflow.get_SourcePositionStart());
      }
    }
    Set<Integer> open = checkOpenRules(spec, rules);
    for (int rule : rules) {
      if (open.contains(rule) && covered.contains(rule)) {
        diagnostics.error("Fault rule " + rule + " is stated as open but also covered by a workflow; keep one of them.",
            spec.get_SourcePositionStart());
      } else if (!covered.contains(rule) && !open.contains(rule)) {
        diagnostics.error("Fault rule " + rule + " is not covered by any workflow.", spec.get_SourcePositionStart());
      }
    }
  }

  static Set<Integer> faultRuleNumbers(String text) {
    Set<Integer> numbers = new TreeSet<>();
    Matcher matcher = FAULT_RULE.matcher(text);
    while (matcher.find()) {
      numbers.add(Integer.parseInt(matcher.group(1)));
    }
    if (numbers.isEmpty() && !text.isBlank()) {
      numbers.add(1); // Unnumbered text is one fault rule.
    }
    return numbers;
  }

  /** Soundness: one start, known targets, every element reachable, every path ends, splits join again. */
  private void checkGraph(ASTWorkflow workflow) {
    String where = "Workflow " + workflow.getName() + ": ";
    Map<String, ASTElement> elements = new LinkedHashMap<>();
    for (ASTElement element : workflow.getElementList()) {
      if (elements.put(name(element), element) != null) {
        diagnostics.error(where + "element " + name(element) + " is declared twice.", element.get_SourcePositionStart());
      }
    }
    List<ASTElement> starts = workflow.getElementList().stream().filter(e -> e instanceof ASTStartElement).toList();
    if (starts.size() != 1) {
      diagnostics.error(where + "a workflow needs exactly one start event.", workflow.get_SourcePositionStart());
    }
    boolean targetsKnown = true;
    for (ASTElement element : workflow.getElementList()) {
      for (String next : successors(element)) {
        if (!elements.containsKey(next)) {
          diagnostics.error(where + name(element) + " leads to " + next + ", which is not declared.",
              element.get_SourcePositionStart());
          targetsKnown = false;
        } else if (elements.get(next) instanceof ASTStartElement) {
          diagnostics.error(where + name(element) + " leads back to the start event.", element.get_SourcePositionStart());
        }
      }
    }
    if (starts.size() != 1 || !targetsKnown) {
      return;
    }
    Set<String> reachable = reach(elements, Set.of(name(starts.get(0))), false, null);
    Set<String> ends = new HashSet<>();
    elements.forEach((name, e) -> {
      if (e instanceof ASTEndElement || e instanceof ASTEscalateElement || e instanceof ASTTerminateElement) {
        ends.add(name);
      }
    });
    Set<String> ending = reach(elements, ends, true, null);
    for (ASTElement element : workflow.getElementList()) {
      if (!reachable.contains(name(element))) {
        diagnostics.error(where + name(element) + " is not reachable from the start event.",
            element.get_SourcePositionStart());
      } else if (!ending.contains(name(element))) {
        diagnostics.error(where + "no path from " + name(element) + " reaches an end.", element.get_SourcePositionStart());
      }
    }
    Map<String, Integer> incoming = new LinkedHashMap<>();
    elements.values().forEach(e -> successors(e).forEach(n -> incoming.merge(n, 1, Integer::sum)));
    for (ASTElement element : workflow.getElementList()) {
      if (element instanceof ASTParallelElement split && split.getNextList().size() > 1) {
        List<String> joins = elements.values().stream()
            .filter(j -> j instanceof ASTParallelElement && incoming.getOrDefault(name(j), 0) > 1).map(WorkflowCheck::name)
            .toList();
        if (!joined(elements, split.getNextList(), joins, ends)) {
          diagnostics.error(where + "the branches of " + split.getName()
              + " must meet again at one parallel gateway before any end.", element.get_SourcePositionStart());
        }
      } else if (element instanceof ASTInclusiveSplitElement split) {
        List<String> joins = elements.values().stream().filter(j -> j instanceof ASTInclusiveJoinElement)
            .map(WorkflowCheck::name).toList();
        if (!joined(elements, successors(split), joins, ends)) {
          diagnostics.error(where + "the branches of " + split.getName()
              + " must meet again at one inclusive join before any end.", element.get_SourcePositionStart());
        }
      }
    }
  }

  /** Whether all branches reach one of the joins without passing an end first. */
  private static boolean joined(Map<String, ASTElement> elements, List<String> branches, List<String> joins,
      Set<String> ends) {
    return joins.stream().anyMatch(join -> branches.stream().allMatch(branch -> {
      Set<String> before = reach(elements, Set.of(branch), false, join);
      return before.contains(join) && before.stream().noneMatch(ends::contains);
    }));
  }

  /** Elements reachable from the given ones (or that reach them, if backwards), not passing through stop. */
  private static Set<String> reach(Map<String, ASTElement> elements, Set<String> from, boolean backwards, String stop) {
    Map<String, List<String>> edges = new LinkedHashMap<>();
    elements.forEach((name, element) -> {
      for (String next : successors(element)) {
        if (backwards) {
          edges.computeIfAbsent(next, k -> new ArrayList<>()).add(name);
        } else {
          edges.computeIfAbsent(name, k -> new ArrayList<>()).add(next);
        }
      }
    });
    Set<String> seen = new HashSet<>(from);
    Deque<String> queue = new ArrayDeque<>(from);
    while (!queue.isEmpty()) {
      String current = queue.pop();
      if (current.equals(stop)) {
        continue;
      }
      for (String next : edges.getOrDefault(current, List.of())) {
        if (seen.add(next)) {
          queue.push(next);
        }
      }
    }
    return seen;
  }

  static String name(ASTElement element) {
    return switch (element) {
      case ASTStartElement e -> e.getName();
      case ASTTimerElement e -> e.getName();
      case ASTSignalElement e -> e.getName();
      case ASTXorElement e -> e.getName();
      case ASTInclusiveSplitElement e -> e.getName();
      case ASTInclusiveJoinElement e -> e.getName();
      case ASTEventGatewayElement e -> e.getName();
      case ASTParallelElement e -> e.getName();
      case ASTTaskElement e -> e.getName();
      case ASTDecideElement e -> e.getName();
      case ASTUserElement e -> e.getName();
      case ASTCallElement e -> e.getName();
      case ASTEscalateElement e -> e.getName();
      case ASTTerminateElement e -> e.getName();
      case ASTEndElement e -> e.getName();
      default -> throw new IllegalArgumentException("Unknown element");
    };
  }

  static List<ASTBoundary> boundaries(ASTElement element) {
    return switch (element) {
      case ASTUserElement e -> e.getBoundaryList();
      case ASTCallElement e -> e.getBoundaryList();
      default -> List.of();
    };
  }

  static String next(ASTBoundary boundary) {
    return switch (boundary) {
      case ASTTimerBoundary b -> b.getNext();
      case ASTConditionBoundary b -> b.getNext();
      case ASTSignalBoundary b -> b.getNext();
      default -> throw new IllegalArgumentException("Unknown boundary event");
    };
  }

  static List<String> successors(ASTElement element) {
    List<String> next = new ArrayList<>(switch (element) {
      case ASTStartElement e -> List.of(e.getNext());
      case ASTTimerElement e -> List.of(e.getNext());
      case ASTSignalElement e -> List.of(e.getNext());
      case ASTXorElement e -> List.of(e.getYes(), e.getNo());
      case ASTInclusiveSplitElement e -> {
        List<String> targets = new ArrayList<>(e.getBranchList().stream().map(ASTBranch::getNext).toList());
        targets.add(e.getFallback());
        yield targets;
      }
      case ASTInclusiveJoinElement e -> List.of(e.getNext());
      case ASTEventGatewayElement e -> List.of(e.getMet(), e.getDeadline());
      case ASTParallelElement e -> e.getNextList();
      case ASTTaskElement e -> List.of(e.getNext());
      case ASTDecideElement e -> List.of(e.getNext());
      case ASTUserElement e -> List.of(e.getYes(), e.getNo());
      case ASTCallElement e -> List.of(e.getNext());
      default -> List.<String>of();
    });
    boundaries(element).forEach(b -> next.add(next(b)));
    return next;
  }

  /** Resolves a reference by its qualified name or, if unique, by its attribute name alone. */
  private String resolve(ASTAttributeRef ref) {
    String text = String.join(".", ref.getNameList());
    if (keys.containsKey(ref)) {
      return keys.get(ref);
    }
    // A workflow requirement of the same workflow comes first; requirements of other workflows are not visible.
    String own = scopes.get(ref) + "." + text;
    if (requirements.attributes.containsKey(own) && scopes.get(ref).equals(requirements.attributes.get(own).workflow)) {
      keys.put(ref, own);
      return own;
    }
    List<String> matches = requirements.attributes.values().stream().filter(a -> a.workflow == null)
        .map(a -> a.key).filter(k -> k.equals(text) || k.endsWith("." + text)).toList();
    String key = matches.contains(text) ? text : matches.size() == 1 ? matches.get(0) : null;
    if (key == null) {
      diagnostics.error(matches.isEmpty()
          ? text + " is not an attribute of the service requirements or a workflow requirement of this workflow."
          : text + " is ambiguous; use one of " + matches + ".", ref.get_SourcePositionStart());
    } else {
      keys.put(ref, key);
    }
    return key;
  }

  /** A value has a known source if it is a bound attribute whose node the adapter can read. */
  private String readable(ASTAttributeRef ref) {
    String key = resolve(ref);
    if (key == null) {
      return null;
    }
    Map<String, Object> entry = binding.resolved.get(key);
    if (entry == null && requirements.attributes.get(key).workflow != null) {
      return null; // Already reported as a missing source.
    }
    if (binding.unbound.containsKey(key)) {
      diagnostics.error(unboundError(key), ref.get_SourcePositionStart());
      return null;
    }
    if (entry == null || !Boolean.TRUE.equals(entry.get("readable"))) {
      diagnostics.error(key + " has no known source: it is not bound to a readable node.", ref.get_SourcePositionStart());
      return null;
    }
    return key;
  }

  private String writable(ASTAttributeRef ref) {
    String key = resolve(ref);
    if (key == null) {
      return null;
    }
    Map<String, Object> entry = binding.resolved.get(key);
    if (entry == null && requirements.attributes.get(key).workflow != null) {
      return null; // Already reported as a missing source.
    }
    if (binding.unbound.containsKey(key)) {
      diagnostics.error(unboundError(key), ref.get_SourcePositionStart());
      return null;
    }
    if (entry == null || !"write".equals(entry.get("access"))) {
      diagnostics.error(key + " is not a writable bound attribute, so the workflow cannot set it.",
          ref.get_SourcePositionStart());
      return null;
    }
    return key;
  }

  /** The finding for a use of an attribute that the engineer marked as unbound. */
  private String unboundError(String key) {
    String reason = binding.unbound.get(key);
    return key + " is marked as unbound" + (reason.isEmpty() ? "" : " (" + reason + ")")
        + ": no node provides it, so no workflow can use it. If a fault rule cannot be implemented without it, "
        + "state openrule <number> needs " + key + "; instead of a workflow.";
  }

  /**
   * An open rule names an existing fault rule that no workflow covers, and only attributes that the
   * engineer marked as unbound; otherwise the rule must be implemented by a workflow.
   */
  private Set<Integer> checkOpenRules(ASTWorkflowSpec spec, Set<Integer> rules) {
    Set<Integer> open = new TreeSet<>();
    for (ASTOpenRule rule : spec.getOpenRuleList()) {
      int number = rule.getNatLiteral().getValue();
      boolean valid = true;
      if (!rules.contains(number)) {
        diagnostics.error("openrule " + number + " names a fault rule that does not exist.", rule.get_SourcePositionStart());
        valid = false;
      } else if (!open.add(number)) {
        diagnostics.error("Fault rule " + number + " is stated as open more than once.", rule.get_SourcePositionStart());
        valid = false;
      }
      List<String> attributes = new ArrayList<>();
      List<String> reasons = new ArrayList<>();
      for (ASTAttributeRef ref : rule.getAttributeRefList()) {
        String text = String.join(".", ref.getNameList());
        String key = requirements.attributes.containsKey(text) ? text : resolve(ref);
        if (key == null) {
          valid = false;
        } else if (!binding.unbound.containsKey(key)) {
          diagnostics.error("openrule " + number + " needs " + key + ", but " + key + " is not marked as unbound; "
              + "implement fault rule " + number + " with a workflow.", ref.get_SourcePositionStart());
          valid = false;
        } else {
          attributes.add(key);
          reasons.add(binding.unbound.get(key));
        }
      }
      if (valid) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("number", number);
        json.put("attributes", attributes);
        json.put("reasons", reasons);
        openRules.add(json);
      } else {
        open.remove(number);
      }
    }
    return open;
  }

  private void checkValue(String key, ASTSignedLiteral literal, boolean equalityOrSet, de.monticore.ast.ASTNode node) {
    String type = requirements.attributes.get(key).type;
    boolean ok = switch (type) {
      case "boolean" -> literal instanceof ASTBooleanLiteral && equalityOrSet;
      case "String" -> literal instanceof ASTStringLiteral && equalityOrSet;
      case "int", "long" -> literal instanceof ASTSignedNatLiteral;
      case "float", "double" -> literal instanceof ASTSignedNatLiteral || literal instanceof ASTSignedBasicDoubleLiteral;
      default -> false;
    };
    if (!ok) {
      diagnostics.error("The value or operator does not fit " + key + " of type " + type + ".",
          node.get_SourcePositionStart());
    } else if (!inRange(type, literal)) {
      diagnostics.error("The value is outside the range of " + key + " of type " + type + ".",
          node.get_SourcePositionStart());
    }
  }

  /** An attribute bound to a method without argument can only be set to the value that calls it. */
  private void checkCall(String key, ASTSignedLiteral literal, de.monticore.ast.ASTNode node) {
    Object callWhen = binding.resolved.get(key).get("callWhen");
    if (callWhen != null && literal instanceof ASTBooleanLiteral b && !callWhen.equals(b.getValue())) {
      diagnostics.error(key + " calls a method without argument, so it can only be set to " + callWhen + ".",
          node.get_SourcePositionStart());
    }
  }

  private static boolean inRange(String type, ASTSignedLiteral literal) {
    java.math.BigDecimal value;
    if (literal instanceof ASTSignedNatLiteral n) {
      value = new java.math.BigDecimal((n.isNegative() ? "-" : "") + n.getSource().replace("-", ""));
    } else if (literal instanceof ASTSignedBasicDoubleLiteral d) {
      value = new java.math.BigDecimal((d.isNegative() ? "-" : "") + d.getSource().replace("-", ""));
    } else {
      return true;
    }
    double limit = switch (type) {
      case "int" -> Integer.MAX_VALUE;
      case "long" -> Long.MAX_VALUE;
      case "float" -> Float.MAX_VALUE;
      default -> Double.MAX_VALUE;
    };
    return value.abs().compareTo(new java.math.BigDecimal(limit)) <= 0;
  }

  String key(ASTAttributeRef ref) {
    return keys.get(ref);
  }

  BindingCheck binding() {
    return binding;
  }

  String type(String key) {
    return requirements.attributes.get(key).type;
  }

  static int seconds(ASTDuration duration) {
    int value = duration.getNatLiteral().getValue();
    return duration.getUnit().equals("min") ? value * 60 : value;
  }

  static Object literal(ASTSignedLiteral literal) {
    if (literal instanceof ASTBooleanLiteral b) {
      return b.getValue();
    }
    if (literal instanceof ASTStringLiteral s) {
      return s.getValue();
    }
    if (literal instanceof ASTSignedNatLiteral n) {
      return n.getValue();
    }
    if (literal instanceof ASTSignedBasicDoubleLiteral n) {
      return n.getValue();
    }
    throw new IllegalArgumentException("Unsupported literal");
  }
}
