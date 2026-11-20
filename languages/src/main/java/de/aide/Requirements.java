package de.aide;

import de.aide.dtbasics._ast.ASTUnitRef;
import de.aide.servicerequirements.ServiceRequirementsMill;
import de.aide.servicerequirements._ast.ASTCDAttribute;
import de.aide.servicerequirements._symboltable.IServiceRequirementsArtifactScope;
import de.aide.watchdogworkflow.WatchdogWorkflowMill;
import de.aide.watchdogworkflow._ast.ASTWorkflow;
import de.aide.watchdogworkflow._ast.ASTWorkflowRequirement;
import de.aide.watchdogworkflow._ast.ASTWorkflowSpec;
import de.monticore.cdbasis._ast.ASTCDClass;
import de.monticore.cdbasis._ast.ASTCDCompilationUnit;
import de.monticore.cdbasis._ast.ASTCDDefinition;
import de.monticore.cdbasis._ast.ASTCDElement;
import de.monticore.cdbasis._ast.ASTCDMember;
import de.monticore.cdbasis._symboltable.CDTypeSymbol;
import de.monticore.types.mcarraytypes._ast.ASTMCArrayType;
import de.monticore.types.mcbasictypes._ast.ASTMCType;
import de.monticore.types.mccollectiontypes._ast.ASTMCGenericType;
import de.se_rwth.commons.logging.Log;

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
 * Parses and checks the service requirements, a CD4A variant, and flattens them into
 * bindable attributes. Classes and attribute types are resolved in the CD4A symbol table.
 * Attributes of a structured type are expanded field by field.
 */
public class Requirements {

  public static final Set<String> PRIMITIVES = Set.of("boolean", "int", "long", "float", "double", "String");
  public static final Set<String> NUMERIC = Set.of("int", "long", "float", "double");

  public static class Attribute {
    public String key;
    public String type;
    public String unit;
    public boolean readOnly;
    /** input, output, structured or unsupported */
    public String kind;
    public String note;
    /** The workflow that requires the attribute, or null for a service attribute. */
    public String workflow;

    public boolean bindable() {
      return "input".equals(kind) || "output".equals(kind);
    }

    public Map<String, Object> toJson() {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("key", key);
      json.put("type", type);
      json.put("unit", unit);
      json.put("access", readOnly ? "read" : "write");
      json.put("kind", kind);
      if (note != null) {
        json.put("note", note);
      }
      if (workflow != null) {
        json.put("workflow", workflow);
      }
      return json;
    }
  }

  public String name;
  public final Map<String, Attribute> attributes = new LinkedHashMap<>();
  public final Diagnostics diagnostics = new Diagnostics();

  public static Requirements parse(String text) throws Exception {
    Requirements requirements = new Requirements();
    ServiceRequirementsMill.init();
    ServiceRequirementsMill.globalScope().clear();
    var parser = ServiceRequirementsMill.parser();
    Optional<ASTCDCompilationUnit> ast = parser.parse_String(text);
    if (parser.hasErrors() || ast.isEmpty()) {
      requirements.diagnostics.takeLogFindings();
      if (requirements.diagnostics.isEmpty()) {
        requirements.diagnostics.error("The service requirements cannot be parsed.", null);
      }
      return requirements;
    }
    IServiceRequirementsArtifactScope scope = ServiceRequirementsMill.scopesGenitorDelegator().createFromAST(ast.get());
    requirements.check(ast.get().getCDDefinition(), scope);
    if (requirements.diagnostics.isEmpty()) {
      requirements.flatten(ast.get().getCDDefinition(), scope);
    }
    return requirements;
  }

  /**
   * Adds the workflow requirements of watchdog workflows as attributes Workflow.name, so that
   * they are bound like service attributes. Invalid workflow text or requirements are skipped
   * here; the workflow check reports them.
   */
  public void addWorkflowRequirements(String workflowText) {
    if (workflowText == null || !diagnostics.isEmpty()) {
      return;
    }
    WatchdogWorkflowMill.init();
    var parser = WatchdogWorkflowMill.parser();
    Optional<ASTWorkflowSpec> ast;
    try {
      ast = parser.parse_String(workflowText);
    } catch (java.io.IOException e) {
      return;
    }
    Log.clearFindings();
    if (parser.hasErrors() || ast.isEmpty()) {
      return;
    }
    for (ASTWorkflow workflow : ast.get().getWorkflowList()) {
      for (ASTWorkflowRequirement requirement : workflow.getWorkflowRequirementList()) {
        String key = workflow.getName() + "." + requirement.getName();
        if (workflowRequirementError(requirement, key) == null && !attributes.containsKey(key)) {
          Attribute attribute = new Attribute();
          attribute.key = key;
          attribute.type = requirement.getType();
          attribute.unit = requirement.isPresentUnitRef() ? Units.symbol(unitText(requirement.getUnitRef())) : null;
          attribute.readOnly = requirement.isPresentReadOnly();
          attribute.kind = attribute.readOnly ? "input" : "output";
          attribute.workflow = workflow.getName();
          attributes.put(key, attribute);
        }
      }
    }
  }

  /** The error of a workflow requirement with the given key, or null if it is valid. */
  String workflowRequirementError(ASTWorkflowRequirement requirement, String key) {
    if (!PRIMITIVES.contains(requirement.getType())) {
      return "Workflow requirement " + key + " needs a primitive type: " + String.join(", ", PRIMITIVES) + ".";
    }
    if (requirement.isPresentUnitRef()) {
      String unit = unitText(requirement.getUnitRef());
      if (Units.parse(unit).isEmpty()) {
        return "Unknown unit " + unit + " of workflow requirement " + key + ".";
      }
      if (!NUMERIC.contains(requirement.getType())) {
        return "Only numeric attributes can have a unit: " + key + ".";
      }
    }
    Attribute existing = attributes.get(key);
    if (existing != null && existing.workflow == null) {
      return "Workflow requirement " + key + " has the name of a service attribute.";
    }
    return null;
  }

