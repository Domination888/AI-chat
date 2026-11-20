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

class DailyIssueSelectionTest extends DailyDiscussionContextFixture {

 @Test void selectedOlderIssueSurvivesAnEditedDraftAndDisabledImplicitSkillWithoutRefreshing()throws Exception{
  String old=LocalDate.parse(date()).minusDays(1).toString(),c=chapter();
  var digest=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(stories.db().queryForObject("SELECT digest_json FROM companion_daily WHERE user_id=1",String.class));digest.put("date",old);
  stories.db().update("UPDATE companion_daily SET issue_date=?,digest_json=? WHERE user_id=1",old,json.writeValueAsString(digest));
  doReturn(false).when(skills).enabled(1,"ai-daily-juya");clearInvocations(content);
  int role=((Number)stories.owned(1,c).get("role_id")).intValue();String question="第2条还有什么细节？";
  String response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",c,"roleId",role,"message",question,"dailyIssueDate",old));
  assertTrue(response.contains("event:done"));assertTrue(response.contains("event:sources"));
  String system=prompts.get(0).get(0).get("content").toString();assertTrue(system.contains("期刊日期："+old));assertTrue(system.contains("第二条专属详情"));assertFalse(system.contains("MEMORY_DISTRACTOR"));
  assertEquals(question,prompts.get(0).get(prompts.get(0).size()-1).get("content"));verifyNoInteractions(content);
  var saved=json.readTree(stories.db().queryForObject("SELECT context_json FROM message_context WHERE conversation_id=? ORDER BY history_id DESC LIMIT 1",String.class,c));assertEquals(old,saved.path("aiDaily").path("date").asText());
 }

 @Test void invalidSelectionIsRejectedBeforeAcceptingTheUserTurn(){
  String c=chapter();int role=((Number)stories.owned(1,c).get("role_id")).intValue();int before=stories.db().queryForObject("SELECT COUNT(*) FROM history WHERE conversation_id=?",Integer.class,c);
  var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.setBearerAuth(TOKEN);
  var result=client.postForEntity("/api/chat",new HttpEntity<>(Map.of("conversationId",c,"roleId",role,"message","解释这条","dailyIssueDate","2026-02-30"),h),String.class);
  assertEquals(400,result.getStatusCode().value());assertTrue(result.getBody().contains("所选日报"));assertTrue(prompts.isEmpty());
  assertEquals(before,stories.db().queryForObject("SELECT COUNT(*) FROM history WHERE conversation_id=?",Integer.class,c));
 }

 @Test void fullTitleWithSharedBrandRetainsLateExportDetailWithinSmallBudget()throws Exception{
  String title="Google agent Jules推出全新远程任务管理和代码审查功能";
  when(content.get(contains("/markdown/"))).thenReturn("# AI 日报\n\n### ["+title+"](https://example.org/jules)\n> Google推出编程工具。\n\n"+"工具支持远程编程与代码管理。".repeat(35)+"用户可以导出完整执行记录与补丁。\n\n---\n\n### [Google Gemini发布新的图片工具](https://example.org/gemini)\n> Google图片工具摘要。\n\n图片工具详情。\n");
  // A fresh service isolates this new feed from setup's five-minute feed cache.
  var fixtureDaily=new AiDailyService(stories,companionEvidence,content,json,runtimeEvidence,webEvidence);
  stories.db().update("DELETE FROM companion_daily");
  fixtureDaily.refresh(1);
  for(int budget:List.of(350,650)){
   var result=fixtureDaily.grounding(1,"日报中“"+title+"”相关背景和远程任务管理的后续安排，尤其执行记录怎么导出？",List.of(),budget,date());
   assertEquals(0,result.itemIndex());
   assertTrue(result.text().contains("导出完整执行记录与补丁"));
   assertTrue(PromptBudget.tokens(result.text())<=budget);
  }
  var multiple=fixtureDaily.grounding(1,"比较 Jules 和 Gemini 两条新闻",List.of(),650,date());
  assertEquals(-1,multiple.itemIndex());
  assertTrue(multiple.text().contains("Google Gemini"));
  assertTrue(multiple.text().contains(title));
 }

