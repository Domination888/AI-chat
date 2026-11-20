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
import java.util.*;
import java.time.*;
import org.example.aichat.rp.RpState;

class CompanionDailyIntegrationTest extends CompanionIntegrationFixture {

  @Test void dailyRequiresOptInParsesRealFormatAndKeepsReadState()throws Exception{
    assertEquals(400,call("/api/v1/me/ai-daily/refresh",HttpMethod.POST,Map.of()).getStatusCode().value());verifyNoInteractions(content);
    result(call("/api/v1/me/companion",HttpMethod.PUT,Map.of("dailyEnabled",true)));var d=result(call("/api/v1/me/ai-daily/refresh",HttpMethod.POST,Map.of()));assertEquals(2,d.path("digest").path("items").size());assertTrue(d.path("unread").asBoolean());assertTrue(daily.context(1,"看看 AI 日报").contains("https://example.org/model"));
    result(call("/api/v1/me/ai-daily/read",HttpMethod.POST,Map.of()));d=result(call("/api/v1/me/ai-daily/refresh",HttpMethod.POST,Map.of()));assertFalse(d.path("unread").asBoolean());assertTrue(daily.current(2).path("digest").isNull());
    when(content.get(anyString())).thenThrow(new java.io.IOException("offline"));var restarted=new AiDailyService(stories,companion,content,json,mode,web);assertThrows(Exception.class,()->restarted.refresh(1));assertEquals(2,restarted.current(1).path("digest").path("items").size());
  }

