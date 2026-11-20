package de.aide;

import de.aide.dashboard.DashboardMill;
import de.aide.dashboard._ast.ASTAlarm;
import de.aide.dashboard._ast.ASTButton;
import de.aide.dashboard._ast.ASTCard;
import de.aide.dashboard._ast.ASTDashboardSpec;
import de.aide.dashboard._ast.ASTGauge;
import de.aide.dashboard._ast.ASTHide;
import de.aide.dashboard._ast.ASTHistory;
import de.aide.dashboard._ast.ASTInput;
import de.aide.dashboard._ast.ASTItem;
import de.aide.dashboard._ast.ASTTile;
import de.aide.dashboard._ast.ASTToggle;
import de.aide.dashboard._ast.ASTView;
import de.aide.dashboard._ast.ASTVisibility;
import de.aide.dtbasics._ast.ASTAttributeRef;
import de.monticore.literals.mccommonliterals._ast.ASTBooleanLiteral;
import de.monticore.literals.mccommonliterals._ast.ASTSignedBasicDoubleLiteral;
import de.monticore.literals.mccommonliterals._ast.ASTSignedLiteral;
import de.monticore.literals.mccommonliterals._ast.ASTSignedNatLiteral;
import de.monticore.literals.mccommonliterals._ast.ASTStringLiteral;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parses a dashboard and checks it with context conditions: every card shows a
 * bound value and fits its type and access mode, every control that calls a method
 * asks for confirmation, a control is shown only under a condition on a readable
 * value, every alarm refers to a watchdog workflow, gauge ranges are valid, and
 * every bound attribute is shown or hidden.
 * The result is a dashboard model from which the back end writes the Home
 * Assistant cards.
 */
public class DashboardCheck {

  public final Diagnostics diagnostics = new Diagnostics();
  public final List<Map<String, Object>> views = new ArrayList<>();

  private final BindingCheck binding;
  private final Set<String> workflows;
  private final Set<String> shown = new HashSet<>();

  private DashboardCheck(BindingCheck binding, Set<String> workflows) {
    this.binding = binding;
    this.workflows = workflows;
  }

  public static DashboardCheck run(BindingCheck binding, Set<String> workflows, String text) throws Exception {
    DashboardCheck check = new DashboardCheck(binding, workflows);
    DashboardMill.init();
    var parser = DashboardMill.parser();
    Optional<ASTDashboardSpec> ast = parser.parse_String(text);
    if (parser.hasErrors() || ast.isEmpty()) {
      check.diagnostics.takeLogFindings();
      if (check.diagnostics.isEmpty()) {
        check.diagnostics.error("The dashboard cannot be parsed.", null);
      }
      return check;
    }
    check.check(ast.get());
    return check;
  }

  private void check(ASTDashboardSpec spec) {
    if (binding.name != null && !spec.getBinding().equals(binding.name)) {
      diagnostics.error("The dashboard uses " + spec.getBinding() + ", but the binding is " + binding.name + ".",
          spec.get_SourcePositionStart());
    }
    for (ASTItem item : spec.getItemList()) {
      if (item instanceof ASTView view) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (ASTCard card : view.getCardList()) {
          Map<String, Object> json = card(card);
          if (json != null) {
            cards.add(json);
          }
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("title", view.getTitle().getValue());
        json.put("cards", cards);
        views.add(json);
      } else if (item instanceof ASTHide hide) {
        hide.getAttributeRefList().forEach(this::resolve);
      }
    }
    for (String key : binding.resolved.keySet()) {
      if (!shown.contains(key)) {
        diagnostics.error(key + " is bound but neither shown nor hidden; add a card or hide " + key + ";.",
            spec.get_SourcePositionStart());
      }
    }
  }

