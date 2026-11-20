package de.aide;

import de.aide.binding.BindingMill;
import de.aide.binding._ast.ASTBindingEntry;
import de.aide.binding._ast.ASTBindingSpec;
import de.aide.binding._ast.ASTConversion;
import de.aide.binding._ast.ASTMethodCall;
import de.aide.binding._ast.ASTTypeConversion;
import de.aide.binding._ast.ASTUnboundEntry;
import de.aide.binding._ast.ASTUnitConversion;
import de.aide.binding._ast.ASTValueMapping;
import de.aide.binding._ast.ASTValuePair;
import de.aide.binding._cocos.BindingCoCoChecker;
import de.se_rwth.commons.logging.Log;

import javax.measure.Unit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a binding specification and checks it with context conditions: the
 * attribute exists in the service or workflow requirements and is bindable, the
 * NodeId exists, the pair is not incompatible, and every required conversion is
 * stated explicitly. An incompatible pair is admissible only through a value
 * mapping that the engineer confirmed, for example a writable Boolean that calls a
 * method without argument. If the whole text does not parse, each line is checked
 * on its own. Conversion factors come from the unit library, never from the
 * specification. An attribute that no node can provide is marked as unbound by the
 * engineer; it has no node and no value, and the later checks reject every use of it.
 */
public class BindingCheck {

  public String name;
  public final Diagnostics diagnostics = new Diagnostics();
  public final List<Map<String, Object>> entries = new ArrayList<>();
  /** Valid entries with everything the runtime adapter needs, by attribute key. */
  public final Map<String, Map<String, Object>> resolved = new LinkedHashMap<>();
  /** Attributes that the engineer marked as unbound, with the reason (empty if none was given). */
  public final Map<String, String> unbound = new LinkedHashMap<>();

  public static BindingCheck run(Requirements requirements, NodeSet nodeSet, String text) throws Exception {
    BindingCheck check = new BindingCheck();
    BindingMill.init();
    var parser = BindingMill.parser();
    Optional<ASTBindingSpec> ast = parser.parse_String(text);
    if (parser.hasErrors() || ast.isEmpty()) {
      Log.clearFindings();
      check.diagnostics.error("The binding specification cannot be parsed as a whole, so each line was checked on "
          + "its own.", null);
      check.checkLines(requirements, nodeSet, text);
      return check;
    }
    check.name = ast.get().getName();
    BindingCoCoChecker checker = new BindingCoCoChecker();
    checker.addCoCo((ASTBindingSpec spec) -> {
      if (!spec.getRequirements().equals(requirements.name)) {
        check.diagnostics.error("The binding is for " + spec.getRequirements()
            + ", but the service requirements are " + requirements.name + ".", spec.get_SourcePositionStart());
      }
    });
    Set<String> seen = new HashSet<>();
    String[] lines = text.split("\\R");
    checker.addCoCo((ASTBindingEntry entry) -> {
      int line = entry.get_SourcePositionStart().getLine();
      check.checkEntry(entry, requirements, nodeSet, seen, line, lines[line - 1].trim());
    });
    checker.addCoCo((ASTUnboundEntry entry) -> check.checkUnbound(entry, requirements, seen));
    checker.checkAll(ast.get());
    return check;
  }

  /** An unbound entry names a bindable attribute that has no other entry. */
  private void checkUnbound(ASTUnboundEntry entry, Requirements requirements, Set<String> seen) {
    String key = String.join(".", entry.getAttributeRef().getNameList());
    Requirements.Attribute attribute = requirements.attributes.get(key);
    String error = null;
    if (attribute == null) {
      error = "Attribute " + key + " does not exist in the service or workflow requirements.";
    } else if (!attribute.bindable()) {
      error = key + " is " + attribute.kind + " and cannot be marked as unbound.";
    } else if (!seen.add(key)) {
      error = key + " is bound more than once.";
    }
    if (error != null) {
      diagnostics.error(error, entry.get_SourcePositionStart());
    } else {
      unbound.put(key, entry.isPresentReason() ? entry.getReason().getValue() : "");
    }
  }