 @Test void explicitHistoricIssueNeverFallsBackToCurrentAndDoesNotReplacePanelCache()throws Exception{
  var fresh=new AiDailyService(stories,companionEvidence,content,json,runtimeEvidence,webEvidence);
  String old="2026-10-30",latest=date();
  when(content.get("https://daily.juya.uk/markdown/"+old+".md")).thenReturn("### [Archive agent retirement](https://example.org/archive)\n> Prior issue.\n\nUse the CLI to export sessions locally.\n");
  var result=fresh.grounding(1,"请聊2026-10-30期AI日报《Archive agent retirement》：数据怎么导出？",List.of(),350,latest);
  assertEquals(old,result.digest().path("date").asText());assertTrue(result.userNotice().isEmpty());assertTrue(result.text().contains("CLI"));assertTrue(result.sources().stream().anyMatch(v->v.url().contains("/issues/2026-10-30/")));
  assertEquals(latest,fresh.current(1).path("digest").path("date").asText());
  when(content.get("https://daily.juya.uk/markdown/2026-10-29.md")).thenThrow(new IllegalStateException("unavailable"));
  var missing=fresh.grounding(1,"请聊2026-10-29期AI日报的数据导出",List.of(),350,latest);
  assertTrue(missing.text().contains("2026-10-29"));assertTrue(missing.sources().isEmpty());assertTrue(missing.digest().isNull());assertTrue(missing.userNotice().contains("2026-10-29"));assertFalse(missing.userNotice().contains("不要编造"));assertFalse(missing.text().contains("测试新闻"));
 }

 @Test void requestedDateOverridesPreviousIssueButMatchingHistoryAvoidsNetwork()throws Exception{
  var fresh=new AiDailyService(stories,companionEvidence,content,json,runtimeEvidence,webEvidence);
  var digest=json.createObjectNode().put("date","2026-10-28").put("sourceUrl","https://daily.juya.uk/issues/2026-10-28/");
  digest.putArray("items").addObject().put("title","Archive notes").put("link","https://example.org/archive").put("summary","Saved evidence.").put("detail","Saved complete evidence.");
  var meta=json.createObjectNode();meta.set("aiDaily",digest);meta.put("aiDailyItem",0);
  var previous=List.<Map<String,Object>>of(Map.of("context_json",meta.toString()));
  var saved=fresh.grounding(1,"请聊2026-10-28期AI日报原文",previous,350,date());
  assertEquals("2026-10-28",saved.digest().path("date").asText());assertTrue(saved.text().contains("Saved complete"));
  verify(content,never()).get("https://daily.juya.uk/markdown/2026-10-28.md");
  var current=fresh.grounding(1,"请聊"+date()+"期AI日报第1条",previous,350,"2026-10-28");
  assertEquals(date(),current.digest().path("date").asText());assertFalse(current.text().contains("Saved evidence"));
 }

 @Test void compoundDetailFitsActualSearchBudgetWithBothAnswersAndMatchingSourceSnippet()throws Exception{
  var fresh=new AiDailyService(stories,companionEvidence,content,json,runtimeEvidence,webEvidence);
  String title="Anthropic 更名 Claude 订阅方案，逐步取消已验证 Ultra 账户五小时限制";
  when(content.get(contains("/markdown/"))).thenReturn("### ["+title+"](https://example.org/plans)\n> Anthropic 宣布重命名订阅方案，逐步取消限制。\n\n"+"Anthropic 逐步取消五小时限制。".repeat(12)+"Ultra 的周使用额度不变，仍为 Pro 的 10 倍。\n\n验证自动进行，目前尚无主动申请方式，团队正在探索。\n");
  fresh.refresh(1);
  String query="请聊"+date()+"期AI日报《"+title+"》：Ultra的周使用额度是否改变，是Pro的多少倍？账户验证能否主动申请？请附来源，没写的别补。";
  for(int budget:new int[]{350,650}){
   var result=fresh.grounding(1,query,List.of(),budget,date());
   assertTrue(PromptBudget.tokens(result.text())<=budget);
   for(String detail:List.of("Pro 的 10 倍","验证自动进行","尚无主动申请方式")){
    assertTrue(result.text().contains(detail),result.text());assertTrue(result.sources().stream().anyMatch(v->v.snippet().contains(detail)));
   }
  }
 }

 @Test void invalidNaturalIssueAndConflictingIssueIdentitiesFailBeforeReadingSources()throws Exception{
  var fresh=new AiDailyService(stories,companionEvidence,content,json,runtimeEvidence,webEvidence);
  clearInvocations(content);
  var invalid=assertThrows(IllegalArgumentException.class,()->fresh.grounding(1,"2026-02-30期《测试新闻二》",List.of(),350));
  assertEquals("日报期次无效",invalid.getMessage());
  assertThrows(IllegalArgumentException.class,()->fresh.grounding(1,"2026-10-29期AI日报与2026-10-30期《测试新闻二》",List.of(),350));
  verifyNoInteractions(content);
 }