  /** Checks one card and returns its model, or null for hide. */
  private Map<String, Object> card(ASTCard card) {
    Map<String, Object> json = new LinkedHashMap<>();
    switch (card) {
      case ASTGauge c -> {
        json.put("card", "gauge");
        String key = numeric(c.getAttributeRef(), "gauge");
        Double min = number(c.getMin());
        Double max = number(c.getMax());
        if (min != null && max != null && min >= max) {
          diagnostics.error("The gauge minimum must be less than its maximum.", c.get_SourcePositionStart());
        }
        json.put("attributes", key == null ? List.of() : List.of(key));
        json.put("min", min);
        json.put("max", max);
        if (c.isPresentWarning()) {
          Double warning = number(c.getWarning());
          Double critical = number(c.getCritical());
          for (Double value : new Double[] {warning, critical}) {
            if (value != null && min != null && max != null && (value < min || value > max)) {
              diagnostics.error("Severity value " + format(value) + " is outside the gauge range.",
                  c.get_SourcePositionStart());
            }
          }
          json.put("severity", List.of(warning == null ? 0 : warning, critical == null ? 0 : critical));
        }
        json.put("unit", key == null ? null : binding.resolved.get(key).get("unit"));
      }
      case ASTHistory c -> {
        json.put("card", "history");
        List<String> keys = new ArrayList<>();
        c.getAttributeRefList().forEach(ref -> {
          String key = numeric(ref, "history");
          if (key != null) {
            keys.add(key);
          }
        });
        json.put("attributes", keys);
        int seconds = c.getPeriod().getValue() * switch (c.getTimeUnit().name()) {
          case "H" -> 3600;
          case "MIN" -> 60;
          default -> 1;
        };
        if (seconds <= 0) {
          diagnostics.error("The history period must be greater than zero.", c.get_SourcePositionStart());
        }
        json.put("hours", seconds / 3600.0);
      }
      case ASTTile c -> {
        json.put("card", "tile");
        String key = resolve(c.getAttributeRef());
        json.put("attributes", key == null ? List.of() : List.of(key));
      }
      case ASTToggle c -> {
        json.put("card", "toggle");
        String key = resolve(c.getAttributeRef());
        Map<String, Object> entry = key == null ? null : binding.resolved.get(key);
        if (entry != null && !("write".equals(entry.get("access")) && "boolean".equals(entry.get("type"))
            && "Variable".equals(entry.get("nodeClass")))) {
          diagnostics.error("A toggle needs a writable Boolean attribute bound to a variable; " + key + " is not.",
              c.get_SourcePositionStart());
        }
        json.put("attributes", key == null ? List.of() : List.of(key));
      }
      case ASTButton c -> {
        json.put("card", "button");
        String key = resolve(c.getAttributeRef());
        Map<String, Object> entry = key == null ? null : binding.resolved.get(key);
        if (entry != null && !("write".equals(entry.get("access")) && "boolean".equals(entry.get("type"))
            && "Method".equals(entry.get("nodeClass")))) {
          diagnostics.error("A button needs a writable Boolean attribute bound via a method; " + key + " is not.",
              c.get_SourcePositionStart());
        }
        if (!c.isPresentQuestion()) {
          diagnostics.error("A button calls a method on the machine, so it needs confirm \"<question>\".",
              c.get_SourcePositionStart());
        }
        json.put("attributes", key == null ? List.of() : List.of(key));
        json.put("confirm", c.isPresentQuestion() ? c.getQuestion().getValue() : null);
        if (c.isPresentVisibility()) {
          json.put("visible", visibility(c.getVisibility()));
        }
      }
      case ASTInput c -> {
        json.put("card", "input");
        String key = resolve(c.getAttributeRef());
        Map<String, Object> entry = key == null ? null : binding.resolved.get(key);
        boolean method = entry != null && "Method".equals(entry.get("nodeClass"));
        if (entry != null && !("write".equals(entry.get("access")) && !"boolean".equals(entry.get("type")))) {
          diagnostics.error("An input needs a writable attribute that is not Boolean; " + key + " is not.",
              c.get_SourcePositionStart());
        }
        if (method && !c.isPresentQuestion()) {
          diagnostics.error("The input calls a method on the machine, so it needs confirm \"<question>\".",
              c.get_SourcePositionStart());
        }
        if (c.isPresentMin()) {
          Double min = number(c.getMin());
          Double max = number(c.getMax());
          if (entry != null && "String".equals(entry.get("type"))) {
            diagnostics.error("An input range needs a numeric attribute; " + key + " is not.",
                c.get_SourcePositionStart());
          } else if (min != null && max != null && min >= max) {
            diagnostics.error("The input minimum must be less than its maximum.", c.get_SourcePositionStart());
          }
          json.put("min", min);
          json.put("max", max);
        }
        json.put("attributes", key == null ? List.of() : List.of(key));
        json.put("method", method);
        json.put("confirm", c.isPresentQuestion() ? c.getQuestion().getValue() : null);
        if (c.isPresentVisibility()) {
          json.put("visible", visibility(c.getVisibility()));
        }
      }
      case ASTAlarm c -> {
        json.put("card", "alarm");
        if (!workflows.contains(c.getWorkflow())) {
          diagnostics.error("Alarm " + c.getWorkflow() + " does not refer to a watchdog workflow.",
              c.get_SourcePositionStart());
        }
        json.put("workflow", c.getWorkflow());
        json.put("processId", BpmnGenerator.processId(c.getWorkflow()));
      }
      case ASTHide c -> {
        c.getAttributeRefList().forEach(this::resolve);
        return null;
      }
      default -> throw new IllegalArgumentException("Unknown card");
    }
    return json;
  }