  /**
   * If the whole answer does not parse, every line that looks like an entry is parsed and checked
   * on its own, so that valid lines are kept and only invalid lines need a revision.
   */
  @SuppressWarnings("unchecked")
  private void checkLines(Requirements requirements, NodeSet nodeSet, String text) throws Exception {
    Set<String> seen = new HashSet<>();
    String[] lines = text.split("\\R");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty() || line.startsWith("binding ") || line.equals("}")) {
        continue;
      }
      var parser = BindingMill.parser();
      Optional<ASTBindingEntry> entry = parser.parse_StringBindingEntry(line);
      if (!parser.hasErrors() && entry.isPresent()) {
        checkEntry(entry.get(), requirements, nodeSet, seen, i + 1, line);
        continue;
      }
      String reason = Log.getFindings().stream().filter(f -> f.isError()).map(f -> f.getMsg().split("\n")[0].trim())
          .findFirst().orElse("syntax error");
      Log.clearFindings();
      var unboundParser = BindingMill.parser();
      Optional<ASTUnboundEntry> unboundEntry = unboundParser.parse_StringUnboundEntry(line);
      Log.clearFindings();
      if (!unboundParser.hasErrors() && unboundEntry.isPresent()) {
        checkUnbound(unboundEntry.get(), requirements, seen);
        continue;
      }
      Matcher nodeId = Pattern.compile("\"([^\"]*)\"").matcher(line);
      Map<String, Object> json = entryJson(line.split("\\s|->")[0], nodeId.find() ? nodeId.group(1) : null, i + 1,
          line);
      ((List<String>) json.get("errors")).add("The line cannot be parsed: " + reason + ".");
    }
  }

  private Map<String, Object> entryJson(String key, String nodeId, int line, String text) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("attribute", key);
    json.put("nodeId", nodeId);
    json.put("line", line);
    json.put("text", text);
    json.put("errors", new ArrayList<String>());
    entries.add(json);
    return json;
  }

  @SuppressWarnings("unchecked")
  private void checkEntry(ASTBindingEntry entry, Requirements requirements, NodeSet nodeSet, Set<String> seen,
      int line, String text) {
    String key = String.join(".", entry.getAttributeRef().getNameList());
    String nodeId = entry.getNodeId().getValue();
    Map<String, Object> json = entryJson(key, nodeId, line, text);
    List<String> errors = (List<String>) json.get("errors");

    Requirements.Attribute attribute = requirements.attributes.get(key);
    NodeSet.UaNode node = nodeSet.nodes.get(nodeId);
    if (attribute == null) {
      errors.add("Attribute " + key + " does not exist in the service or workflow requirements.");
    } else if ("structured".equals(attribute.kind)) {
      errors.add(key + " is structured; bind its fields one by one.");
    } else if ("unsupported".equals(attribute.kind)) {
      errors.add(key + " is not supported: " + attribute.note + ".");
    }
    if (!seen.add(key)) {
      errors.add(key + " is bound more than once.");
    }
    if (node == null) {
      errors.add("NodeId " + nodeId + " does not exist in the NodeSet.");
    }
    if (!errors.isEmpty()) {
      return;
    }
    Compatibility.Match match = Compatibility.classify(attribute, node);
    boolean mapped = entry.getConversionList().stream().anyMatch(c -> c instanceof ASTValueMapping);
    if (match.incompatible() && !(match.mapping != null && mapped)) {
      errors.add(key + " and " + nodeId + " are incompatible: " + String.join("; ", match.reasons)
          + (match.mapping == null ? "." : "; only the engineer can bind them through a value mapping."));
      return;
    }
    match.findings.forEach(finding -> errors.add(key + " cannot be bound to " + nodeId + ": " + finding + "."));
    checkConversions(entry, match, errors);
    if (errors.isEmpty()) {
      resolved.put(key, resolve(attribute, node, nodeSet, match, entry));
    }
  }

  private void checkConversions(ASTBindingEntry entry, Compatibility.Match match, List<String> errors) {
    boolean call = "map true -> call".equals(match.mapping);
    boolean unit = false;
    boolean widen = false;
    boolean method = false;
    for (ASTConversion conversion : entry.getConversionList()) {
      if (conversion instanceof ASTUnitConversion c) {
        unit = true;
        String source = Requirements.unitText(c.getSource());
        String target = Requirements.unitText(c.getTarget());
        if (match.unitFrom == null) {
          errors.add("convert " + source + " to " + target + " is not needed.");
        } else if (!sameUnit(source, match.unitFrom) || !sameUnit(target, match.unitTo)) {
          errors.add("The value flows from " + match.unitFrom + " to " + match.unitTo
              + "; state " + match.clauses().get(0) + ".");
        }
      } else if (conversion instanceof ASTTypeConversion c) {
        widen = true;
        if (match.widenFrom == null) {
          errors.add("widen " + c.getSource() + " to " + c.getTarget() + " is not needed.");
        } else if (!c.getSource().equals(match.widenFrom) || !c.getTarget().equals(match.widenTo)) {
          errors.add("The value is widened from " + match.widenFrom + " to " + match.widenTo
              + "; state widen " + match.widenFrom + " to " + match.widenTo + ".");
        }
      } else if (conversion instanceof ASTMethodCall) {
        method = true;
        if (!match.method) {
          errors.add("via method is stated, but the node is not a method.");
        } else if (call) {
          errors.add("The method takes no argument, so it cannot take the value: state map true -> call.");
        }
      } else if (conversion instanceof ASTValueMapping c) {
        if (match.mapping == null) {
          errors.add("A value mapping is stated, but the node needs none.");
        } else {
          checkValueMapping(c, call, errors);
        }
      }
    }
    if (match.unitFrom != null && !unit) {
      errors.add("Missing unit conversion: state " + match.clauses().get(0) + ".");
    }
    if (match.widenFrom != null && !widen) {
      errors.add("Missing type conversion: state widen " + match.widenFrom + " to " + match.widenTo + ".");
    }
    if (match.method && !call && !method) {
      errors.add("The node is a method: state via method.");
    }
  }

  /**
   * A method without argument needs exactly one pair such as true -> call; an integer node needs
   * true and false mapped to two different integers. Only the engineer confirms a value mapping.
   */
  private void checkValueMapping(ASTValueMapping mapping, boolean call, List<String> errors) {
    List<ASTValuePair> pairs = mapping.getValuePairList();
    if (call) {
      if (pairs.size() != 1 || !pairs.get(0).isCall()) {
        errors.add("A method without argument expresses one value: state exactly one pair such as map true -> call.");
      }
    } else {
      Map<Boolean, Integer> values = new LinkedHashMap<>();
      for (ASTValuePair pair : pairs) {
        if (pair.isCall()) {
          errors.add("The node is no method without argument, so the value mapping cannot call it.");
        } else if (values.put(pair.getBooleanLiteral().getValue(), pair.getSignedNatLiteral().getValue()) != null) {
          errors.add("The value mapping maps " + pair.getBooleanLiteral().getValue() + " twice.");
        }
      }
      if (values.size() != 2 || values.get(true).equals(values.get(false))) {
        errors.add("The value mapping must map true and false to two different integers.");
      }
    }
    if (!mapping.isConfirmed()) {
      errors.add("Only the engineer can bind through a value mapping and confirm it.");
    }
  }

  private static boolean sameUnit(String a, String b) {
    Optional<Unit<?>> x = Units.parse(a);
    Optional<Unit<?>> y = Units.parse(b);
    return x.isPresent() && y.isPresent() && Units.sameUnit(x.get(), y.get());
  }

  private static Map<String, Object> resolve(Requirements.Attribute attribute, NodeSet.UaNode node,
      NodeSet nodeSet, Compatibility.Match match, ASTBindingEntry entry) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("attribute", attribute.key);
    json.put("type", attribute.type);
    json.put("unit", attribute.unit);
    json.put("access", attribute.readOnly ? "read" : "write");
    json.put("nodeId", node.nodeId);
    json.put("namespaceUri", nodeSet.namespaceUri(node.nodeId));
    json.put("identifier", NodeSet.identifier(node.nodeId));
    json.put("nodeClass", node.nodeClass);
    json.put("readable", node.readable());
    if (attribute.workflow != null) {
      json.put("workflow", attribute.workflow);
    }
    if (match.method) {
      NodeSet.UaNode parent = nodeSet.nodes.get(node.parentId);
      if (node.inputs.isEmpty()) {
        // A method without argument is called when the attribute is set to the mapped value.
        entry.getConversionList().stream().filter(c -> c instanceof ASTValueMapping)
            .flatMap(c -> ((ASTValueMapping) c).getValuePairList().stream())
            .forEach(pair -> json.put("callWhen", pair.getBooleanLiteral().getValue()));
      } else {
        json.put("dataType", node.inputs.get(0).dataType);
      }
      json.put("objectNamespaceUri", nodeSet.namespaceUri(parent.nodeId));
      json.put("objectIdentifier", NodeSet.identifier(parent.nodeId));
    } else {
      json.put("dataType", node.dataType);
    }
    if (match.unitFrom != null) {
      json.put("scale", match.scale);
      json.put("offset", match.offset);
    }
    for (ASTConversion conversion : entry.getConversionList()) {
      if (conversion instanceof ASTValueMapping mapping && !json.containsKey("callWhen")) {
        Map<String, Integer> values = new LinkedHashMap<>();
        mapping.getValuePairList().forEach(pair ->
            values.put(String.valueOf(pair.getBooleanLiteral().getValue()), pair.getSignedNatLiteral().getValue()));
        json.put("valueMap", values);
      }
    }
    return json;
  }
}
