package org.example.aichat.release;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Removes known emotion markers across token boundaries while preserving narrative text. */
public final class EmotionStream {
  private record Marker(String text, String emotion) {}
  private static final Set<String> TAGS = Set.of("平静", "倾听", "思考", "开心", "生气", "难过", "惊讶", "害羞", "俏皮");
  private static final List<Marker> MARKERS = TAGS.stream()
      .flatMap(tag -> Stream.of(new Marker("<" + tag + ">", tag), new Marker("</" + tag + ">", null)))
      .toList();
  private final StringBuilder pending = new StringBuilder();
  private final Consumer<String> emotion;

  public EmotionStream(Consumer<String> emotion) { this.emotion = emotion; }

  public String append(String text) {
    pending.append(text);
    var visible = new StringBuilder();
    while (!pending.isEmpty()) {
      int start = pending.indexOf("<");
      if (start < 0) {
        visible.append(pending);
        pending.setLength(0);
        break;
      }
      visible.append(pending, 0, start);
      pending.delete(0, start);
      String candidate = pending.toString();
      Marker matched = null;
      boolean partial = false;
      for (var marker : MARKERS) {
        if (candidate.startsWith(marker.text())) { matched = marker; break; }
        if (marker.text().startsWith(candidate)) partial = true;
      }
      if (matched != null) {
        // Some models pair emotion markers like XML. A closing marker should
        // disappear without replaying the emotion after the spoken text.
        if (matched.emotion() != null) emotion.accept(matched.emotion());
        pending.delete(0, matched.text().length());
      } else if (partial) {
        break;
      } else {
        // Do not wait for an arbitrary '>'. That could swallow a later emotion
        // marker after ordinary text such as "1 < 2 <开心>".
        visible.append('<');
        pending.deleteCharAt(0);
      }
    }
    return visible.toString();
  }

  public String finish() {
    String value = pending.toString();
    pending.setLength(0);
    // Hide a truncated known emotion name, but keep literal comparison symbols.
    if (!value.equals("<") && !value.equals("</")
        && MARKERS.stream().anyMatch(marker -> marker.text().startsWith(value))) return "";
    return value;
  }
}
