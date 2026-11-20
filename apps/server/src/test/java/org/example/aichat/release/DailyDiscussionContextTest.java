package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.example.aichat.rp.RpState;

class DailyDiscussionContextTest extends DailyDiscussionContextFixture {

 @Test void groundedDailyDoesNotReplayWrongAssistantAnswersFromHistoryOrMemory(){
  String c=chapter();stories.message(c,"user","OLD_USER_TOPIC");long old=stories.message(c,"assistant","OLD_ASSISTANT_OPENING");stories.db().update("INSERT INTO message_context(history_id,conversation_id,context_json) VALUES(?,?,?)",old,c,"{\"isProactive\":true}");
  send(c,"我们聊聊 AI 日报吧，请挑几条看点并附上来源。");var messages=prompts.get(0);String system=messages.get(0).get("content").toString();
  assertEquals(2,messages.size());assertFalse(messages.toString().contains("OLD_ASSISTANT_OPENING"));assertFalse(system.contains("MEMORY_DISTRACTOR"));assertFalse(system.contains("【本轮回应与记忆使用】"));
  assertTrue(system.contains("SCENE_LOCATION"));assertTrue(system.contains("测试新闻一"));assertTrue(system.contains("https://example.org/one"));
 }

 @Test void numberedAndSourceFollowupsResolveSavedDigestBeforeHistoryIsolation(){
  String c=chapter();send(c,"看看 AI 日报");send(c,"第2条展开讲讲");String response=send(c,"来源呢");
  assertTrue(response.contains("https://example.org/two"));assertFalse(response.contains("https://example.org/one"));
  for(int i:List.of(1,2)){var messages=prompts.get(i);assertEquals(2,messages.size());String system=messages.get(0).get("content").toString();assertTrue(system.contains("第二条专属详情"));assertFalse(system.contains("第一条正文"));assertFalse(system.contains("MEMORY_DISTRACTOR"));}
 }

 @Test void groundedTaskKeepsTheExactQuestionAndSelectedEvidenceWhilePrioritizingFactualAnswer(){
  String c=chapter(),question="请展开日报第2条，解释具体改动，并给来源。";send(c,question);
  var messages=prompts.get(0);String system=messages.get(0).get("content").toString();
  assertEquals(question,messages.get(messages.size()-1).get("content"));
  assertTrue(system.indexOf("【本轮日报任务】")>system.indexOf("第二条专属详情"));assertTrue(system.contains("资料未提供"));
  assertTrue(system.contains("第二条专属详情"));assertTrue(system.contains("https://example.org/two"));
  assertFalse(system.contains("第一条正文"));assertTrue(system.contains("SCENE_LOCATION"));
 }

 @Test void unavailableDailyDoesNotReceiveTheGroundedAnswerInstruction()throws Exception{
  String c=chapter();stories.message(c,"user","OLD_USER_TOPIC");
  call("/api/v1/me/companion",HttpMethod.PUT,Map.of("dailyEnabled",false));
  String response=send(c,"请展开日报第2条，解释具体改动，并给来源。");
  assertTrue(response.contains("尚未开启"));assertFalse(response.contains("https://example.org/"));
  assertTrue(prompts.isEmpty());verify(models,never()).stream(any(),anyList(),any(),any());
 }