  /** The condition under which a control is shown: a readable bound value equals a literal of its type. */
  private Map<String, Object> visibility(ASTVisibility visibility) {
    String key = find(visibility.getAttributeRef());
    Map<String, Object> json = new LinkedHashMap<>();
    if (key == null) {
      return json;
    }
    Map<String, Object> entry = binding.resolved.get(key);
    ASTSignedLiteral value = visibility.getValue();
    boolean fits = switch ((String) entry.get("type")) {
      case "boolean" -> value instanceof ASTBooleanLiteral;
      case "String" -> value instanceof ASTStringLiteral;
      default -> value instanceof ASTSignedNatLiteral || value instanceof ASTSignedBasicDoubleLiteral;
    };
    if (!Boolean.TRUE.equals(entry.get("readable"))) {
      diagnostics.error("A control can only depend on a readable value; " + key + " is not readable.",
          visibility.get_SourcePositionStart());
    } else if (!fits) {
      diagnostics.error("The value does not fit " + key + " of type " + entry.get("type") + ".",
          visibility.get_SourcePositionStart());
    } else {
      json.put("attribute", key);
      json.put("value", WorkflowCheck.literal(value));
    }
    return json;
  }

  /** Resolves a bound attribute that the card shows. */
  private String resolve(ASTAttributeRef ref) {
    String key = find(ref);
    if (key != null) {
      shown.add(key);
    }
    return key;
  }

  /** Finds a bound attribute by its qualified name or, if unique, by its attribute name alone. */
  private String find(ASTAttributeRef ref) {
    String text = String.join(".", ref.getNameList());
    List<String> matches = binding.resolved.keySet().stream()
        .filter(k -> k.equals(text) || k.endsWith("." + text)).toList();
    String key = matches.contains(text) ? text : matches.size() == 1 ? matches.get(0) : null;
    List<String> unbound = binding.unbound.keySet().stream()
        .filter(k -> k.equals(text) || k.endsWith("." + text)).toList();
    if (key == null && matches.isEmpty() && unbound.size() == 1) {
      String reason = binding.unbound.get(unbound.get(0));
      diagnostics.error(unbound.get(0) + " is marked as unbound" + (reason.isEmpty() ? "" : " (" + reason + ")")
          + ": no node provides it, so no card can show it.", ref.get_SourcePositionStart());
    } else if (key == null) {
      diagnostics.error(matches.isEmpty() ? text + " is not a bound attribute."
          : text + " is ambiguous; use one of " + matches + ".", ref.get_SourcePositionStart());
    }
    return key;
  }

  private String numeric(ASTAttributeRef ref, String card) {
    String key = resolve(ref);
    if (key != null && !Requirements.NUMERIC.contains((String) binding.resolved.get(key).get("type"))) {
      diagnostics.error("A " + card + " needs a numeric attribute; " + key + " is not numeric.",
          ref.get_SourcePositionStart());
      return null;
    }
    return key;
  }

  private Double number(ASTSignedLiteral literal) {
    if (literal instanceof ASTSignedNatLiteral n) {
      return (double) n.getValue();
    }
    if (literal instanceof ASTSignedBasicDoubleLiteral d) {
      return d.getValue();
    }
    diagnostics.error("A number is expected.", literal.get_SourcePositionStart());
    return null;
  }

  private static String format(double value) {
    return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
  }
}
