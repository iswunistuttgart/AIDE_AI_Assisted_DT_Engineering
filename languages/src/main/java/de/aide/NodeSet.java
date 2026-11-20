package de.aide;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The parts of an OPC UA NodeSet2 file that binding generation needs. */
public class NodeSet {

  public static class Argument {
    public String name;
    public String dataType;
    public int valueRank = -1;
  }

  public static class UaNode {
    public String nodeId;
    public String nodeClass; // Object, Variable or Method
    public String browseName;
    public String displayName;
    public String description = "";
    public String parentId;
    public String dataType;
    public int accessLevel = 1;
    public int valueRank = -1;
    public String unit;
    /** Set when EUInformation.UnitId holds a code that the unit library does not know. */
    public String unitError;
    public boolean property;
    public List<Argument> inputs = new ArrayList<>();
    public List<Argument> outputs = new ArrayList<>();

    public boolean readable() {
      return "Variable".equals(nodeClass) && (accessLevel & 1) != 0;
    }

    public boolean writable() {
      return "Variable".equals(nodeClass) && (accessLevel & 2) != 0;
    }
  }

  static final Map<String, String> BUILTIN_TYPES = Map.ofEntries(
      Map.entry("i=1", "Boolean"), Map.entry("i=2", "SByte"), Map.entry("i=3", "Byte"),
      Map.entry("i=4", "Int16"), Map.entry("i=5", "UInt16"), Map.entry("i=6", "Int32"),
      Map.entry("i=7", "UInt32"), Map.entry("i=8", "Int64"), Map.entry("i=9", "UInt64"),
      Map.entry("i=10", "Float"), Map.entry("i=11", "Double"), Map.entry("i=12", "String"),
      Map.entry("i=13", "DateTime"));

  private static final Set<String> HIERARCHICAL = Set.of(
      "HasComponent", "HasProperty", "Organizes", "HasOrderedComponent", "i=47", "i=46", "i=35", "i=49");

  private static final Set<String> PROPERTY_NAMES = Set.of(
      "EngineeringUnits", "InputArguments", "OutputArguments", "EURange", "InstrumentRange");

  public final List<String> namespaceUris = new ArrayList<>();
  public final Map<String, UaNode> nodes = new LinkedHashMap<>();
  private final Map<String, String> aliases = new HashMap<>();

