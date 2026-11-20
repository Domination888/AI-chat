package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.example.aichat.rp.RpState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CurrentScenePromptIntegrationTest {
  static final String TOKEN="scene-prompt-test-local-token-1234567890";
  static final Path DATA;
  static {try{DATA=Files.createTempDirectory("story-current-scene-test-");}catch(Exception e){throw new RuntimeException(e);}}
  @DynamicPropertySource static void props(DynamicPropertyRegistry r){
    r.add("story.mode",()->"offline");r.add("story.data-dir",()->DATA.toString());
    r.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("test.sqlite")+"?busy_timeout=10000&journal_mode=WAL");r.add("story.local-token",()->TOKEN);
  }
  @Autowired TestRestTemplate client;@Autowired StoryStore stories;@Autowired ObjectMapper json;
  @MockitoBean OpenAiTransport models;@MockitoBean MemoryIndex memory;@MockitoBean PublicContent content;
  @Test void nextTurnReceivesNewSavedSceneAfterLongOldStateWithoutChangingTheSavedFacts()throws Exception {
    var prompts=new ArrayList<List<Map<String,Object>>>();
    when(memory.recall(anyString(),anyString())).thenReturn("旧回复里提到旧书架。");
    when(models.stream(any(),anyList(),any(),any())).thenAnswer(call->{prompts.add(new ArrayList<>(call.getArgument(1)));((java.util.function.Consumer<String>)call.getArgument(3)).accept("我们聊聊现在的计划。");return new OpenAiTransport.Usage(1,1,false);});
    int role=stories.db().queryForObject("SELECT MIN(id) FROM role_card",Integer.class);
    String conversation="scene_prompt_"+UUID.randomUUID();var state=new RpState();
    state.location="柳堤茶亭";state.commitments=List.of("周日一起修好旧望远镜");
    // Long fields before commitments used to crowd out trailing state in raw JSON.
    state.userPersona="背景".repeat(100);state.participants="同行者".repeat(100);state.objects="旧书".repeat(100);
    stories.create(1,conversation,role,null,state);
    send(conversation,role,"我们先喝杯茶，聊聊刚才约好的事吧。简短一句。");
    String first=prompts.get(0).get(0).get("content").toString();
    assertTrue(first.contains("当前地点：柳堤茶亭"));assertTrue(first.contains("已保存约定：周日一起修好旧望远镜"));
    var next=stories.sessions().state(conversation);next.location="松林石桥";next.commitments=List.of("明晚整理星图");stories.sessions().editScene(conversation,next);
    send(conversation,role,"我们走到这里了，聊聊新的约定吧。简短一句。");
    String second=prompts.get(1).get(0).get("content").toString();
    assertTrue(second.contains("当前地点：松林石桥"));assertTrue(second.contains("已保存约定：明晚整理星图"));
    assertFalse(second.contains("已保存约定：周日一起修好旧望远镜"));
    assertTrue(second.contains("用户本轮的新设定或明确更正仍优先"));
    assertTrue(PromptBudget.tokens(second)<=2400);
    assertEquals("松林石桥",stories.sessions().state(conversation).location);
    assertEquals(List.of("明晚整理星图"),stories.sessions().state(conversation).commitments);
    send(conversation,role,"请写一个旅行故事，分三章。");
    String creative=prompts.get(2).get(0).get("content").toString();
    assertTrue(creative.contains("可以创造虚构人物和情节"));
    assertTrue(creative.contains("不把角色背景、当前日期或想象当共同经历"));
    assertTrue(creative.contains("【本轮创作任务】"));assertTrue(creative.contains("按要求的结构直接开始作品"));
    assertTrue(PromptBudget.tokens(creative)<=2400);
    verifyNoInteractions(content);
  }
  private void send(String conversation,int role,String message){
    var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(TOKEN);
    var response=client.postForEntity("/api/chat",new HttpEntity<>(Map.of("conversationId",conversation,"roleId",role,"message",message),headers),String.class);
    assertTrue(response.getStatusCode().is2xxSuccessful(),response.getBody());assertTrue(response.getBody().contains("event:done"),response.getBody());
  }
}
