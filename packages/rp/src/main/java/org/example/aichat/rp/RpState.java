package org.example.aichat.rp;

import java.util.*;

/** Explicit, inspectable state; real-world identity is never inferred from RP dialogue. */
public class RpState {
  public String mode = "story";
  public String style = "story";
  public String userPersona = "";
  public String location = "";
  public String storyTime = "";
  public String participants = "";
  public String outfit = "";
  public String objects = "";
  public String action = "";
  public String relationship = "已是恋人，彼此熟悉；保留各自的判断与生活";
  public String mood = "平静";
  public String characterGoal = "";
  public String sharedActivity = "";
  public String directorNote = "";
  public boolean paused = false;
  public boolean legacyMemory = false;
  public List<String> events = new ArrayList<>();
  public List<String> commitments = new ArrayList<>();

  /** Nulls from old/manual JSON cannot crash prompt assembly or scene editing. */
  public RpState normalize() {
    for (var field : RpState.class.getFields()) {
      try {
        if (field.getType() == String.class) {
          String value = (String) field.get(this);
          field.set(this, value == null ? "" : value.substring(0, Math.min(1200, value.length())));
        }
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
    events = clean(events);
    commitments = clean(commitments);
    if (!Set.of("story", "companion").contains(mode)) mode = "story";
    if (!Set.of("story", "chat").contains(style)) style = "story";
    return this;
  }

  private static List<String> clean(List<String> values) {
    return values == null
        ? new ArrayList<>()
        : new ArrayList<>(
            values.stream()
                .filter(Objects::nonNull)
                .filter(v -> !v.isBlank())
                .map(v -> v.substring(0, Math.min(500, v.length())))
                .limit(24)
                .toList());
  }

  public void apply(Map<String, Object> patch) {
    for (var field : RpState.class.getFields()) {
      Object value = patch.get(field.getName());
      if (value == null
          || Set.of("legacyMemory", "directorNote", "userPersona", "mode", "style", "paused")
              .contains(field.getName())) continue;
      try {
        if (field.getType() == String.class && value instanceof String s)
          field.set(this, s.substring(0, Math.min(s.length(), 1200)));
        if (field.getType() == List.class && value instanceof List<?> list) {
          List<String> clean =
              list.stream()
                  .filter(String.class::isInstance)
                  .map(String.class::cast)
                  .filter(s -> !s.isBlank())
                  .map(s -> s.substring(0, Math.min(500, s.length())))
                  .limit(24)
                  .toList();
          field.set(this, new ArrayList<>(clean));
        }
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
