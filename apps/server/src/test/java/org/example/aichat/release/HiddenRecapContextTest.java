package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.example.aichat.rp.RpState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Exercises the release HTTP path and captures the messages actually passed to the model. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HiddenRecapContextTest {
  static final String TOKEN = "hidden-recap-test-local-only-1234567890";
  static final Path DATA;
  static {
    try { DATA = Files.createTempDirectory("hidden-recap-context-"); }
    catch (Exception e) { throw new RuntimeException(e); }
  }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("story.mode", () -> "offline");
    r.add("story.data-dir", () -> DATA.toString());
    r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("test.sqlite") + "?busy_timeout=10000&journal_mode=WAL");
    r.add("story.local-token", () -> TOKEN);
  }

  @Autowired TestRestTemplate client;
  @Autowired StoryStore stories;
  @Autowired ObjectMapper json;
  @MockitoBean PublicContent content;
  @MockitoBean OpenAiTransport models;
  @MockitoBean MemoryIndex memory;
  @MockitoBean InterestInference inference;
  @MockitoBean ProactiveService proactive;
  final List<List<Map<String, Object>>> prompts = new CopyOnWriteArrayList<>();

  @BeforeEach void setup() throws Exception {
    when(memory.recall(anyString(), anyString())).thenReturn("");
    when(models.stream(any(), anyList(), any(), any())).thenAnswer(call -> {
      prompts.add(new ArrayList<>(call.getArgument(1)));
      ((java.util.function.Consumer<String>) call.getArgument(3)).accept("这是测试回复。");
      return new OpenAiTransport.Usage(3, 4, false);
    });
  }

  int role() { return stories.db().queryForObject("SELECT MIN(id) FROM role_card WHERE owner_id IS NULL", Integer.class); }
  String chapter(int user, String world) {
    String id = "hidden_recap_" + UUID.randomUUID();
    var state = new RpState(); state.location = "用户手工填写的松溪书屋";
    stories.create(user, id, role(), world, state);
    return id;
  }
  long turn(String chapter, String user, String assistant) {
    stories.message(chapter, "user", user);
    long id = stories.message(chapter, "assistant", assistant);
    stories.finish(chapter, id, user, assistant, "complete", List.of(), "");
    return id;
  }
  ResponseEntity<String> request(String path, HttpMethod method, Object body) {
    var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.setBearerAuth(TOKEN);
    return client.exchange(path, method, new HttpEntity<>(body, headers), String.class);
  }
  void feedback(String chapter, long id, boolean hidden, String correction) {
    var response = request("/api/memory/" + id, HttpMethod.PUT,
        Map.of("conversationId", chapter, "hidden", hidden, "correction", correction));
    assertTrue(response.getStatusCode().is2xxSuccessful(), response.getBody());
  }
  List<Map<String, Object>> ask(String chapter) {
    int before = prompts.size();
    var response = request("/api/chat", HttpMethod.POST,
        Map.of("conversationId", chapter, "roleId", role(), "message", "接下来我们做什么？"));
    assertEquals(200, response.getStatusCode().value(), response.getBody());
    assertTrue(response.getBody().contains("event:done"), response.getBody());
    assertEquals(before + 1, prompts.size());
    return prompts.get(before);
  }

  @Test void hiddenOldChapterTurnsDoNotEnterTheNewChapterPromptButOriginalHistoryRemains() throws Exception {
    String old = chapter(1, null);
    long original = turn(old, "我喜欢曜翎蜜茶8317。", "你喜欢曜翎蜜茶8317。");
    long changed = turn(old, "后来喜欢辰雾乌龙9264。", "记得辰雾乌龙9264。");
    turn(old, "周五一起修理蓝色钟7281。", "蓝色钟7281的约定还在。");
    feedback(old, original, true, "");
    feedback(old, changed, true, "现在喜欢星辉白茶6402。");
    String world = stories.sessions().worldId(old);
    String stateBefore = stories.sessions().encode(stories.sessions().state(old));
    String next = chapter(1, world);

    var messages = ask(next);
    String system = messages.get(0).get("content").toString();
    for (String hidden : List.of("曜翎蜜茶8317", "辰雾乌龙9264", "星辉白茶6402"))
      assertFalse(messages.toString().contains(hidden), hidden + " leaked into new chapter prompt");
    assertTrue(system.contains("蓝色钟7281"), system);
    assertTrue(system.contains("用户手工填写的松溪书屋"), system);
    assertEquals(stateBefore, stories.sessions().encode(stories.sessions().state(next)));

    var response = request("/api/conversation/" + old + "/history", HttpMethod.GET, null);
    assertEquals(200, response.getStatusCode().value());
    var history = json.readTree(response.getBody());
    assertEquals(6, history.size());
    assertTrue(history.toString().contains("曜翎蜜茶8317"));
    assertTrue(history.toString().contains("辰雾乌龙9264"));
    var memories = json.readTree(request("/api/memory?conversationId=" + next, HttpMethod.GET, null).getBody());
    for (long id : List.of(original, changed)) {
      JsonNode row = null;
      for (var candidate : memories) if (candidate.path("history_id").asLong() == id) row = candidate;
      assertNotNull(row); assertTrue(row.path("hidden").asBoolean());
    }
    String capture = System.getProperty("memoryrecap.capture");
    if (capture != null) json.writerWithDefaultPrettyPrinter().writeValue(Path.of(capture).toFile(), Map.of(
        "kind", "Actual ChatApi final prompt assembly; generation was mocked, not a model-forgetting claim",
        "messages", messages, "originalHistory", history));
  }

  @Test void hidingDoesNotRemoveTheCurrentChaptersRawConversation() {
    String current = chapter(1, null);
    long hidden = turn(current, "桌上的银杏钥匙5623是我的。", "你的银杏钥匙5623在桌上。");
    feedback(current, hidden, true, "");
    var messages = ask(current);
    assertTrue(messages.stream().anyMatch(m -> "user".equals(m.get("role")) && m.get("content").toString().contains("银杏钥匙5623")));
    assertTrue(messages.stream().anyMatch(m -> "assistant".equals(m.get("role")) && m.get("content").toString().contains("银杏钥匙5623")));
    assertFalse(messages.get(0).get("content").toString().contains("银杏钥匙5623"));
  }

  @Test void showingMemoryAgainRestoresOnlyTheEligibleWorldRecap() {
    String source = chapter(1, null);
    long id = turn(source, "我们养的纸鸢叫月芽4139。", "月芽4139是纸鸢的名字。");
    feedback(source, id, true, "");
    String next = chapter(1, stories.sessions().worldId(source));
    assertFalse(ask(next).toString().contains("月芽4139"));
    feedback(source, id, false, "");
    assertTrue(ask(next).get(0).get("content").toString().contains("月芽4139"));
    // The shared RP package stays independent of release memory feedback.
    assertEquals(stories.sessions().recentEvents(source), stories.sessions().recentEvents(source, Set.of()));
    assertFalse(stories.sessions().recentEvents(source, Set.of(id)).contains("月芽4139"));
  }

  @Test void otherWorldsAndOwnersAreNotIncludedOrEditableThroughTheCurrentAccount() {
    String mine = chapter(1, null), otherWorld = chapter(1, null), otherOwner = chapter(2, null);
    turn(mine, "我们的风铃名为北辰3187。", "北辰3187挂在窗边。");
    long otherWorldId = turn(otherWorld, "别的世界有紫砂石8521。", "紫砂石8521留在那里。");
    long otherOwnerId = turn(otherOwner, "另一个人的私信是云阶9765。", "云阶9765是他的私信。");
    feedback(otherWorld, otherWorldId, true, "");
    String next = chapter(1, stories.sessions().worldId(mine));
    String prompt = ask(next).toString();
    assertTrue(prompt.contains("北辰3187"));
    assertFalse(prompt.contains("紫砂石8521")); assertFalse(prompt.contains("云阶9765"));
    assertEquals(404, request("/api/conversation/" + otherOwner + "/history", HttpMethod.GET, null).getStatusCode().value());
    assertEquals(404, request("/api/memory?conversationId=" + otherOwner, HttpMethod.GET, null).getStatusCode().value());
    assertEquals(404, request("/api/memory/" + otherOwnerId, HttpMethod.PUT,
        Map.of("conversationId", mine, "hidden", true)).getStatusCode().value());
    assertEquals(404, request("/api/memory/" + otherWorldId, HttpMethod.PUT,
        Map.of("conversationId", mine, "hidden", false)).getStatusCode().value());
    assertEquals(400, request("/api/rp/sessions", HttpMethod.POST, Map.of(
        "conversationId", "forbidden_" + UUID.randomUUID(), "roleId", role(),
        "continueWorld", stories.sessions().worldId(otherOwner), "state", new RpState())).getStatusCode().value());
    verify(memory).recall(eq(stories.sessions().worldId(mine)), anyString());
  }
}
