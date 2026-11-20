package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.aichat.rp.RpSessionService;
import org.example.aichat.rp.RpState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProactiveIdleTest {
  @TempDir Path directory;
  final ObjectMapper json=new ObjectMapper();
  final String conversation="idle-test";
  JdbcTemplate db;
  ProactiveService service;
  ChatApi chat;
  SkillRuntime skills;
  ObjectNode settings;
  final AtomicInteger generated=new AtomicInteger();

  @BeforeEach void setup()throws Exception{
    db=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("idle.sqlite")));
    db.execute("CREATE TABLE account(id INTEGER PRIMARY KEY,status TEXT)");
    db.execute("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT,conversation_id TEXT,sender TEXT,content TEXT)");
    db.execute("CREATE TABLE message_context(history_id INTEGER,context_json TEXT)");
    db.execute("CREATE TABLE rp_turn(history_id INTEGER,conversation_id TEXT,status TEXT)");
    db.execute("CREATE TABLE proactive_candidate(id TEXT PRIMARY KEY,user_id INTEGER,conversation_id TEXT,topic TEXT,title TEXT,summary TEXT,sources_json TEXT,fingerprint TEXT,created_at TEXT,status TEXT DEFAULT 'pending',history_id INTEGER,UNIQUE(user_id,conversation_id,fingerprint))");
    db.update("INSERT INTO account VALUES(1,'active')");
    var stories=mock(StoryStore.class);var sessions=mock(RpSessionService.class);
    when(stories.db()).thenReturn(db);when(stories.sessions()).thenReturn(sessions);
    when(stories.owned(1,conversation)).thenReturn(Map.of("id",conversation));
    var state=new RpState();state.mode="companion";when(sessions.state(conversation)).thenReturn(state);
    var companion=mock(CompanionService.class);settings=json.createObjectNode().put("proactiveEnabled",true).put("proactiveIdleSeconds",-1);
    // A negative mocked interval makes every tick eligible without sleeping or changing production limits.
    when(companion.settings(1)).thenReturn(settings);skills=mock(SkillRuntime.class);
    when(skills.list(1)).thenReturn(List.of());chat=mock(ChatApi.class);
    doAnswer(call->{
      generated.incrementAndGet();var metadata=new LinkedHashMap<String,Object>(call.getArgument(3));metadata.put("isProactive",true);
      long id=assistant("complete",metadata);var sink=(ChatApi.EventSink)call.getArgument(4);
      sink.send("done",Map.of("status","complete","historyId",id));sink.complete();return null;
    }).when(chat).proactive(eq(1),eq(conversation),anyString(),anyMap(),any());
    service=new ProactiveService(stories,companion,mock(AiDailyService.class),skills,chat,mock(SearchRag.class),mock(Streams.class),json);
    service.subscribe(1,conversation);
  }
  @AfterEach void close(){service.close();}
  long assistant(String status,Map<String,Object> metadata)throws Exception{
    db.update("INSERT INTO history(conversation_id,sender,content) VALUES(?,'assistant','主动来信')",conversation);
    long id=db.queryForObject("SELECT MAX(id) FROM history",Long.class);
    db.update("INSERT INTO message_context VALUES(?,?)",id,json.writeValueAsString(metadata));
    db.update("INSERT INTO rp_turn VALUES(?,?,?)",id,conversation,status);return id;
  }
  @Test void unansweredIdleDoesNotRepeatEvenAfterReconnect(){
    service.tick();assertEquals(1,generated.get());service.tick();service.tick();assertEquals(1,generated.get());
    service.unregister(1,conversation);service.subscribe(1,conversation);service.tick();assertEquals(1,generated.get());
  }
  @Test void typingPostponesButOnlySentUserMessageReopensCasualConversation(){
    service.tick();assertEquals(1,generated.get());
    settings.put("proactiveIdleSeconds",3600);service.touch(1,conversation);service.tick();assertEquals(1,generated.get());
    settings.put("proactiveIdleSeconds",-1);service.tick();assertEquals(1,generated.get(),"A draft alone must not rearm idle openers");
    db.update("INSERT INTO history(conversation_id,sender,content) VALUES(?,'user','我回复了')",conversation);
    service.activity(new ChatApi.UserActivity(1,conversation));service.tick();assertEquals(2,generated.get());
    service.tick();assertEquals(2,generated.get());
  }
  @Test void explicitInvitationStillWorksWhileAwaitingReply(){
    service.tick();assertEquals(1,generated.get());
    assertEquals(true,service.trigger(1,conversation).get("triggered"));assertEquals(2,generated.get());
    service.tick();assertEquals(2,generated.get());
  }
  @Test void scheduledCandidateIsQueuedAndDeliveredWhileAwaitingReply(){
    service.tick();assertEquals(1,generated.get());
    when(skills.list(1)).thenReturn(List.of(json.valueToTree(Map.of("name","daily-reminder","description","每日提醒","enabled",true,"instructions","喝杯水", "schedule",Map.of("enabled",true,"hour",0,"minute",0,"zone","UTC"),"proactive",Map.of("enabled",true,"promptTemplate","提醒用户")))));
    service.tick();assertEquals(2,generated.get());
    assertEquals("delivered",db.queryForObject("SELECT status FROM proactive_candidate",String.class));
    service.tick();assertEquals(2,generated.get(),"The same scheduled occurrence is not delivered twice");
  }
  @Test void preparedCandidateAlsoBypassesIdleGate(){
    service.tick();assertTrue(service.enqueue(1,conversation,"摄影","新资料","具体信息",List.of(),"new-research"));
    service.tick();assertEquals(2,generated.get());service.tick();assertEquals(2,generated.get());
  }
  @Test void allDisabledScheduledCandidatesAreCancelledWithoutBlockingResearch(){
    var manifests=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    for(int off=0;off<3;off++){
      String name="off-"+off;manifests.add(json.valueToTree(Map.of("name",name,"enabled",off!=0,"schedule",Map.of("enabled",off!=1),"proactive",Map.of("enabled",off!=2))));
      service.enqueue(1,conversation,"技能："+name,"旧提醒","不要执行",List.of(),"skill:"+name+":old");
    }
    service.enqueue(1,conversation,"技能：removed","已删除技能","不要执行",List.of(),"skill:removed:old");
    // A research topic may mention a skill; only a persisted skill occurrence is cancellable here.
    service.enqueue(1,conversation,"技能：removed","普通研究话题","真实研究资料",List.of(),"research:removed:current");
    when(skills.list(1)).thenReturn(manifests);service.tick();
    assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM proactive_candidate WHERE status='cancelled'",Integer.class));
    assertEquals("delivered",db.queryForObject("SELECT status FROM proactive_candidate WHERE fingerprint='research:removed:current'",String.class));assertEquals(1,generated.get());
    service.tick();assertEquals(1,generated.get());
  }
  @Test void failedOrOrdinaryAssistantMessagesDoNotConsumeIdleOpener()throws Exception{
    assistant("failed",Map.of("isProactive",true));assistant("complete",Map.of());
    service.tick();assertEquals(1,generated.get());service.tick();assertEquals(1,generated.get());
  }
}
