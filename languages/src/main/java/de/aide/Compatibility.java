package de.aide;

import javax.measure.Unit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic compatibility rules between a required attribute and an OPC UA
 * node. Every pair is compatible, convertible or incompatible. Names are never
 * used, so a semantic cut-off cannot discard the correct node. Some incompatible
 * pairs, such as a writable Boolean on a method without argument, can be bound
 * only by the engineer through an explicit value mapping.
 */
public final class Compatibility {

  /** Attribute types and the OPC UA type that represents them exactly. */
  static final Map<String, String> EXACT = Map.of(
      "boolean", "Boolean", "int", "Int32", "long", "Int64",
      "float", "Float", "double", "Double", "String", "String");

  /** Lossless widening between OPC UA types. */
  static final Map<String, Set<String>> WIDENING = Map.of(
      "SByte", Set.of("Int16", "Int32", "Int64", "Float", "Double"),
      "Byte", Set.of("Int16", "UInt16", "Int32", "UInt32", "Int64", "UInt64", "Float", "Double"),
      "Int16", Set.of("Int32", "Int64", "Float", "Double"),
      "UInt16", Set.of("Int32", "UInt32", "Int64", "UInt64", "Float", "Double"),
      "Int32", Set.of("Int64", "Double"),
      "UInt32", Set.of("Int64", "UInt64", "Double"),
      "Float", Set.of("Double"));

  static final Set<String> INTEGERS = Set.of("SByte", "Byte", "Int16", "UInt16", "Int32", "UInt32", "Int64", "UInt64");
  static final Set<String> FLOATS = Set.of("Float", "Double");

  /** The result of classifying one attribute-node pair. */
  public static class Match {
    public String classification = "compatible";
    public final List<String> reasons = new ArrayList<>();
    public boolean method;
    /** For an incompatible pair, the value mapping through which only the engineer may bind it. */
    public String mapping;
    /** Problems that keep the node but block a binding, such as an unknown unit code. */
    public final List<String> findings = new ArrayList<>();
    public String unitFrom;
    public String unitTo;
    public double scale = 1;
    public double offset = 0;
    public String widenFrom;
    public String widenTo;

    void incompatible(String reason) {
      classification = "incompatible";
      reasons.add(reason);
    }

    void convertible() {
      if (!"incompatible".equals(classification)) {
        classification = "convertible";
      }
    }

    public boolean incompatible() {
      return "incompatible".equals(classification);
    }

    /** The conversion clauses that a binding entry must state, in canonical order. */
    public List<String> clauses() {
      List<String> clauses = new ArrayList<>();
      if (unitFrom != null) {
        clauses.add("convert " + unitRef(unitFrom) + " to " + unitRef(unitTo));
      }
      if (widenFrom != null) {
        clauses.add("widen " + widenFrom + " to " + widenTo);
      }
      if (method && !"map true -> call".equals(mapping)) {
        clauses.add("via method");
      }
      if (mapping != null) {
        clauses.add(mapping);
      }
      return clauses;
    }

    public Map<String, Object> toJson() {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("classification", classification);
      if (!reasons.isEmpty()) {
        json.put("reasons", reasons);
      }
      if (!findings.isEmpty()) {
        json.put("findings", findings);
      }
      json.put("clauses", clauses());
      return json;
    }
  }

  private Compatibility() {
  }

  public static Match classify(Requirements.Attribute attribute, NodeSet.UaNode node) {
    Match match = new Match();
    if (node.property) {
      match.incompatible("node is a property of another node");
      return match;
    }
    if ("Object".equals(node.nodeClass)) {
      match.incompatible("object nodes cannot be bound; structured attributes are bound field by field");
      return match;
    }
    String valueType;
    String nodeUnit;
    String unitError = null;
    if ("Method".equals(node.nodeClass)) {
      if (attribute.readOnly) {
        match.incompatible("getter methods are not supported");
        return match;
      }
      if (node.parentId == null) {
        match.incompatible("method has no parent object to call it on");
        return match;
      }
      if (node.inputs.isEmpty()) {
        match.incompatible("method takes no argument, so it cannot carry a value");
        if ("boolean".equals(attribute.type)) {
          // A call expresses only one of the two Boolean values, so only the engineer maps it.
          match.method = true;
          match.mapping = "map true -> call";
        }
        return match;
      }
      if (node.inputs.size() > 1) {
        match.incompatible("method takes " + node.inputs.size() + " arguments; only one is supported");
        return match;
      }
      NodeSet.Argument argument = node.inputs.get(0);
      if (argument.valueRank != -1) {
        match.incompatible("array arguments are not supported");
        return match;
      }
      match.method = true;
      valueType = argument.dataType;
      nodeUnit = null;
    } else {
      if (attribute.readOnly && !node.readable()) {
        match.incompatible("variable is not readable");
      }
      if (!attribute.readOnly && !node.writable()) {
        match.incompatible("no write access");
      }
      if (node.valueRank != -1) {
        match.incompatible("arrays are not supported");
      }
      valueType = node.dataType;
      nodeUnit = node.unit;
      unitError = node.unitError;
    }
    if (match.incompatible()) {
      return match;
    }
    classifyType(attribute, valueType, match);
    if (!match.incompatible()) {
      classifyUnit(attribute, nodeUnit, unitError, valueType, match);
    }
    return match;
  }

