package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.aichat.rp.RpState;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.*;

class MissingPastAnswerTest {
  SingleConnectionDataSource source;JdbcTemplate db;
  @BeforeEach void database() {
    source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);db=new JdbcTemplate(source);
    db.execute("CREATE TABLE rp_session(conversation_id TEXT,world_id TEXT)");
    db.execute("CREATE TABLE message_context(history_id INTEGER,conversation_id TEXT,context_json TEXT)");
    db.execute("CREATE TABLE memory_feedback(history_id INTEGER,world_id TEXT,hidden BOOLEAN,correction TEXT)");
    db.execute("CREATE TABLE rp_turn(history_id INTEGER,world_id TEXT,user_text TEXT,status TEXT)");
    db.update("INSERT INTO rp_session VALUES('chapter','world')");
  }
  @AfterEach void close(){source.destroy();}
  void turn(int id,String user){db.update("INSERT INTO rp_turn VALUES(?,'world',?,'complete')",id,user);}

  @Test void openingAndOldQuestionAreNotUserFactsButStatementsAndCorrectionsAre() {
    turn(1,"");turn(2,"还记得我们以前的约定吗？");
    assertTrue(MissingPastAnswer.confirmedEmpty(db,"world",new RpState()));
    turn(3,"上次在港口修好了钟");
    assertFalse(MissingPastAnswer.confirmedEmpty(db,"world",new RpState()));
    db.update("INSERT INTO memory_feedback VALUES(3,'world',TRUE,'')");
    assertTrue(MissingPastAnswer.confirmedEmpty(db,"world",new RpState()));
    db.update("UPDATE memory_feedback SET hidden=FALSE,correction='上次一起修好了木马'");
    assertFalse(MissingPastAnswer.confirmedEmpty(db,"world",new RpState()));
  }
  @Test void ScheduledAndUnavailableAnswersCannotBecomeEvidenceForThemselves() {
    turn(1,"我们上次约好去港口");turn(2,"我仍记得一个约定");
    db.update("INSERT INTO message_context VALUES(1,'chapter','{\"scheduledSkill\":\"a\"}')");
    db.update("INSERT INTO message_context VALUES(2,'chapter','{\"memoryUnavailable\":true}')");
    assertTrue(MissingPastAnswer.confirmedEmpty(db,"world",new RpState()));
    assertEquals(Set.of(2L),MissingPastAnswer.unavailableIds(db,"world"));
    db.update("INSERT INTO memory_feedback VALUES(2,'world',FALSE,'用户补充的真实约定')");
    assertEquals(Set.of(),MissingPastAnswer.unavailableIds(db,"world"));
    assertFalse(MissingPastAnswer.confirmedEmpty(db,"world",new RpState()));
  }
  @Test void UntrackedSceneMaterialDoesNotBecomeAFalseEmptyClaim() {
    var state=new RpState();state.commitments=List.of("每周日去码头");
    assertFalse(MissingPastAnswer.confirmedEmpty(db,"world",state));
    state.commitments=List.of();state.directorNote="之前共同修过木马";
    assertFalse(MissingPastAnswer.confirmedEmpty(db,"world",state));
  }
  @Test void ANewProposalMakesPastEvidenceUncertainRatherThanEmpty() {
    String proposal="这周日我们一起去码头好吗？";
    assertFalse(MissingPastAnswer.candidate(proposal));
    assertFalse(MissingPastAnswer.candidate("要不要约定下周日再来？"));
    assertTrue(MissingPastAnswer.currentEvidence(List.of(Map.of("sender","user","content",proposal))));
    turn(1,proposal);
    assertFalse(MissingPastAnswer.confirmedEmpty(db,"world",new RpState()));
  }
  @Test void AProfileFieldDoesNotProveAPastEventButRemainsAnswerableWithoutInventedEvents() {
    turn(1,"我们以前一起做过一些事");db.update("INSERT INTO memory_feedback VALUES(1,'world',FALSE,'我的专属口令是蓝鲸七弦。')");
    assertTrue(MissingPastAnswer.confirmedEmpty(db,"world",new RpState()));
    var correction=List.<Map<String,Object>>of(Map.of("correction","我的专属口令是蓝鲸七弦。"));
    assertEquals("你记录的专属口令是蓝鲸七弦。",MissingPastAnswer.profileAnswer("我的专属口令是什么？",correction,List.of()).orElseThrow());
    assertEquals("你记录的专属口令是蓝鲸七弦。",MissingPastAnswer.profileAnswer("我的专属口令是什么？",correction,List.of(Map.of("sender","user","content","嗨，今天还好吗？"))).orElseThrow());
    assertTrue(MissingPastAnswer.profileAnswer("我的专属口令是什么，给它编一个来历",correction,List.of()).isEmpty());
    assertTrue(MissingPastAnswer.profileAnswer("我的专属口令是什么？",correction,List.of(Map.of("sender","user","content","更正：我的专属口令是银帆。"))).isEmpty());
    assertTrue(MissingPastAnswer.profileAnswer("我的名字是什么？",List.of(Map.of("correction","我的名字是阿青。"),Map.of("correction","我的名字是阿蓝。")),List.of()).orElseThrow().contains("有不同记录：阿青；阿蓝"));
    assertFalse(MissingPastAnswer.userAssertion("我的名字是阿青。"));
    assertFalse(MissingPastAnswer.userAssertion("我的专属口令是什么？"));
    assertTrue(MissingPastAnswer.userAssertion("我的口令是我们上次一起商量的。"));
    db.update("UPDATE memory_feedback SET correction='我的口令是我们上次一起商量的。'");
    assertFalse(MissingPastAnswer.confirmedEmpty(db,"world",new RpState()));
  }
  @Test void ACurrentCorrectedFactIsReplacedForTheGateButHiddenVisibleContextIsPreserved() {
    var previous=List.<Map<String,Object>>of(Map.of("id",7L,"sender","user","content","我们上次一起去了码头"));
    assertTrue(MissingPastAnswer.currentEvidence(MissingPastAnswer.withoutReplacedUserFacts(previous,List.of())));
    assertFalse(MissingPastAnswer.currentEvidence(MissingPastAnswer.withoutReplacedUserFacts(previous,List.of(Map.of("user_history_id",7L,"correction","我的名字是阿青。")))));
  }
  @Test void CurrentBackgroundCreativeTasksAndDefinitionsBypassTheGate() {
    for(String text:List.of("我们上次约好周日看展，地址是什么？","我来补充：昨天我们修好了木马，你记得吗？","给我们新编一个约定","请解释HTTP中的约定是什么","婚姻里约定的含义是什么？","还记得小说《我们》的结尾吗？","上次在港口修好了钟","We agreed to meet on Sunday, where should we go?","今天想做什么？"))
      assertFalse(MissingPastAnswer.candidate(text),text);
    assertTrue(MissingPastAnswer.candidate("银帆节的约定是什么？不知道请直说。"));
    assertTrue(MissingPastAnswer.candidate("What did we agree to do last time?"));
    assertTrue(MissingPastAnswer.userAssertion("上次在港口修好了钟"));
    assertTrue(MissingPastAnswer.providedBackground("星砂日我们去紫钟塔交换银羽书签。我的专属口令是蓝鲸七弦。"));
    assertTrue(MissingPastAnswer.providedBackground("我记得我们上次约好去码头。"));
    assertFalse(MissingPastAnswer.providedBackground("我们上次去了哪？"));
    assertFalse(MissingPastAnswer.providedBackground("我的专属口令是什么？"));
    assertFalse(MissingPastAnswer.userAssertion("说说我们上次的约定"));
  }
  @Test void OnlyExactValidClassificationCanProduceTheFallbackAndUsageIsRecorded()throws Exception {
    var model=mock(OpenAiTransport.class);var connection=new ModelConnections.Connection("http://127.0.0.1","model","",false);
    var usage=new OpenAiTransport.Usage(20,8,false);var recorded=new ArrayList<OpenAiTransport.Usage>();
    when(model.complete(eq(connection),anyList(),eq(80))).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"shared_past_question\"}",usage));
    assertEquals(MissingPastAnswer.NOTICE,MissingPastAnswer.answer("你记得我们以前的约定吗？",model,connection,new ObjectMapper(),recorded::add).orElseThrow());
    assertEquals(List.of(usage),recorded);
    when(model.complete(eq(connection),anyList(),eq(80))).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"other\"}",usage));
    assertTrue(MissingPastAnswer.answer("你记得我们以前的约定吗？",model,connection,new ObjectMapper(),recorded::add).isEmpty());
    when(model.complete(eq(connection),anyList(),eq(80))).thenReturn(new OpenAiTransport.Completion("{broken",usage));
    assertTrue(MissingPastAnswer.answer("你记得我们以前的约定吗？",model,connection,new ObjectMapper(),recorded::add).isEmpty());
    clearInvocations(model);
    assertTrue(MissingPastAnswer.answer("我们上次约好周日看展，地址是什么？",model,connection,new ObjectMapper(),recorded::add).isEmpty());
    verifyNoInteractions(model);
  }
  @Test void FactualEvidenceSurvivesWithoutIncreasingTheCombinedTextBudget() {
    String evidence="资料起点"+"证".repeat(2050)+"最后一项真实变化";
    String system=SearchFactPrompt.context("角色",evidence)+SearchFactPrompt.instruction("概括主要变化");
    var messages=PromptBudget.assemble(system,List.of(Map.of("sender","assistant","content","旧回答".repeat(1500))),"概括主要变化","概括主要变化",2800);
    assertTrue(messages.get(0).get("content").toString().contains("最后一项真实变化"));
    assertTrue(messages.stream().mapToInt(m->PromptBudget.tokens(m.get("content").toString())+20).sum()<=6000);
    assertEquals("概括主要变化",messages.get(messages.size()-1).get("content"));
  }
  @Test void ordinaryConversationDoesNotProveSharedHistoryAndQuotedAssistantTextStaysAvailable(){
    for(String text:List.of("你好，今天想来坐坐。","最近过得还好吗？","Hello, how are you?","UXAUDIT：你好，林间，今天想来坐坐。"))assertFalse(MissingPastAnswer.userAssertion(text),text);
    turn(1,"你好，今天想来坐坐。");assertTrue(MissingPastAnswer.confirmedEmpty(db,"world",new RpState()));
    assertTrue(MissingPastAnswer.userAssertion("我把玻璃罗盘放在了河畔白色木柜里。"));assertTrue(MissingPastAnswer.userAssertion("我们之前在港口。"));assertTrue(MissingPastAnswer.userAssertion("I left the compass in the cabinet."));assertFalse(MissingPastAnswer.userAssertion("假如我把罗盘放在白色木柜里。"));
    assertFalse(MissingPastAnswer.candidate("你上次写的那首诗原话是什么？"));assertFalse(MissingPastAnswer.candidate("你上次答应我什么？"));
  }
}
