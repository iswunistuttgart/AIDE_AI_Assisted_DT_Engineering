package de.aide;

import com.google.gson.GsonBuilder;
import de.aide.watchdogworkflow.WatchdogWorkflowMill;
import de.se_rwth.commons.logging.Log;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line entry point used by the backend. Every command prints one JSON
 * object to standard output. Workflow requirements in watchdog.wf are bound like
 * service attributes.
 *
 * <pre>
 * candidates      requirements.cd nodeset.xml [watchdog.wf]
 * check-binding   requirements.cd nodeset.xml binding.bind [watchdog.wf]
 * check-watchdog  requirements.cd nodeset.xml binding.bind watchdog.wf fault-rules.md [output-directory]
 * check-dashboard requirements.cd nodeset.xml binding.bind dashboard.dash watchdog.wf
 * </pre>
 */
public class Cli {

  public static void main(String[] args) throws Exception {
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    System.setOut(System.err); // Keep MontiCore log output away from the JSON result.
    Log.init();
    Log.enableFailQuick(false);
    Map<String, Object> result = switch (args.length == 0 ? "" : args[0]) {
      case "candidates" -> candidates(Path.of(args[1]), Path.of(args[2]), optional(args, 3));
      case "nodes" -> nodes(Path.of(args[1]));
      case "check-binding" -> checkBinding(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), optional(args, 4));
      case "check-watchdog" -> checkWatchdog(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]),
          Path.of(args[5]), args.length > 6 ? Path.of(args[6]) : null);
      case "check-dashboard" -> checkDashboard(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]),
          Path.of(args[5]));
      default -> throw new IllegalArgumentException("Unknown command. See de.aide.Cli for usage.");
    };
    out.println(new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create().toJson(result));
  }

  private static Path optional(String[] args, int index) {
    return args.length > index ? Path.of(args[index]) : null;
  }

  /** The service requirements together with the workflow requirements of the watchdog workflows. */
  private static Requirements requirements(Path cd, Path workflows) throws Exception {
    Requirements requirements = Requirements.parse(Files.readString(cd));
    if (workflows != null && Files.exists(workflows)) {
      requirements.addWorkflowRequirements(Files.readString(workflows));
    }
    return requirements;
  }

  /**
   * Compatibility filter: the non-incompatible nodes for every bindable attribute, and the
   * incompatible nodes that only the engineer can bind through a value mapping.
   */
  static Map<String, Object> candidates(Path cd, Path nodeSetFile, Path workflows) throws Exception {
    Requirements requirements = requirements(cd, workflows);
    NodeSet nodeSet = NodeSet.load(nodeSetFile);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("requirements", requirements.name);
    result.put("errors", requirements.diagnostics.errors);
    result.put("nodeCount", nodeSet.nodes.size());
    List<Map<String, Object>> attributes = new ArrayList<>();
    for (Requirements.Attribute attribute : requirements.attributes.values()) {
      Map<String, Object> json = attribute.toJson();
      if (attribute.bindable()) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        List<Map<String, Object>> manual = new ArrayList<>();
        List<Map<String, Object>> unsupported = new ArrayList<>();
        for (NodeSet.UaNode node : nodeSet.nodes.values()) {
          Compatibility.Match match = Compatibility.classify(attribute, node);
          if (!match.incompatible() || match.mapping != null) {
            Map<String, Object> candidate = describe(nodeSet, node);
            candidate.putAll(match.toJson());
            (match.incompatible() ? manual : candidates).add(candidate);
          } else {
            // Excluded only by an unsupported rule: shown to the engineer, never offered for binding.
            Compatibility.unsupported(attribute, node).ifPresent(reason -> {
              Map<String, Object> excluded = describe(nodeSet, node);
              excluded.put("reason", reason);
              unsupported.add(excluded);
            });
          }
        }
        candidates.sort(Comparator.comparing((Map<String, Object> c) -> !"compatible".equals(c.get("classification")))
            .thenComparing(c -> (String) c.get("path")));
        manual.sort(Comparator.comparing(c -> (String) c.get("path")));
        unsupported.sort(Comparator.comparing(c -> (String) c.get("path")));
        json.put("candidates", candidates);
        json.put("manual", manual);
        json.put("unsupported", unsupported);
      }
      attributes.add(json);
    }
    result.put("attributes", attributes);
    return result;
  }

  /** Every node of the NodeSet without filtering, with their descriptions. */
  static Map<String, Object> nodes(Path nodeSetFile) throws Exception {
    NodeSet nodeSet = NodeSet.load(nodeSetFile);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("nodes", nodeSet.nodes.values().stream().map(node -> describe(nodeSet, node)).toList());
    return result;
  }

  static Map<String, Object> checkBinding(Path cd, Path nodeSetFile, Path binding, Path workflows) throws Exception {
    Requirements requirements = requirements(cd, workflows);
    NodeSet nodeSet = NodeSet.load(nodeSetFile);
    Map<String, Object> result = new LinkedHashMap<>();
    if (!requirements.diagnostics.isEmpty()) {
      result.put("errors", requirements.diagnostics.errors);
      return result;
    }
    BindingCheck check = BindingCheck.run(requirements, nodeSet, Files.readString(binding));
    result.put("name", check.name);
    result.put("errors", check.diagnostics.errors);
    result.put("entries", check.entries);
    result.put("resolved", check.resolved);
    result.put("unbound", check.unbound);
    result.put("attributes", requirements.attributes.values().stream().map(Requirements.Attribute::toJson).toList());
    return result;
  }

  static Map<String, Object> checkWatchdog(Path cd, Path nodeSetFile, Path binding, Path workflows, Path faultRules,
      Path outDir) throws Exception {
    Requirements requirements = requirements(cd, workflows);
    NodeSet nodeSet = NodeSet.load(nodeSetFile);
    Map<String, Object> result = new LinkedHashMap<>();
    if (!requirements.diagnostics.isEmpty()) {
      result.put("errors", requirements.diagnostics.errors);
      return result;
    }
    BindingCheck bindingCheck = BindingCheck.run(requirements, nodeSet, Files.readString(binding));
    WorkflowCheck check = WorkflowCheck.run(requirements, bindingCheck, Files.readString(workflows),
        Files.readString(faultRules));
    result.put("errors", check.diagnostics.errors);
    result.put("openRules", check.openRules);
    if (!check.diagnostics.isEmpty()) {
      return result;
    }
    List<Map<String, Object>> generated = new ArrayList<>();
    for (var workflow : check.ast.getWorkflowList()) {
      BpmnGenerator.Output output = BpmnGenerator.generate(workflow, check);
      generated.add(output.workflow());
      if (outDir != null) {
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve(BpmnGenerator.fileName(workflow.getName()) + ".bpmn"), output.bpmn());
        for (var decision : output.decisions().entrySet()) {
          Files.writeString(outDir.resolve(decision.getKey() + ".dmn"), decision.getValue());
        }
        for (var form : output.forms().entrySet()) {
          Files.writeString(outDir.resolve(form.getKey() + ".form"), form.getValue());
        }
      }
    }
    result.put("workflows", generated);
    return result;
  }

  static Map<String, Object> checkDashboard(Path cd, Path nodeSetFile, Path binding, Path dashboard, Path workflows)
      throws Exception {
    Requirements requirements = requirements(cd, workflows);
    NodeSet nodeSet = NodeSet.load(nodeSetFile);
    Map<String, Object> result = new LinkedHashMap<>();
    if (!requirements.diagnostics.isEmpty()) {
      result.put("errors", requirements.diagnostics.errors);
      return result;
    }
    BindingCheck bindingCheck = BindingCheck.run(requirements, nodeSet, Files.readString(binding));
    Set<String> names = new HashSet<>();
    if (Files.exists(workflows)) {
      WatchdogWorkflowMill.init();
      var parser = WatchdogWorkflowMill.parser();
      parser.parse_String(Files.readString(workflows))
          .ifPresent(spec -> spec.getWorkflowList().forEach(w -> names.add(w.getName())));
      Log.clearFindings();
    }
    DashboardCheck check = DashboardCheck.run(bindingCheck, names, Files.readString(dashboard));
    result.put("errors", check.diagnostics.errors);
    result.put("views", check.views);
    return result;
  }

  private static Map<String, Object> describe(NodeSet nodeSet, NodeSet.UaNode node) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("nodeId", node.nodeId);
    json.put("path", nodeSet.path(node));
    json.put("description", node.description);
    json.put("nodeClass", node.nodeClass);
    if ("Method".equals(node.nodeClass)) {
      json.put("arguments", node.inputs.stream().map(a -> a.name + ": " + a.dataType).toList());
      if (!node.outputs.isEmpty()) {
        json.put("returns", node.outputs.stream().map(a -> a.name + ": " + a.dataType).toList());
      }
    } else {
      json.put("dataType", node.dataType);
      json.put("unit", node.unit);
      json.put("access", node.writable() ? (node.readable() ? "readwrite" : "write") : "read");
    }
    return json;
  }
}