 @Test void naturalTitleAndTwoUnselectedFollowupsPersistIdentityThroughChatApiWithSearchOn()throws Exception{
  doReturn(true).when(skills).enabled(1,"ai-daily-juya");
  String c=chapter();int role=((Number)stories.owned(1,c).get("role_id")).intValue();
  for(String question:List.of("请按"+date()+"期《测试新闻二》回答：具体改动是什么？","这条的具体改动能再确认吗？","那条还有哪些没写出的限制？")){
   String response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",c,"roleId",role,"message",question,"search",true));
   assertTrue(response.contains("event:done"));assertTrue(response.contains("https://example.org/two"));
   String system=prompts.get(prompts.size()-1).get(0).get("content").toString();
   assertTrue(system.contains("第二条专属详情"));assertFalse(system.contains("第一条正文"));
   var saved=json.readTree(stories.db().queryForObject("SELECT context_json FROM message_context WHERE conversation_id=? ORDER BY history_id DESC LIMIT 1",String.class,c));
   assertEquals(date(),saved.path("aiDaily").path("date").asText());assertEquals(1,saved.path("aiDailyItem").asInt(-1));
  }
  verify(search,never()).search(anyInt(),anyString(),anyList(),any());
 }

 @Test void naturalIssueTitleAndTwoFollowupsRetainStructuredIdentityWithoutSelection()throws Exception{
  var fresh=new AiDailyService(stories,companionEvidence,content,json,runtimeEvidence,webEvidence);
  var first=fresh.grounding(1,"请按"+date()+"期《测试新闻二》回答：具体改动是什么？",List.of(),350);
  assertEquals(date(),first.digest().path("date").asText());assertEquals(1,first.itemIndex());
  assertTrue(first.text().contains("第二条专属详情"));
  for(String q:List.of("这条的具体改动能再确认吗？","那条还有哪些没写出的限制？")){
   var meta=json.createObjectNode();meta.set("aiDaily",first.digest());meta.put("aiDailyItem",first.itemIndex());
   first=fresh.grounding(1,q,List.of(Map.of("context_json",meta.toString())),350);
   assertEquals(1,first.itemIndex());assertTrue(first.text().contains("第二条专属详情"));
  }
  var meta=json.createObjectNode();meta.set("aiDaily",first.digest());meta.put("aiDailyItem",1);
  var history=List.<Map<String,Object>>of(Map.of("context_json",meta.toString()));
  for(String unrelated:List.of("换个话题：这条街的天气如何？","不谈这条，另一个新问题：周末去哪里散步？","换话题，另一个新问题：今天杭州天气如何？"))assertTrue(fresh.grounding(1,unrelated,history,350).text().isEmpty());
  assertTrue(fresh.grounding(1,"2026-10-30是星期几？",history,350).text().isEmpty());
  assertTrue(fresh.grounding(1,"这条有什么限制？",List.of(history.get(0),Map.of("content","普通新话题"),Map.of("content","普通回答")),350).text().isEmpty());
 }

 @Test void naturalArchivedIssueIsVerifiedAgainstFixedDailySourceAndOtherJournalsStaySeparate()throws Exception{
  var fresh=new AiDailyService(stories,companionEvidence,content,json,runtimeEvidence,webEvidence);
  String old="2026-10-30",title="Orion 工作台停止服务公告";
  when(content.get("https://daily.juya.uk/markdown/"+old+".md")).thenReturn("### ["+title+"](https://example.org/orion)\n> 旧期公告。\n\n用户可以从设置导出ZIP归档。\n");
  var result=fresh.grounding(1,"请查"+old+"期《"+title+"》：如何导出？",List.of(),350);
  assertEquals(old,result.digest().path("date").asText());assertEquals(0,result.itemIndex());assertTrue(result.text().contains("ZIP"));
  assertEquals(date(),fresh.current(1).path("digest").path("date").asText());
  assertTrue(fresh.grounding(1,"请查"+old+"期《世界地理专刊》",List.of(),350).text().isEmpty());
  when(content.get("https://daily.juya.uk/markdown/2026-10-29.md")).thenThrow(new IllegalStateException("unavailable"));
  var missing=fresh.grounding(1,"请查2026-10-29期《测试新闻二》",List.of(),350);
  assertTrue(missing.digest().isNull());assertTrue(missing.sources().isEmpty());assertTrue(missing.userNotice().contains("2026-10-29"));
  verify(content,never()).get(contains("世界地理"));
 }