  /** The service requirements use only classes with attributes of CD4A. */
  private void check(ASTCDDefinition diagram, IServiceRequirementsArtifactScope scope) {
    name = diagram.getName();
    Set<String> classes = new HashSet<>();
    for (ASTCDElement element : diagram.getCDElementList()) {
      if (!(element instanceof ASTCDClass c)) {
        diagnostics.error("The service requirements contain only classes.", element.get_SourcePositionStart());
        continue;
      }
      if (!classes.add(c.getName())) {
        diagnostics.error("Class " + c.getName() + " is declared twice.", c.get_SourcePositionStart());
      }
      if (c.isPresentCDExtendUsage() || c.isPresentCDInterfaceUsage()) {
        diagnostics.error("Class " + c.getName() + " cannot extend or implement another type.", c.get_SourcePositionStart());
      }
      Set<String> names = new HashSet<>();
      for (ASTCDAttribute a : attributes(c)) {
        String key = c.getName() + "." + a.getName();
        if (!names.add(a.getName())) {
          diagnostics.error("Attribute " + key + " is declared twice.", a.get_SourcePositionStart());
        }
        if (a.isPresentInitial()) {
          diagnostics.error("Attribute " + key + " cannot have an initial value.", a.get_SourcePositionStart());
        }
        String type = typeText(a.getMCType());
        boolean simple = !(a.getMCType() instanceof ASTMCGenericType) && !(a.getMCType() instanceof ASTMCArrayType);
        if (a.isPresentUnitRef()) {
          String unit = unitText(a.getUnitRef());
          if (Units.parse(unit).isEmpty()) {
            diagnostics.error("Unknown unit " + unit + " of attribute " + a.getName() + ".", a.get_SourcePositionStart());
          }
          if (!simple || !NUMERIC.contains(type)) {
            diagnostics.error("Only numeric attributes can have a unit: " + a.getName() + ".", a.get_SourcePositionStart());
          }
        }
        if (simple && !PRIMITIVES.contains(type) && scope.resolveCDType(type).isEmpty()) {
          diagnostics.error("Unknown type " + type + " of attribute " + key + ".", a.get_SourcePositionStart());
        }
      }
    }
  }

  /** Classes that no attribute uses as its type are the roots of the attribute keys. */
  private void flatten(ASTCDDefinition diagram, IServiceRequirementsArtifactScope scope) {
    Set<String> usedAsType = new HashSet<>();
    for (ASTCDClass c : diagram.getCDClassesList()) {
      for (ASTCDAttribute a : attributes(c)) {
        Matcher names = Pattern.compile("\\w+").matcher(typeText(a.getMCType()));
        while (names.find()) {
          usedAsType.add(names.group());
        }
      }
    }
    for (ASTCDClass c : diagram.getCDClassesList()) {
      if (!usedAsType.contains(c.getName())) {
        addFields(c.getName(), c, false, scope, new ArrayList<>());
      }
    }
  }

  private void addFields(String prefix, ASTCDClass owner, boolean readOnly,
      IServiceRequirementsArtifactScope scope, List<String> visiting) {
    visiting.add(owner.getName());
    for (ASTCDAttribute a : attributes(owner)) {
      Attribute attribute = new Attribute();
      attribute.key = prefix + "." + a.getName();
      attribute.type = typeText(a.getMCType());
      attribute.unit = a.isPresentUnitRef() ? Units.symbol(unitText(a.getUnitRef())) : null;
      attribute.readOnly = readOnly || a.isPresentReadOnly();
      Optional<CDTypeSymbol> type = scope.resolveCDType(attribute.type);
      if (a.getMCType() instanceof ASTMCGenericType || a.getMCType() instanceof ASTMCArrayType) {
        attribute.kind = "unsupported";
        attribute.note = "collections such as " + attribute.type + " are not supported";
      } else if (type.isPresent() && type.get().getAstNode() instanceof ASTCDClass typeClass) {
        attribute.kind = "structured";
        attribute.note = "bound field by field";
        if (visiting.contains(typeClass.getName())) {
          diagnostics.error("Structured type " + typeClass.getName() + " contains itself.", a.get_SourcePositionStart());
          continue;
        }
        attributes.put(attribute.key, attribute);
        addFields(attribute.key, typeClass, attribute.readOnly, scope, visiting);
        continue;
      } else {
        attribute.kind = attribute.readOnly ? "input" : "output";
      }
      attributes.put(attribute.key, attribute);
    }
    visiting.remove(visiting.size() - 1);
  }

  /** The attributes of a class; the parser of this CD4A variant creates only its own attribute nodes. */
  private static List<ASTCDAttribute> attributes(ASTCDClass c) {
    List<ASTCDAttribute> result = new ArrayList<>();
    for (ASTCDMember member : c.getCDMemberList()) {
      result.add((ASTCDAttribute) member);
    }
    return result;
  }

  static String unitText(ASTUnitRef unit) {
    return unit.isPresentName() ? unit.getName() : unit.getUnitLiteral();
  }

  private static String typeText(ASTMCType type) {
    return ServiceRequirementsMill.prettyPrint(type, false).replaceAll("\\s+", "");
  }
}

