package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmotionStreamTest {
  @Test void removesPairedEmotionMarkersAtEveryTokenBoundary() {
    String input = "阿晴，<开心>桂花茶我记住了。</开心>雨天可以读《雨天的书》。";
    String expected = "阿晴，桂花茶我记住了。雨天可以读《雨天的书》。";
    for (int split = 0; split <= input.length(); split++) {
      var events = new ArrayList<String>();
      var stream = new EmotionStream(events::add);
      String visible = stream.append(input.substring(0, split))
          + stream.append(input.substring(split)) + stream.finish();
      assertEquals(expected, visible, "split at " + split);
      assertEquals(List.of("开心"), events, "a closing tag must not replay the emotion");
    }
  }

  @Test void handlesEveryEmotionWhenTokensAreSingleCharacters() {
    var tags = List.of("平静", "倾听", "思考", "开心", "生气", "难过", "惊讶", "害羞", "俏皮");
    var events = new ArrayList<String>();
    var stream = new EmotionStream(events::add);
    var visible = new StringBuilder();
    for (String tag : tags) {
      for (char c : ("<" + tag + ">好</" + tag + ">").toCharArray())
        visible.append(stream.append(String.valueOf(c)));
    }
    visible.append(stream.finish());
    assertEquals("好".repeat(tags.size()), visible.toString());
    assertEquals(tags, events);
  }

  @Test void preservesMathematicsHtmlAndUnknownTagsWithoutMaskingLaterEmotions() {
    String input = "1 < 2 <开心>，<b>书名</b>，<开篇>故事</开篇>，<custom>注释</custom>。";
    String expected = input.replace("<开心>", "");
    for (int split = 0; split <= input.length(); split++) {
      var events = new ArrayList<String>();
      var stream = new EmotionStream(events::add);
      assertEquals(expected, stream.append(input.substring(0, split))
          + stream.append(input.substring(split)) + stream.finish(), "split at " + split);
      assertEquals(List.of("开心"), events);
    }
  }

  @Test void hidesTruncatedKnownNamesButKeepsOrdinaryUnfinishedText() {
    for (String partial : List.of("<开", "<开心", "</开", "</开心")) {
      var stream = new EmotionStream(ignored -> {});
      assertEquals("你好", stream.append("你好" + partial) + stream.finish(), partial);
      assertEquals("", stream.finish());
      assertEquals("下一句", stream.append("下一句"));
    }
    for (String literal : List.of("<", "</", "<开篇", "<b", "1 < 2")) {
      var stream = new EmotionStream(ignored -> {});
      assertEquals(literal, stream.append(literal) + stream.finish(), literal);
    }
  }
}