 @Test void explicitCorrectionKeepsSavedRecordsButDoesNotReinforceAnOldFixedReply(){
  String c=chapter();int role=((Number)stories.owned(1,c).get("role_id")).intValue();
  String catchphrase="我是测试角色。常看星空。";
  stories.db().update("INSERT INTO character_profile(role_id,profile_json) VALUES(?,?)",role,"{\"card\":{\"persona\":{\"identity\":\"温柔的测试角色\",\"speech\":\"自然说话\"},\"mantra\":\""+catchphrase+"从现在开始保持身份。\"}}");
  long old=stories.message(c,"assistant",catchphrase);
  String world=stories.sessions().worldId(c);
  stories.db().update("INSERT INTO memory_feedback(history_id,world_id,correction,hidden) VALUES(?,?,?,FALSE)",old,world,"旧记录：喜欢冰茶。");
  when(memory.recall(anyString(),anyString())).thenReturn("用户已纠正，以此为准：旧记录：喜欢冰茶。\n用户纠正的记忆：旧记录：喜欢冰茶。");
  try{
    String correction="我喜欢的是热茶，不是冰茶。请更正并确认。";send(c,correction);
    var messages=prompts.get(0);String system=messages.get(0).get("content").toString();
    assertEquals(correction,messages.get(messages.size()-1).get("content"));
    assertTrue(system.contains("用户本轮的明确纠正"));assertTrue(system.contains("旧更正记录：旧记录：喜欢冰茶。"));
    assertFalse(system.contains("以此为准"));assertFalse(system.contains("表达习惯："));assertTrue(system.contains("SCENE_LOCATION"));
    assertTrue(messages.stream().skip(1).anyMatch(m->catchphrase.equals(m.get("content"))));
    assertEquals("旧记录：喜欢冰茶。",stories.db().queryForObject("SELECT correction FROM memory_feedback WHERE history_id=?",String.class,old));
    assertEquals(catchphrase,stories.db().queryForObject("SELECT content FROM history WHERE id=?",String.class,old));
    send(c,"你记得我喜欢什么吗？");String following=prompts.get(1).get(0).get("content").toString();
    assertTrue(following.contains("【用户最近的明确更正】\n"+correction));
    assertEquals("你记得我喜欢什么吗？",prompts.get(1).get(prompts.get(1).size()-1).get("content"));
  }finally{stories.db().update("DELETE FROM character_profile WHERE role_id=?",role);}
 }

 @Test void ordinaryRoleplayKeepsConversationAndMemoryContext(){
  String c=chapter();stories.message(c,"user","OLD_USER_TOPIC");stories.message(c,"assistant","OLD_ASSISTANT_OPENING");send(c,"我们周末读什么书？");var messages=prompts.get(0);
  assertTrue(messages.toString().contains("OLD_ASSISTANT_OPENING"));assertTrue(messages.get(0).get("content").toString().contains("MEMORY_DISTRACTOR"));assertFalse(messages.get(0).get("content").toString().contains("【AI 日报外部资料"));assertFalse(messages.get(0).get("content").toString().contains("【本轮日报任务】"));
 }

 @Test void combinedSearchAndDailyKeepBothTaskRulesWithinTheBudget()throws Exception{
  when(search.search(anyInt(),anyString(),anyList(),any())).thenReturn(new SearchRag.Result("ok",List.of("daily"),List.of(),"SEARCH_CONTEXT "+"检索事实".repeat(400),""));
  String c=chapter(),question="请展开日报第2条，解释具体改动，并给来源。";int role=((Number)stories.owned(1,c).get("role_id")).intValue();
  String response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",c,"roleId",role,"message",question,"search",true));
  assertTrue(response.contains("event:done"),response);String system=prompts.get(0).get(0).get("content").toString();
  assertFalse(system.contains("【本轮联网任务】"));assertTrue(system.contains("【本轮日报任务】"));
  verify(search,never()).search(anyInt(),anyString(),anyList(),any());assertFalse(system.contains("SEARCH_CONTEXT"));
  assertTrue(system.contains("第二条专属详情"));assertTrue(system.contains("https://example.org/two"));
  assertTrue(PromptBudget.tokens(system)<=2400);assertTrue(system.endsWith("不添加场景描写。\n"));
  assertEquals(question,prompts.get(0).get(prompts.get(0).size()-1).get("content"));
 }

 @Test void fullIssueFollowupKeepsTheActualPrecedingIssueAndCompoundRequest()throws Exception{
  String c=chapter();send(c,"看看 AI 日报");
  String question="你刚才那条Jules关停消息来自哪一期？请只依据同一期日报说明原文还说了什么，并给来源。";
  String response=send(c,question);var system=prompts.get(1).get(0).get("content").toString();
  assertTrue(system.contains("期刊日期："+date()));assertTrue(system.contains("第一条正文"));assertFalse(system.contains("MEMORY_DISTRACTOR"));assertTrue(response.contains("event:sources"));
  assertEquals(question,prompts.get(1).get(prompts.get(1).size()-1).get("content"));
 }

 @Test void barePublicationQuestionAfterOrdinaryChatDoesNotInheritOldDaily(){
  String c=chapter();send(c,"看看 AI 日报");send(c,"你好，我们聊聊茶");send(c,"发布日期是什么？");
  assertFalse(prompts.get(2).get(0).get("content").toString().contains("【本轮日报任务】"));
 }
}
