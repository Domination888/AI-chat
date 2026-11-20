package org.example.aichat.release;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
class DailyIssueIdentityTest {
 @Test void identityUsesSavedIssueAndDoesNotDropCompoundRequest()throws Exception{
  var digest=new ObjectMapper().readTree("{\"date\":\"2026-10-30\",\"sourceUrl\":\"https://daily.juya.uk/issues/2026-10-30/\",\"items\":[]}");
  var context=new AiDailyService.Context("evidence",List.of(new SearchRag.Source("issue","https://daily.juya.uk/issues/2026-10-30/","",List.of(),0,true)),digest,-1);
  assertTrue(AiDailyService.issueIdentityAnswer("哪一期？",context).orElseThrow().contains("2026-10-30"));
  assertTrue(AiDailyService.issueIdentityAnswer("你刚才那条Jules关停消息来自哪一期？请只依据同一期日报说明原文还说了什么，并给来源。",context).isEmpty());
  assertTrue(AiDailyService.issueIdentityAnswer("这条报道发布日期是什么？",context).isEmpty());
 }
 @Test void compoundQuestionUsesAdjacentOriginalDigestAndNamedArticleWithoutFetchingLatest()throws Exception{
  var json=new ObjectMapper();var companion=mock(CompanionService.class);when(companion.settings(8)).thenReturn(json.createObjectNode().put("dailyEnabled",true));
  var content=mock(PublicContent.class);var web=mock(WebAccess.class);
  var store=mock(StoryStore.class);var db=mock(org.springframework.jdbc.core.JdbcTemplate.class);when(store.db()).thenReturn(db);when(db.queryForList(anyString(),eq(8))).thenReturn(List.of());
  var daily=new AiDailyService(store,companion,content,json,mock(RuntimeMode.class),web);
  var digest=json.readTree("{\"date\":\"2026-10-30\",\"sourceUrl\":\"https://daily.juya.uk/issues/2026-10-30/\",\"items\":[{\"title\":\"Other article\",\"link\":\"https://example.org/other\",\"summary\":\"unrelated\"},{\"title\":\"Jules service shutdown\",\"link\":\"https://example.org/jules\",\"summary\":\"shutdown evidence\",\"detail\":\"export script evidence\"}]}");
  var saved=json.createObjectNode();saved.set("aiDaily",digest);saved.put("aiDailyItem",-1);
  var previous=List.<Map<String,Object>>of(Map.of("sender","assistant","content","news","context_json",saved.toString()));
  var context=daily.grounding(8,"你刚才那条Jules关停消息来自哪一期？请只依据同一期日报说明原文还说了什么，并给来源。",previous,650);
  assertEquals("2026-10-30",context.digest().path("date").asText());assertEquals(1,context.itemIndex());assertTrue(context.text().contains("export script evidence"));assertFalse(context.text().contains("unrelated"));assertEquals(2,context.sources().size());verifyNoInteractions(content,web);
  assertTrue(daily.grounding(8,"发布日期是什么？",previous,650).text().isBlank());
 }
 @Test void explicitlySelectedOldIssueSurvivesEditedQuestionAndTimezone()throws Exception{
  var json=new ObjectMapper();var companion=mock(CompanionService.class);when(companion.settings(8)).thenReturn(json.createObjectNode().put("dailyEnabled",true).put("timezone","Asia/Shanghai"));
  var store=mock(StoryStore.class);var db=mock(org.springframework.jdbc.core.JdbcTemplate.class);when(store.db()).thenReturn(db);
  String digest="{\"date\":\"2026-10-30\",\"sourceUrl\":\"https://daily.juya.uk/issues/2026-10-30/\",\"items\":[{\"title\":\"Jules shutdown\",\"link\":\"https://example.org/jules\",\"summary\":\"shutdown summary\",\"detail\":\"export evidence\"}]}";
  when(db.queryForList(anyString(),eq(8))).thenReturn(List.of(Map.of("digest_json",digest,"read_at","","fetched_at","2026-10-31T00:00:00Z")));
  var content=mock(PublicContent.class);var daily=new AiDailyService(store,companion,content,json,mock(RuntimeMode.class),mock(WebAccess.class));
  var answer=daily.grounding(8,"Jules shutdown来自哪一期？同一期原文还说了什么并给来源",List.of(),650,"2026-10-30");
  assertEquals("2026-10-30",answer.digest().path("date").asText());assertTrue(answer.text().contains("export evidence"));assertEquals(2,answer.sources().size());verifyNoInteractions(content);
  assertTrue(daily.grounding(8,"这期说了什么",List.of(),650,"2026-10-29").sources().isEmpty());verifyNoInteractions(content);
 }
}
