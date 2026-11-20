package org.example.aichat.rp;

/** Removes private state across arbitrary token boundaries. Narrative remains visible. */
public final class RpOutputParser {
  private static final String OPEN = "<scene>";
  private static final String CLOSE = "</scene>";
  private final StringBuilder pending = new StringBuilder();
  private final StringBuilder state = new StringBuilder();
  private boolean inside;
  private boolean overflow;
  private int blocks;

  public String append(String token) {
    pending.append(token);
    StringBuilder visible = new StringBuilder();
    while (!pending.isEmpty()) {
      String marker = inside ? CLOSE : OPEN;
      int match = pending.indexOf(marker);
      if (match >= 0) {
        if (inside) appendState(pending.substring(0, match));
        else {
          visible.append(pending.substring(0, match));
          blocks++;
        }
        pending.delete(0, match + marker.length());
        inside = !inside;
        continue;
      }
      int keep = 0;
      for (int i = 1; i < marker.length() && i <= pending.length(); i++)
        if (marker.startsWith(pending.substring(pending.length() - i))) keep = i;
      int length = pending.length() - keep;
      if (inside) {
        appendState(pending.substring(0, length));
      } else visible.append(pending.substring(0, length));
      pending.delete(0, length);
      break;
    }
    return visible.toString();
  }

  public String finish() {
    String rest = inside || OPEN.startsWith(pending.toString()) ? "" : pending.toString();
    pending.setLength(0);
    return rest;
  }

  private void appendState(String value) {
    int available = Math.max(0, 16000 - state.length());
    if (value.length() > available) overflow = true;
    state.append(value, 0, Math.min(available, value.length()));
  }

  public String stateJson() {
    if (inside || overflow || blocks != 1) return "";
    return state.toString();
  }
}