  /**
   * Explains an exclusion that only an unsupported rule causes: the node would provide the value
   * if AIDE supported getter methods or arrays, so the engineer should mark the attribute as
   * unbound rather than bind a merely type-compatible node. The classification of the pair is
   * not changed; this only names the reason for the engineer.
   */
  public static Optional<String> unsupported(Requirements.Attribute attribute, NodeSet.UaNode node) {
    Match match = classify(attribute, node);
    if (!match.incompatible() || match.mapping != null || node.property) {
      return Optional.empty();
    }
    Match value = new Match();
    if ("Method".equals(node.nodeClass) && match.reasons.contains("getter methods are not supported")) {
      // A getter takes no argument and returns exactly one scalar value of a fitting type.
      if (!node.inputs.isEmpty() || node.outputs.size() != 1 || node.outputs.get(0).valueRank != -1) {
        return Optional.empty();
      }
      classifyType(attribute, node.outputs.get(0).dataType, value);
      return value.incompatible() ? Optional.empty()
          : Optional.of("only available through getter method (not supported)");
    }
    if (match.reasons.equals(List.of("arrays are not supported"))) {
      classifyType(attribute, node.dataType, value);
      if (!value.incompatible()) {
        classifyUnit(attribute, node.unit, node.unitError, node.dataType, value);
      }
      return value.incompatible() ? Optional.empty() : Optional.of("only available as an array (not supported)");
    }
    return Optional.empty();
  }

  private static void classifyType(Requirements.Attribute attribute, String nodeType, Match match) {
    String attributeType = EXACT.get(attribute.type);
    if (nodeType == null || !(EXACT.containsValue(nodeType) || INTEGERS.contains(nodeType))) {
      match.incompatible("data type " + nodeType + " is not supported");
      return;
    }
    if (attributeType.equals(nodeType)) {
      return;
    }
    // Data flows from node to attribute for reads and from attribute to node for writes.
    String from = attribute.readOnly ? nodeType : attributeType;
    String to = attribute.readOnly ? attributeType : nodeType;
    if (WIDENING.getOrDefault(from, Set.of()).contains(to)) {
      match.widenFrom = attribute.readOnly ? nodeType : attribute.type;
      match.widenTo = attribute.readOnly ? attribute.type : nodeType;
      match.convertible();
    } else if (!attribute.readOnly && "Boolean".equals(from) && INTEGERS.contains(to)) {
      // The integer may encode further states, so only the engineer maps the two Boolean values.
      match.incompatible("Boolean to " + to + " is no lossless conversion");
      match.mapping = "map true -> 1, false -> 0";
    } else {
      match.incompatible("lossy or invalid conversion from " + from + " to " + to);
    }
  }

  private static void classifyUnit(Requirements.Attribute attribute, String nodeUnit, String unitError, String nodeType,
      Match match) {
    if (attribute.unit != null && unitError != null) {
      match.findings.add("the node's " + unitError);
      return;
    }
    if (attribute.unit == null || nodeUnit == null) {
      return; // A missing unit never removes a node.
    }
    Optional<Unit<?>> attributeUnit = Units.parse(attribute.unit);
    Optional<Unit<?>> unit = Units.parse(nodeUnit);
    if (attributeUnit.isEmpty() || unit.isEmpty()) {
      match.findings.add("unit " + nodeUnit + " cannot be parsed");
      return;
    }
    if (!Units.sameDimension(attributeUnit.get(), unit.get())) {
      match.incompatible("unit " + nodeUnit + " has another dimension than " + attribute.unit);
      return;
    }
    if (Units.sameUnit(attributeUnit.get(), unit.get())) {
      return;
    }
    String target = attribute.readOnly ? EXACT.get(attribute.type) : nodeType;
    if (!FLOATS.contains(target)) {
      match.incompatible("unit conversion from " + nodeUnit + " to " + attribute.unit
          + " needs a floating-point target");
      return;
    }
    match.unitFrom = attribute.readOnly ? nodeUnit : attribute.unit;
    match.unitTo = attribute.readOnly ? attribute.unit : nodeUnit;
    double[] linear = Units.linear(Units.parse(match.unitFrom).get(), Units.parse(match.unitTo).get());
    match.scale = linear[0];
    match.offset = linear[1];
    match.convertible();
  }

  static String unitRef(String unit) {
    return unit.matches("[A-Za-z_][A-Za-z0-9_]*") ? unit : "[" + unit + "]";
  }
}
