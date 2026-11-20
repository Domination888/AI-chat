package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.aichat.rp.RpState;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.*;
import java.util.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class MissingPastChatTest {
  static final Path DATA;static final String TOKEN="missing-past-test-only-token-0123456789";
  static{try{DATA=Files.createTempDirectory("missing-past-chat-");}catch(Exception e){throw new RuntimeException(e);}}
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("story.mode",()->"offline");r.add("story.data-dir",()->DATA.toString());r.add("story.local-token",()->TOKEN);r.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("test.sqlite")+"?busy_timeout=10000&journal_mode=WAL");}
  @Autowired TestRestTemplate client;@Autowired StoryStore stories;@Autowired MemoryIndex memory;@Autowired ObjectMapper json;
  @MockitoBean OpenAiTransport models;@MockitoBean InterestInference inference;
  int role;
  @BeforeEach void model()throws Exception {
    role=stories.db().queryForObject("SELECT MIN(id) FROM role_card",Integer.class);
    when(models.embedding(anyString())).thenReturn(new float[1024]);
    when(models.complete(any(),anyList(),eq(80))).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"shared_past_question\"}",new OpenAiTransport.Usage(20,8,false)));
    when(models.stream(any(),anyList(),any(),any())).thenAnswer(call->{if(!((OpenAiTransport.Control)call.getArgument(2)).stopped.get())((java.util.function.Consumer<String>)call.getArgument(3)).accept("已有资料的普通回应");return new OpenAiTransport.Usage(1,1,false);});
  }
  String chapter(String world){String id="past_"+UUID.randomUUID();stories.create(1,id,role,world,new RpState());return id;}
  String send(String chapter,String text){var headers=new HttpHeaders();headers.setBearerAuth(TOKEN);headers.setContentType(MediaType.APPLICATION_JSON);var reply=client.postForEntity("/api/chat",new HttpEntity<>(Map.of("conversationId",chapter,"roleId",role,"message",text),headers),String.class);assertEquals(200,reply.getStatusCode().value());return reply.getBody();}
  long latest(String chapter){return stories.db().queryForObject("SELECT MAX(id) FROM history WHERE conversation_id=? AND sender='assistant'",Long.class,chapter);}
  @Test void EmptyWorldReturnsHonestVisibleReplyAndDoesNotIndexItAsAnExperience()throws Exception {
    String c=chapter(null);String response=send(c,"银帆节的约定是什么？不知道请直说。");long first=latest(c);
    assertTrue(response.contains(MissingPastAnswer.NOTICE));assertTrue(response.contains("event:done"));
    var context=json.readTree(stories.db().queryForObject("SELECT context_json FROM message_context WHERE history_id=?",String.class,first));assertTrue(context.path("memoryUnavailable").asBoolean());
    memory.drain();assertEquals(0,stories.db().queryForObject("SELECT COUNT(*) FROM memory_entry WHERE history_id=?",Integer.class,first));
    String c2=chapter(stories.sessions().worldId(c));assertTrue(send(c2,"你记得我们以前的约定吗？").contains(MissingPastAnswer.NOTICE));
    verify(models,never()).stream(any(),anyList(),any(),any());
  }
  @Test void HiddenLongTermEntryDoesNotEraseVisibleCurrentContextButNewChapterHasNoFact()throws Exception {
    String c=chapter(null);send(c,"我们上次约好周日在码头交换船票。");long fact=latest(c);String world=stories.sessions().worldId(c);
    stories.db().update("INSERT INTO memory_feedback(history_id,world_id,correction,hidden) VALUES(?,?,'',TRUE)",fact,world);
    clearInvocations(models);
    when(models.complete(any(),anyList(),eq(180))).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"shared_past_question\",\"evidence\":[1]}",new OpenAiTransport.Usage(1,1,false)));
    assertTrue(send(c,"我们以前约好做什么？").contains("周日在码头交换船票"));
    verify(models,never()).complete(any(),anyList(),eq(80));
    // The follow-up question itself does not constitute a new user fact.
    String c2=chapter(world);assertTrue(send(c2,"我们以前约好做什么？").contains(MissingPastAnswer.NOTICE));
  }
  @Test void AProvidedBackgroundQuestionUsesTheNormalModelWithoutEmptyWorldFallback()throws Exception {
    String c=chapter(null);String response=send(c,"我们上次约好周日看展，地址是什么？");
    assertTrue(response.contains("已有资料的普通回应"));assertFalse(response.contains(MissingPastAnswer.NOTICE));
    verify(models,never()).complete(any(),anyList(),eq(80));
  }
  @Test void UnrelatedSavedProfileDoesNotBlockHonestPastFallbackAndStillRecallsExactly()throws Exception {
    String c=chapter(null);send(c,"我们上次约好在青瓷桥交换铜铃。");long old=latest(c);String world=stories.sessions().worldId(c);
    stories.db().update("INSERT INTO memory_feedback(history_id,world_id,correction,hidden) VALUES(?,?,'',TRUE)",old,world);
    String c2=chapter(world);send(c2,"我的专属口令是蓝鲸七弦。");long profile=latest(c2);
    stories.db().update("INSERT INTO memory_feedback(history_id,world_id,correction,hidden) VALUES(?,?,'我的专属口令是蓝鲸七弦。',FALSE)",profile,world);
    String c3=chapter(world);clearInvocations(models);
    assertTrue(send(c3,"星砂日的约定是什么？不知道请直说。").contains(PastFactAnswer.UNMATCHED));
    String answer=send(c3,"我的专属口令是什么？");
    assertTrue(answer.contains("你记录的专属口令是蓝鲸七弦。"));
    verify(models,never()).stream(any(),anyList(),any(),any());
  }

 @Test void subjectFirstCorrectionAnswersPresentReferenceAcrossSameWorldOnly()throws Exception{
  String first=chapter(null);send(first,"我把绿色星盘放在白桦码头的蓝色帆布袋里。");long original=latest(first);String world=stories.sessions().worldId(first);
  stories.db().update("INSERT INTO memory_feedback(history_id,world_id,correction,hidden) VALUES(?,?,?,FALSE)",original,world,"绿色星盘放在白桦码头的红色木匣里。");
  when(models.complete(any(),anyList(),eq(180))).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"record_question\",\"evidence\":[1]}",new OpenAiTransport.Usage(2,2,false)));
  String continued=chapter(world);clearInvocations(models);String response=send(continued,"那块星盘现在放在哪里？");assertTrue(response.contains("红色木匣"),response);assertTrue(response.contains("记忆更正"));verify(models,never()).stream(any(),anyList(),any(),any());
  var saved=json.readTree(stories.db().queryForObject("SELECT context_json FROM message_context WHERE history_id=?",String.class,latest(continued)));assertEquals(original,saved.path("memoryAnswer").path("historyIds").get(0).asLong());
  clearInvocations(models);assertTrue(send(chapter(null),"那块星盘现在放在哪里？").contains("已有资料的普通回应"));verify(models,never()).complete(any(),anyList(),eq(180));
  when(models.complete(any(),anyList(),eq(180))).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"other\",\"evidence\":[]}",new OpenAiTransport.Usage(1,1,false)));
  assertTrue(send(continued,"这种星盘怎么保养？").contains("已有资料的普通回应"));
 }

 @Test void sqlCorrectionRetainsPersonalProvenance()throws Exception{
  String c=chapter(null);send(c,"我的暗号是青叶。");long id=latest(c);String world=stories.sessions().worldId(c);
  stories.db().update("INSERT INTO memory_feedback(history_id,world_id,correction,hidden) VALUES(?,?,?,FALSE)",id,world,"暗号是赤叶。");
  var facts=PastFactAnswer.facts(stories.db(),world);var corrected=facts.stream().filter(f->f.historyId()==id).findFirst().orElseThrow();assertEquals("personal_correction",corrected.kind());assertEquals("暗号是赤叶。",corrected.text());assertFalse(PastFactAnswer.supportsQuestionRole("我们之前交换的暗号是什么？",corrected));
 }
}