  @Test void dailySourcesSurviveModelOmissionsReloadAndNumberedFollowups()throws Exception{
    companion.save(1,json.valueToTree(Map.of("dailyEnabled",true)));String conv=chapter("companion");
    var first=dailyReplyContext(conv,"一起聊聊 AI 日报",false);assertEquals(3,first.path("sources").size());
    assertEquals("https://example.org/model",first.path("sources").get(1).path("url").asText());
    assertEquals("https://example.org/tool",first.path("sources").get(2).path("url").asText());
    // A subsequent refresh must not silently change which issue '第2条' refers to.
    var replacement=first.path("aiDaily").deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)replacement.path("items").get(1)).put("title","另一刊的条目").put("link","https://example.org/other-issue");
    stories.db().update("UPDATE companion_daily SET digest_json=? WHERE user_id=1",replacement.toString());
    var second=dailyReplyContext(conv,"第2条展开讲讲",false);assertEquals(2,second.path("sources").size());assertEquals(1,second.path("aiDailyItem").asInt());
    assertEquals("https://example.org/tool",second.path("sources").get(1).path("url").asText());
    var third=dailyReplyContext(conv,"来源呢",false);assertEquals(second.path("sources"),third.path("sources"));
    var capture=org.mockito.ArgumentCaptor.forClass(List.class);verify(models,times(3)).stream(any(),capture.capture(),any(),any());
    assertTrue(capture.getValue().get(0).toString().contains("第二条正文"));assertFalse(capture.getValue().get(0).toString().contains("另一刊的条目"));
    assertFalse(dailyReplyContext(conv,"聊聊晚饭吃什么",false).has("sources"));
    assertFalse(dailyReplyContext(conv,"来源呢",false).has("sources"));
  }

  @Test void dailyDiscussionExcludesEarlierChatterOnlyInTheModelRequest()throws Exception{
    companion.save(1,json.valueToTree(Map.of("dailyEnabled",true)));String conv=chapter("companion"),opening="我正想问问你，这周末的短篇集该挑哪本呢？";
    long id=stories.message(conv,"assistant",opening);stories.db().update("INSERT INTO message_context(history_id,conversation_id,context_json) VALUES(?,?,?)",id,conv,"{\"isProactive\":true}");
    dailyReplyContext(conv,"我们聊聊 AI 日报，请挑几条看点",false);
    var capture=org.mockito.ArgumentCaptor.forClass(List.class);verify(models).stream(any(),capture.capture(),any(),any());
    assertFalse(capture.getValue().toString().contains(opening));
    assertEquals(opening,stories.db().queryForObject("SELECT content FROM history WHERE id=?",String.class,id));
    clearInvocations(models);dailyReplyContext(conv,"讲个笑话",false);verify(models).stream(any(),capture.capture(),any(),any());
    assertEquals(opening,((Map<?,?>)capture.getValue().get(1)).get("content"));
  }

  @Test void newTasksRequestingDetailsOrSourcesDoNotInheritDailyGrounding()throws Exception{
    companion.save(1,json.valueToTree(Map.of("dailyEnabled",true)));daily.refresh(1);
    String context=json.writeValueAsString(Map.of("aiDaily",daily.current(1).path("digest"),"aiDailyItem",0));
    var previous=List.<Map<String,Object>>of(Map.of("sender","assistant","content","今天有两条消息。","context_json",context));
    for(String message:List.of("请给我写一个至少三千字的原创书店冒险故事，分十段展开，描写细致。","请联网搜索 Python 官网的入门教程，给我一个官方学习入口，并附来源链接。","请详细介绍杭州的天气并给出来源。")){
      var grounding=daily.grounding(1,message,previous,650);assertTrue(grounding.sources().isEmpty(),message);assertTrue(grounding.text().isEmpty(),message);
    }
    for(String message:List.of("来源呢","请展开讲讲","这条新闻的原文呢","第2条展开讲讲","请展开第2条，解释具体改动，并给来源。","请展开日报第2条，解释具体改动，并给来源。"))assertFalse(daily.grounding(1,message,previous,650).sources().isEmpty(),message);
    var numbered=daily.grounding(1,"请展开日报第2条，解释具体改动，并给来源。",List.of(),650);
    assertEquals(1,numbered.itemIndex());assertTrue(numbered.text().contains("第二条正文"));assertEquals("https://example.org/tool",numbered.sources().get(1).url());
  }

  @Test void dailyAndSearchSourcesMergeWithoutDuplicateUrls()throws Exception{
    companion.save(1,json.valueToTree(Map.of("dailyEnabled",true,"searchEnabled",true)));daily.refresh(1);
    var digest=daily.current(1).path("digest");((com.fasterxml.jackson.databind.node.ObjectNode)digest.path("items").get(0)).put("link","https://1.1.1.1/docs?topic=photo");
    stories.db().update("UPDATE companion_daily SET digest_json=? WHERE user_id=1",digest.toString());
    when(web.get(contains("/search?"),eq(true))).thenReturn("{\"results\":[{\"title\":\"模型官方说明\",\"url\":\"https://1.1.1.1/docs?topic=photo\",\"content\":\"模型最新功能\"}]}");
    when(web.get(contains("/docs?"),eq(false))).thenReturn("<main>模型支持本地运行。</main>");
    var context=dailyReplyContext(chapter("companion"),"AI 日报第1条的来源，也搜索核对一下",true);
    assertEquals(2,context.path("sources").size());var urls=new HashSet<String>();context.path("sources").forEach(source->assertTrue(urls.add(source.path("url").asText())));
    assertTrue(context.has("aiDaily"));
  }

  @Test void dailySourceCardsOnlyDescribeItemsIncludedWithinThePromptBudget()throws Exception{
    companion.save(1,json.valueToTree(Map.of("dailyEnabled",true)));daily.refresh(1);
    var digest=(com.fasterxml.jackson.databind.node.ObjectNode)daily.current(1).path("digest");var items=digest.putArray("items");
    for(int i=0;i<20;i++)items.addObject().put("title","资料条目"+i).put("link","https://example.org/item/"+i).put("summary","这是本条目的内容。".repeat(30)).put("detail","详细内容");
    stories.db().update("UPDATE companion_daily SET digest_json=? WHERE user_id=1",digest.toString());
    var context=daily.grounding(1,"AI 日报",List.of(),350);assertTrue(PromptBudget.tokens(context.text())<=350);
    assertTrue(context.sources().size()>1);assertTrue(context.sources().size()<21);
    context.sources().forEach(source->assertTrue(context.text().contains(source.url()),source.url()));
    assertEquals(20,context.digest().path("items").size());
  }

  @Test void unrelatedDisabledOrUnavailableDailyDoesNotAttachSources()throws Exception{
    assertTrue(daily.grounding(1,"AI 日报",List.of(),650).sources().isEmpty());verifyNoInteractions(content);
    companion.save(1,json.valueToTree(Map.of("dailyEnabled",true)));
    assertTrue(daily.grounding(1,"今天想出去散步",List.of(),650).text().isEmpty());
    when(content.get(anyString())).thenThrow(new java.io.IOException("unavailable"));
    var fresh=new AiDailyService(stories,companion,content,json,mode,web);var unavailable=fresh.grounding(1,"AI 日报",List.of(),650);
    assertTrue(unavailable.sources().isEmpty());assertTrue(unavailable.text().contains("暂不可用"));
  }

  @Test void schedulerRespectsSwitchAndDoesNotNotifyAgainForReadIssue()throws Exception{
    var scheduler=new AiDailyService(stories,companion,content,json,mode,web);scheduler.scheduled();verifyNoInteractions(content);
    companion.save(1,json.valueToTree(Map.of("dailyEnabled",true,"dailyScheduleEnabled",true,"dailyTime","00:00")));scheduler.scheduled();assertTrue(scheduler.current(1).path("unread").asBoolean());scheduler.read(1);clearInvocations(content);scheduler.scheduled();verifyNoInteractions(content);assertFalse(scheduler.current(1).path("unread").asBoolean());
  }

  @Test void inFlightDailyFetchCannotRecreateClearedUserData()throws Exception{
    companion.save(1,json.valueToTree(Map.of("dailyEnabled",true)));
    when(content.get(contains("rss.xml"))).thenAnswer(call->{stories.db().update("DELETE FROM companion_setting WHERE user_id=1");return rss();});
    var fresh=new AiDailyService(stories,companion,content,json,mode,web);fresh.refresh(1);
    assertTrue(fresh.current(1).path("digest").isNull());
  }
}
