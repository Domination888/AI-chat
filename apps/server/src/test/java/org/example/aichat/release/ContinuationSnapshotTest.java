package org.example.aichat.release;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import org.example.aichat.rp.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.nio.file.*;
import java.util.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContinuationSnapshotTest {
 static final String TOKEN="continuation-test-local-token-1234567890123456";
 static final Path DATA;static{try{DATA=Files.createTempDirectory("continuation-snapshot-");}catch(Exception e){throw new RuntimeException(e);}}
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("story.mode",()->"offline");r.add("story.data-dir",()->DATA.toString());r.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("test.sqlite"));r.add("story.local-token",()->TOKEN);}
 @Autowired StoryStore stories;@Autowired ObjectMapper json;@Autowired TestRestTemplate client;
 @MockitoBean OpenAiTransport models;@MockitoBean MemoryIndex memory;@MockitoBean SearchRag search;
 @BeforeEach void defaults(){when(memory.recall(anyString(),anyString())).thenReturn("");}
 int role(){return stories.db().queryForObject("SELECT MIN(id) FROM role_card",Integer.class);}
 String chapter(){String c="snapshot_"+UUID.randomUUID();stories.create(1,c,role(),null,new RpState());return c;}
 long reply(String c,String question,String answer,String status){stories.message(c,"user",question);long id=stories.message(c,"assistant",answer);stories.finish(c,id,question,answer,status,List.of(),"");return id;}
 @Test void rewindRestoresManualPreTargetStateAndBranchKeepsCheckpoint(){
  String c=chapter();reply(c,"","greeting","complete");var scene=stories.sessions().state(c);scene.location="morning room";scene.commitments=List.of("silver bookmark");stories.sessions().editScene(c,scene);
  long first=reply(c,"tea please","tea reply","complete");scene=stories.sessions().state(c);scene.location="amber station";scene.commitments=List.of("blue umbrella");stories.sessions().editScene(c,scene);reply(c,"wait","later","complete");
  String branch=stories.branch(c,first);long copied=stories.db().queryForObject("SELECT MAX(id) FROM history WHERE conversation_id=?",Long.class,branch);
  stories.sessions().rewind(branch,copied);assertEquals("morning room",stories.sessions().state(branch).location);assertEquals(List.of("silver bookmark"),stories.sessions().state(branch).commitments);
  stories.sessions().rewind(c,first);assertEquals("morning room",stories.sessions().state(c).location);assertEquals(List.of("silver bookmark"),stories.sessions().state(c).commitments);
 }
 @Test void oldRowsFallbackToPreviousAfterWithoutInventingManualCheckpoint(){
  String c=chapter();reply(c,"","greeting","complete");var scene=stories.sessions().state(c);scene.location="manual later";stories.sessions().editScene(c,scene);long id=reply(c,"first","reply","complete");stories.db().update("UPDATE rp_turn SET before_state_json=NULL WHERE history_id=?",id);stories.sessions().rewind(c,id);assertNotEquals("manual later",stories.sessions().state(c).location);
 }
 @Test void branchRemapsPersistedContinuationReference()throws Exception{
  String c=chapter();long original=reply(c,"original task","partial","interrupted"),next=reply(c,"continue","tail","complete");stories.db().update("INSERT INTO message_context VALUES(?,?,?)",next,c,"{\"continuationHistoryId\":"+original+"}");String branch=stories.branch(c,next);long mapped=json.readTree(stories.db().queryForObject("SELECT context_json FROM message_context WHERE conversation_id=?",String.class,branch)).path("continuationHistoryId").asLong();assertNotEquals(original,mapped);assertEquals(branch,stories.db().queryForObject("SELECT conversation_id FROM history WHERE id=?",String.class,mapped));
 }
 ResponseEntity<String> send(String c,long target){var h=new HttpHeaders();h.setBearerAuth(TOKEN);h.setContentType(MediaType.APPLICATION_JSON);return client.postForEntity("/api/chat",new HttpEntity<>(Map.of("conversationId",c,"roleId",role(),"message","继续","continuationHistoryId",target,"search",true),h),String.class);}
 @Test void continuedSearchUsesOriginalTaskAndPartialAndRejectsInvalidBeforeSaving()throws Exception{
  String c=chapter();long first=reply(c,"read https://www.python.org/ and compare dates","VISIBLE_PARTIAL"+" long content".repeat(1200)+"ACTUAL_CUTOFF_TAIL","interrupted");stories.db().update("INSERT INTO message_context VALUES(?,?,?)",first,c,"{\"completion\":{\"reason\":\"length\",\"incomplete\":true},\"sources\":[{\"title\":\"source\",\"url\":\"https://www.python.org/\"}]}");
  when(search.search(eq(1),anyString(),anyList(),any())).thenReturn(new SearchRag.Result("ok",List.of("original"),List.of(new SearchRag.Source("source","https://www.python.org/","VERIFIED_EVIDENCE",List.of(),1,true)),"VERIFIED_EVIDENCE",""));
  var prompts=new ArrayList<List<Map<String,Object>>>();when(models.stream(any(),anyList(),any(),any())).thenAnswer(call->{prompts.add(new ArrayList<>(call.getArgument(1)));((java.util.function.Consumer<String>)call.getArgument(3)).accept("continued");return new OpenAiTransport.Usage(1,1,false);});
  var response=send(c,first);assertEquals(200,response.getStatusCode().value());assertTrue(response.getBody().contains("event:done"));assertTrue(prompts.get(0).toString().contains("VISIBLE_PARTIAL"));assertTrue(prompts.get(0).toString().contains("ACTUAL_CUTOFF_TAIL"));assertTrue(prompts.get(0).toString().contains("中间已显示部分省略"));assertTrue(prompts.get(0).toString().contains("VERIFIED_EVIDENCE"));verify(search).search(eq(1),eq("read https://www.python.org/ and compare dates"),anyList(),any());
  long count=stories.db().queryForObject("SELECT COUNT(*) FROM history WHERE conversation_id=?",Long.class,c);long complete=stories.db().queryForObject("SELECT MAX(history_id) FROM rp_turn WHERE conversation_id=?",Long.class,c);assertEquals(400,send(c,complete).getStatusCode().value());assertEquals(count,stories.db().queryForObject("SELECT COUNT(*) FROM history WHERE conversation_id=?",Long.class,c));String other=chapter();assertEquals(400,send(other,first).getStatusCode().value());
 }
 @Test void plainContinuationDoesNotPretendItIsTheFirstUserMessage()throws Exception{
  String c=chapter();long first=reply(c,"tell a story","first interrupted text","interrupted");var prompts=new ArrayList<List<Map<String,Object>>>();when(models.stream(any(),anyList(),any(),any())).thenAnswer(call->{prompts.add(new ArrayList<>(call.getArgument(1)));((java.util.function.Consumer<String>)call.getArgument(3)).accept("tail");return new OpenAiTransport.Usage(1,1,false);});
  var h=new HttpHeaders();h.setBearerAuth(TOKEN);h.setContentType(MediaType.APPLICATION_JSON);var response=client.postForEntity("/api/chat",new HttpEntity<>(Map.of("conversationId",c,"roleId",role(),"message","继续","continuationHistoryId",first),h),String.class);
  assertTrue(response.getBody().contains("event:done"));assertFalse(prompts.get(0).get(0).get("content").toString().contains("【本世界线的对话进度】"));assertTrue(prompts.get(0).toString().contains("first interrupted text"));
 }
 @Test void continuedDailyKeepsOriginalIssueWhenLatestCacheIsGone()throws Exception{
  String c=chapter();long first=reply(c,"AI 日报 Original article还有什么","partial daily","interrupted");
  stories.db().update("INSERT INTO message_context VALUES(?,?,?)",first,c,"{\"aiDaily\":{\"date\":\"2026-10-30\",\"sourceUrl\":\"https://daily.juya.uk/issues/2026-10-30/\",\"items\":[{\"title\":\"Original article\",\"detail\":\"OLD_ISSUE_DETAIL\"}]},\"aiDailyItem\":0,\"sources\":[{\"title\":\"old issue\",\"url\":\"https://daily.juya.uk/issues/2026-10-30/\"}]}");
  var prompts=new ArrayList<List<Map<String,Object>>>();when(models.stream(any(),anyList(),any(),any())).thenAnswer(call->{prompts.add(new ArrayList<>(call.getArgument(1)));((java.util.function.Consumer<String>)call.getArgument(3)).accept("continued daily");return new OpenAiTransport.Usage(1,1,false);});
  var h=new HttpHeaders();h.setBearerAuth(TOKEN);h.setContentType(MediaType.APPLICATION_JSON);var response=client.postForEntity("/api/chat",new HttpEntity<>(Map.of("conversationId",c,"roleId",role(),"message","继续","continuationHistoryId",first,"dailyIssueDate","2026-10-30"),h),String.class);
  assertTrue(response.getBody().contains("event:done"));assertTrue(prompts.get(0).toString().contains("2026-10-30"));assertTrue(prompts.get(0).toString().contains("OLD_ISSUE_DETAIL"));verifyNoInteractions(search);
 }
}