  public static NodeSet load(Path file) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    Document document = factory.newDocumentBuilder().parse(file.toFile());
    NodeSet nodeSet = new NodeSet();
    nodeSet.read(document.getDocumentElement());
    return nodeSet;
  }

  private void read(Element root) {
    for (Element uris : children(root, "NamespaceUris")) {
      for (Element uri : children(uris, "Uri")) {
        namespaceUris.add(uri.getTextContent().trim());
      }
    }
    for (Element aliasList : children(root, "Aliases")) {
      for (Element alias : children(aliasList, "Alias")) {
        aliases.put(alias.getAttribute("Alias"), alias.getTextContent().trim());
      }
    }
    Map<String, List<String>> forwardChildren = new HashMap<>();
    Map<String, Element> elements = new HashMap<>();
    for (Element element : allChildren(root)) {
      String nodeClass = switch (element.getLocalName()) {
        case "UAObject" -> "Object";
        case "UAVariable" -> "Variable";
        case "UAMethod" -> "Method";
        default -> null;
      };
      if (nodeClass == null) {
        continue;
      }
      UaNode node = new UaNode();
      node.nodeId = element.getAttribute("NodeId");
      node.nodeClass = nodeClass;
      node.browseName = stripNamespacePrefix(element.getAttribute("BrowseName"));
      node.displayName = textOf(element, "DisplayName", node.browseName);
      node.description = textOf(element, "Description", "");
      if (element.hasAttribute("ParentNodeId")) {
        node.parentId = element.getAttribute("ParentNodeId");
      }
      if (element.hasAttribute("DataType")) {
        node.dataType = resolveDataType(element.getAttribute("DataType"));
      }
      if (element.hasAttribute("AccessLevel")) {
        node.accessLevel = Integer.parseInt(element.getAttribute("AccessLevel"));
      }
      if (element.hasAttribute("ValueRank")) {
        node.valueRank = Integer.parseInt(element.getAttribute("ValueRank"));
      }
      for (Element references : children(element, "References")) {
        for (Element reference : children(references, "Reference")) {
          String type = reference.getAttribute("ReferenceType");
          String target = reference.getTextContent().trim();
          boolean forward = !"false".equalsIgnoreCase(reference.getAttribute("IsForward"));
          if (!forward && HIERARCHICAL.contains(type) && node.parentId == null) {
            node.parentId = target;
          }
          if (forward && HIERARCHICAL.contains(type)) {
            forwardChildren.computeIfAbsent(node.nodeId, k -> new ArrayList<>()).add(target);
          }
          if (("HasProperty".equals(type) || "i=46".equals(type)) && !forward) {
            node.property = true;
          }
          if (("HasTypeDefinition".equals(type) || "i=40".equals(type)) && "i=68".equals(resolveAlias(target))) {
            node.property = true;
          }
        }
      }
      if (PROPERTY_NAMES.contains(node.browseName)) {
        node.property = true;
      }
      nodes.put(node.nodeId, node);
      elements.put(node.nodeId, element);
    }
    forwardChildren.forEach((parent, list) -> list.forEach(child -> {
      UaNode node = nodes.get(child);
      if (node != null && node.parentId == null) {
        node.parentId = parent;
      }
    }));
    for (UaNode node : nodes.values()) {
      if (!node.property || node.parentId == null || !nodes.containsKey(node.parentId)) {
        continue;
      }
      UaNode owner = nodes.get(node.parentId);
      Element value = firstChild(elements.get(node.nodeId), "Value");
      if (value == null) {
        continue;
      }
      switch (node.browseName) {
        case "EngineeringUnits" -> readUnit(owner, value);
        case "InputArguments" -> owner.inputs = readArguments(value);
        case "OutputArguments" -> owner.outputs = readArguments(value);
        default -> { }
      }
    }
  }

  /** Browse path from the top of the NodeSet, for example Cell/Robot/Temperature. */
  public String path(UaNode node) {
    List<String> names = new ArrayList<>();
    UaNode current = node;
    int guard = 0;
    while (current != null && guard++ < 64) {
      names.add(0, current.browseName);
      current = current.parentId == null ? null : nodes.get(current.parentId);
    }
    return String.join("/", names);
  }

  public String namespaceUri(String nodeId) {
    if (!nodeId.startsWith("ns=")) {
      return "http://opcfoundation.org/UA/";
    }
    int index = Integer.parseInt(nodeId.substring(3, nodeId.indexOf(';')));
    return index >= 1 && index <= namespaceUris.size() ? namespaceUris.get(index - 1) : "";
  }

  /** The NodeId without its namespace index, for example s=Cell/Robot/Temperature. */
  public static String identifier(String nodeId) {
    return nodeId.startsWith("ns=") ? nodeId.substring(nodeId.indexOf(';') + 1) : nodeId;
  }

  /** Reads the unit from EUInformation.UnitId (UNECE code); the display name is only a label. */
  private static void readUnit(UaNode owner, Element value) {
    Element info = descendant(value, "EUInformation");
    Element unitId = info == null ? null : firstChild(info, "UnitId");
    if (unitId == null || unitId.getTextContent().isBlank()) {
      return;
    }
    int id = Integer.parseInt(unitId.getTextContent().trim());
    if (id == -1) {
      return; // -1 means that no unit is set.
    }
    Units.fromUnitId(id).ifPresentOrElse(unit -> owner.unit = unit,
        () -> owner.unitError = "unit code " + Units.uneceCode(id) + " (UnitId " + id + ") is unknown");
  }

  private List<Argument> readArguments(Element value) {
    List<Argument> result = new ArrayList<>();
    NodeList list = value.getElementsByTagNameNS("*", "Argument");
    for (int i = 0; i < list.getLength(); i++) {
      Element element = (Element) list.item(i);
      Argument argument = new Argument();
      argument.name = textOf(element, "Name", "");
      Element dataType = firstChild(element, "DataType");
      if (dataType != null) {
        Element identifier = firstChild(dataType, "Identifier");
        argument.dataType = resolveDataType(
            (identifier != null ? identifier.getTextContent() : dataType.getTextContent()).trim());
      }
      String rank = textOf(element, "ValueRank", "-1");
      argument.valueRank = rank.isEmpty() ? -1 : Integer.parseInt(rank);
      result.add(argument);
    }
    return result;
  }

  private String resolveAlias(String value) {
    return aliases.getOrDefault(value, value);
  }

  private String resolveDataType(String value) {
    String resolved = resolveAlias(value.trim());
    if (resolved.startsWith("ns=0;")) {
      resolved = resolved.substring(5);
    }
    return BUILTIN_TYPES.getOrDefault(resolved, resolved);
  }

  private static String stripNamespacePrefix(String browseName) {
    int colon = browseName.indexOf(':');
    return colon > 0 && browseName.substring(0, colon).chars().allMatch(Character::isDigit)
        ? browseName.substring(colon + 1) : browseName;
  }

  private static String textOf(Element element, String name, String fallback) {
    Element child = firstChild(element, name);
    return child == null ? fallback : child.getTextContent().trim();
  }

  private static List<Element> allChildren(Element element) {
    List<Element> result = new ArrayList<>();
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element e) {
        result.add(e);
      }
    }
    return result;
  }

  private static List<Element> children(Element element, String name) {
    return allChildren(element).stream().filter(e -> name.equals(e.getLocalName())).toList();
  }

  private static Element firstChild(Element element, String name) {
    List<Element> list = children(element, name);
    return list.isEmpty() ? null : list.get(0);
  }

  private static Element descendant(Element element, String name) {
    NodeList list = element.getElementsByTagNameNS("*", name);
    return list.getLength() == 0 ? null : (Element) list.item(0);
  }
}
