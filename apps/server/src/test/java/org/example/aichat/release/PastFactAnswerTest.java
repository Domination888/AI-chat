package org.example.aichat.release;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
class PastFactAnswerTest {
 @Test void conflictingRecordsAreQuotedTogetherAndGeneratedOutputCannotInventFacts()throws Exception{
  var models=mock(OpenAiTransport.class);var connection=mock(ModelConnections.Connection.class);var facts=List.of(new PastFactAnswer.Fact(1,"我把罗盘放在山顶旅馆的黑色抽屉里。"),new PastFactAnswer.Fact(2,"我把罗盘放在河畔仓库的白色木柜里。"));
  when(models.complete(any(),anyList(),anyInt())).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"shared_past_question\",\"evidence\":[1,2],\"invented\":\"new location\"}",new OpenAiTransport.Usage(1,1,false)));
  var answer=PastFactAnswer.answer("我之前把罗盘放在哪里了？",facts,models,connection,new ObjectMapper(),usage->{}).orElseThrow();assertEquals(List.of(1L,2L),answer.historyIds());assertTrue(answer.text().contains("黑色抽屉"));assertTrue(answer.text().contains("白色木柜"));assertTrue(answer.text().contains("请确认"));assertFalse(answer.text().contains("new location"));
 }
 @Test void questionsHypothesesAndFreshCreativeRequestsAreNotPromotedToFacts()throws Exception{
  var models=mock(OpenAiTransport.class);var fact=List.of(new PastFactAnswer.Fact(1,"我把罗盘放在白色木柜里。"));
  for(String query:List.of("给我编一个罗盘丢失的故事","假如我把罗盘放在白色木柜里会怎样？","我来补充：上次我们修好了罗盘，接着聊吧"))assertTrue(PastFactAnswer.answer(query,fact,models,null,new ObjectMapper(),u->{}).isEmpty());
  assertFalse(PastFactAnswer.related("我之前把钱包放在哪里了？",fact.get(0).text()));verifyNoInteractions(models);
 }
 @Test void unrelatedPersonalOrSoloEventCannotProveSharedExchangeEvenIfModelSelectsIt()throws Exception{
  var models=mock(OpenAiTransport.class);when(models.complete(any(),anyList(),anyInt())).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"shared_past_question\",\"evidence\":[1]}",new OpenAiTransport.Usage(1,1,false)));
  for(var fact:List.of(new PastFactAnswer.Fact(1,"我喜欢乌龙茶。","personal_fact"),new PastFactAnswer.Fact(2,"我把钥匙放在绿色铁盒里。"))){var answer=PastFactAnswer.answer("我们上次银灯节去了哪里交换什么？",List.of(fact),models,null,new ObjectMapper(),u->{}).orElseThrow();assertEquals(PastFactAnswer.UNMATCHED,answer.text());assertTrue(answer.historyIds().isEmpty());}
 }
 @Test void semanticAliasAndSavedSceneDoNotRequireLexicalMatch()throws Exception{
  var models=mock(OpenAiTransport.class);when(models.complete(any(),anyList(),anyInt())).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"shared_past_question\",\"evidence\":[1]}",new OpenAiTransport.Usage(1,1,false)));
  var fact=new PastFactAnswer.Fact(1,"我们上次在旧码头修好了船钟。");String question="还记得那次修理计时器的经历吗？我们当时在哪儿忙活？";assertFalse(PastFactAnswer.related(question,fact.text()));assertTrue(PastFactAnswer.answer(question,List.of(fact),models,null,new ObjectMapper(),u->{}).orElseThrow().text().contains("旧码头"));
  var state=new org.example.aichat.rp.RpState();state.commitments=List.of("待履行：周日到海边观测灯塔");var answer=PastFactAnswer.answer("我们上次约好什么时候去哪里做什么？",PastFactAnswer.withScene(List.of(),state),models,null,new ObjectMapper(),u->{}).orElseThrow();assertTrue(answer.text().contains("待履行"));assertEquals(List.of("scene_commitment"),answer.sourceTypes());
 }

 @Test void presentReferencesRequireFactsAndKeepGeneralAdviceOnNormalPath()throws Exception{
  var facts=List.of(new PastFactAnswer.Fact(104,"绿色星盘放在白桦码头的红色木匣里。","user_correction"));
  assertTrue(PastFactAnswer.eligible("那块星盘现在放在哪里？",facts));assertFalse(PastFactAnswer.eligible("那块星盘现在放在哪里？",List.of()));
  assertTrue(PastFactAnswer.declarativeCorrection(facts.get(0).text(),"我把绿色星盘放在码头的蓝袋里。"));
  for(String correction:List.of("是不是放在红木匣里？","如果下雨放进柜子","请把它放进柜子","创作一个藏物故事"))assertFalse(PastFactAnswer.declarativeCorrection(correction,"我把星盘放在码头。"));
  var models=mock(OpenAiTransport.class);when(models.complete(any(),anyList(),eq(180))).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"record_question\",\"evidence\":[1]}",new OpenAiTransport.Usage(1,1,false)));
  assertTrue(PastFactAnswer.answer("那块星盘现在放在哪里？",facts,models,null,new ObjectMapper(),u->{}).orElseThrow().text().contains("红色木匣"));
  when(models.complete(any(),anyList(),eq(180))).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"other\",\"evidence\":[]}",new OpenAiTransport.Usage(1,1,false)));
  assertTrue(PastFactAnswer.answer("这种星盘怎么保养？",facts,models,null,new ObjectMapper(),u->{}).isEmpty());
  assertFalse(PastFactAnswer.eligible("假设那块星盘在云上，写一个故事",facts));
 }

 @Test void personalCorrectionsCannotProveSharedPastEvenIfClassifierSelectsThem()throws Exception{
  var models=mock(OpenAiTransport.class);when(models.complete(any(),anyList(),eq(180))).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"shared_past_question\",\"evidence\":[1]}",new OpenAiTransport.Usage(1,1,false)));
  for(String text:List.of("暗号是赤叶。","我喜欢红茶。","年龄是二十岁。")){
   var fact=new PastFactAnswer.Fact(1,text,"personal_correction");assertTrue(PastFactAnswer.isPersonal(fact));assertFalse(PastFactAnswer.supportsQuestionRole("我们之前的共同经历是什么？",fact));
   var answer=PastFactAnswer.answer("我们之前的共同经历是什么？",List.of(fact),models,null,new ObjectMapper(),u->{}).orElseThrow();assertEquals(PastFactAnswer.UNMATCHED,answer.text());assertTrue(answer.historyIds().isEmpty());
   assertTrue(PastFactAnswer.quoted(List.of(fact)).text().contains("个人记忆更正"));
  }
 }
}
