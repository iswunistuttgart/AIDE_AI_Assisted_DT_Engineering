package de.aide;

import de.se_rwth.commons.SourcePosition;
import de.se_rwth.commons.logging.Finding;
import de.se_rwth.commons.logging.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Error messages of one check, each with an optional source line. */
public class Diagnostics {

  public final List<Map<String, Object>> errors = new ArrayList<>();

  public void error(String message, SourcePosition position) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("message", message);
    if (position != null && position.getLine() > 0) {
      error.put("line", position.getLine());
    }
    if (!errors.contains(error)) { // The parser can report the same error more than once.
      errors.add(error);
    }
  }

  public boolean isEmpty() {
    return errors.isEmpty();
  }

  /** Moves MontiCore log findings (for example parse errors) into this collection. */
  public void takeLogFindings() {
    for (Finding finding : Log.getFindings()) {
      if (finding.isError()) {
        error(finding.getMsg(), finding.getSourcePosition().orElse(null));
      }
    }
    Log.clearFindings();
  }
}