 @Test void fullKnownTitleRoutesBeforeIntentGateAndNewIssueWins()throws Exception{
  var fresh=new AiDailyService(stories,companionEvidence,content,json,runtimeEvidence,webEvidence);
  var old=json.createObjectNode().put("date","2026-10-28");old.putArray("items").addObject().put("title","旧刊条目标题");
  var meta=json.createObjectNode();meta.set("aiDaily",old);meta.put("aiDailyItem",0);
  var history=List.<Map<String,Object>>of(Map.of("context_json",meta.toString()));
  var result=fresh.grounding(1,"测试新闻二的具体改动是什么？",history,350);
  assertEquals(date(),result.digest().path("date").asText());assertEquals(1,result.itemIndex());
  var explicit=fresh.grounding(1,"请按"+date()+"期《测试新闻二》回答",history,350);
  assertEquals(date(),explicit.digest().path("date").asText());
 }

 @Test void unavailableDailyNoticesAreUserFacingAndUnrelatedChatHasNone()throws Exception{
  var fresh=new AiDailyService(stories,companionEvidence,content,json,runtimeEvidence,webEvidence);
  assertTrue(fresh.grounding(1,"普通聊天",List.of(),350).userNotice().isEmpty());
  call("/api/v1/me/companion",HttpMethod.PUT,Map.of("dailyEnabled",false));
  var disabled=fresh.grounding(1,"聊AI日报",List.of(),350);
  assertTrue(disabled.userNotice().contains("尚未开启"));assertTrue(disabled.sources().isEmpty());
  assertFalse(disabled.userNotice().contains("请说明"));
 }

 @Test void extraExplicitPageFailureDoesNotInvalidateAvailableDailyOriginal()throws Exception{
  String c=chapter(),question="请展开日报第2条，解释具体改动，并读取 https://example.org/extra 给来源。";
  when(search.search(eq(1),eq(question),anyList(),any())).thenReturn(new SearchRag.Result("blocked",List.of(),List.of(),"额外网页读取失败：无可用正文。","额外网页受阻"));
  int role=((Number)stories.owned(1,c).get("role_id")).intValue();
  String response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",c,"roleId",role,"message",question,"search",true));
  assertTrue(response.contains("event:done"));verify(search).search(eq(1),eq(question),anyList(),any());
  String system=prompts.get(0).get(0).get("content").toString();
  assertTrue(system.contains("第二条专属详情"));assertTrue(system.contains("https://example.org/two"));
  assertTrue(system.contains("以下读取状态不适用于上面的日报原文"));assertTrue(system.contains("额外网页读取失败"));
  assertTrue(system.indexOf("第二条专属详情")<system.indexOf("额外网页读取失败"));
  assertFalse(system.contains("第一条正文"));assertTrue(PromptBudget.tokens(system)<=2400);
  assertEquals(question,prompts.get(0).get(prompts.get(0).size()-1).get("content"));
 }

 @Test void explicitOldIssueInMessageOverridesCurrentDraftSelectionThroughHttp()throws Exception{
  String old=LocalDate.parse(date()).minusDays(2).toString();
  when(content.get("https://daily.juya.uk/markdown/"+old+".md")).thenReturn("### [Archive agent retirement](https://example.org/archive)\n> Prior issue.\n\nUse the CLI to export all sessions locally.\n");
  String c=chapter(),question="请聊"+old+"期AI日报《Archive agent retirement》：数据怎么导出？";
  int role=((Number)stories.owned(1,c).get("role_id")).intValue();
  String response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",c,"roleId",role,"message",question,"dailyIssueDate",date(),"search",true));
  assertTrue(response.contains("event:done"));String system=prompts.get(0).get(0).get("content").toString();
  assertTrue(system.contains("期刊日期："+old));assertTrue(system.contains("export all sessions"));assertFalse(system.contains("测试新闻"));
  var saved=json.readTree(stories.db().queryForObject("SELECT context_json FROM message_context WHERE conversation_id=? ORDER BY history_id DESC LIMIT 1",String.class,c));
  assertEquals(old,saved.path("aiDaily").path("date").asText());verify(search,never()).search(anyInt(),anyString(),anyList(),any());
 }
}
