package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;

class SearchRetrievalTest {
 final ObjectMapper json=new ObjectMapper();final CompanionService companion=mock(CompanionService.class);final RuntimeMode mode=mock(RuntimeMode.class);final WebAccess web=mock(WebAccess.class);final OpenAiTransport models=mock(OpenAiTransport.class);
 SearchRag rag() {when(companion.settings(1)).thenReturn(json.createObjectNode().put("searchEnabled",true));when(mode.value("search-url","http://127.0.0.1:8888")).thenReturn("http://127.0.0.1:8888");return new SearchRag(companion,mode,web,json,models);}
 String contents(){return "<html><title>Quasar Learning Manual</title><main><h1>Learning Manual</h1><div class='toc'><ol><li><a href='intro.html'>1. Getting oriented</a></li><li><a href='run.html'>2. Running the engine</a><ul><li>2.1. Arguments</li><li>2.2. Environment</li></ul></li><li><a href='basics.html'>3. Expressions and values</a><ul><li>3.1. Arithmetic</li></ul></li><li><a href='beyond.html'>4. Further study</a></li></ol></div><p>General introduction.</p></main></html>";}
 @Test void explicitPublicUrlIsReadDirectlyAndKeepsTheRequestedOrderedContents()throws Exception{
  String url="https://1.1.1.1/manual/index.html";when(web.get(url,false)).thenReturn(contents());var result=rag().search(1,"请打开 "+url+" 核对目录，只列前三章。",List.of(),p->{});
  assertEquals("ok",result.status());assertEquals(List.of(url),result.plannedQueries());assertEquals(1,result.sources().size());assertTrue(result.sources().get(0).pageRead());assertEquals("Quasar Learning Manual",result.sources().get(0).title());
  String context=result.contextText();assertTrue(context.contains("不是章节编号"));assertTrue(context.indexOf("1. Getting oriented")<context.indexOf("2. Running the engine"));assertTrue(context.indexOf("2. Running the engine")<context.indexOf("3. Expressions and values"));assertTrue(context.contains("https://1.1.1.1/manual/basics.html"));assertTrue(PromptBudget.tokens(context)<=2200);verify(web,never()).get(anyString(),eq(true));verifyNoInteractions(models);
 }
 @Test void explicitUnsafeAddressIsRejectedBeforeReadingAndNeverFallsBackToSearch()throws Exception{
  var result=rag().search(1,"请读取 https://127.0.0.1/secret",List.of(),p->{});assertEquals("unavailable",result.status());assertTrue(result.sources().isEmpty());assertTrue(result.reason().contains("地址校验未通过"));verifyNoInteractions(web);
 }
 @Test void unavailableExplicitPageDoesNotBecomeAFabricatedSourceOrUnrelatedSearch()throws Exception{
  when(web.get("https://1.1.1.1/missing",false)).thenThrow(new java.io.IOException("unavailable"));var result=rag().search(1,"请读取 https://1.1.1.1/missing",List.of(),p->{});assertEquals("unavailable",result.status());assertTrue(result.sources().isEmpty());assertTrue(result.reason().contains("不能确认")||result.reason().contains("无法确认"));verify(web,never()).get(anyString(),eq(true));
 }
 @Test void relevantSixthResultWinsBeforeThePageReadLimit()throws Exception{
  var response=json.createObjectNode();var items=response.putArray("results");for(int i=0;i<5;i++)items.addObject().put("title","Quasar home "+i).put("url","https://1.1.1.1/home/"+i).put("content","Quasar home page");
  items.addObject().put("title","Quasar Tutorial").put("url","https://1.1.1.1/tutorial/index.html").put("content","Quasar tutorial and guide");when(web.get(anyString(),eq(true))).thenReturn(response.toString());when(web.get("https://1.1.1.1/tutorial/index.html",false)).thenReturn(contents());
  var result=rag().search(1,"Quasar 教程的前三章",List.of(),p->{});assertEquals(1,result.sources().size());assertEquals("https://1.1.1.1/tutorial/index.html",result.sources().get(0).url());assertTrue(result.contextText().contains("3. Expressions and values"));verify(web,never()).get(startsWith("https://1.1.1.1/home/"),eq(false));
 }
 @Test void unrelatedResultsAreNotRescuedByEmbeddingsOrMisreportedAsRateLimits()throws Exception{
  when(web.get(anyString(),eq(true))).thenReturn("{\"results\":[{\"title\":\"SharePoint support\",\"url\":\"https://1.1.1.1/sharepoint\",\"content\":\"Microsoft Azure Windows\"}],\"unresponsive_engines\":[[\"brave\",\"too many requests\"]]}");when(models.embedding(anyString())).thenReturn(new float[]{1,0});
  var result=rag().search(1,"Quasar 官方教程",List.of(),p->{});assertEquals("empty",result.status());assertTrue(result.sources().isEmpty());assertTrue(result.reason().contains("不够相关"));assertFalse(result.reason().contains("brave"));verify(web,never()).get(anyString(),eq(false));
 }
 @Test void pageReadAndContextBudgetsStayBounded()throws Exception{
  var response=json.createObjectNode();var items=response.putArray("results");for(int i=0;i<60;i++)items.addObject().put("title","Quasar tutorial "+i).put("url","https://1.1.1.1/tutorial/"+i).put("content","Quasar tutorial");when(web.get(anyString(),eq(true))).thenReturn(response.toString());when(web.get(anyString(),eq(false))).thenReturn(contents()+"<p>"+"Large paragraph. ".repeat(10000)+"</p>");
  var result=rag().search(1,"Quasar tutorial chapters",List.of(),p->{});verify(web,times(5)).get(anyString(),eq(false));assertEquals(3,result.sources().size());assertTrue(PromptBudget.tokens(result.contextText())<=2200);for(var source:result.sources())assertTrue(PromptBudget.tokens(source.excerpts().get(0))<=1800);
 }
 @Test void urlExtractionHandlesSurroundingChinesePunctuationAndLimitsExplicitReads(){assertEquals(List.of("https://1.1.1.1/a","https://1.1.1.1/b?q=x"),SearchTerms.explicitUrls("打开（https://1.1.1.1/a），然后读 https://1.1.1.1/b?q=x。"));}
 @Test void htmlOrderedListNumbersAndLinksArePreservedWithoutInventingChapterNumbers()throws Exception{
  String url="https://1.1.1.1/guide";when(web.get(url,false)).thenReturn("<title>Handbook</title><main><ol start='5'><li><a href='/fifth'>Actual fifth</a></li><li value='9'><a href='/ninth'>Actual ninth</a></li></ol></main>");
  var result=rag().search(1,"请打开 "+url+" 查看目录前两章",List.of(),p->{});assertTrue(result.contextText().contains("5. Actual fifth"));assertTrue(result.contextText().contains("9. Actual ninth"));assertFalse(result.contextText().contains("1. Actual fifth"));
 }

 @Test void explicitPlainTextPageStillSuppliesReadableEvidenceWithinBudget()throws Exception{
  when(web.get("https://1.1.1.1/notes.txt",false)).thenReturn("This is a plain text source with a verified statement. ".repeat(200));var result=rag().search(1,"读取 https://1.1.1.1/notes.txt",List.of(),p->{});assertTrue(result.sources().get(0).pageRead());assertTrue(result.contextText().contains("verified statement"));assertTrue(PromptBudget.tokens(result.contextText())<=2200);
 }

 @Test void originalComparisonRequestUsesOneBoundedFocusedSearchAndReadsItsActualSource()throws Exception{
  String original="请联网查看 Vue 官方文档：ref 和 reactive 有什么区别？给我两个适用场景，并附上官方来源。";
  var wireQueries=new ArrayList<String>();
  when(web.get(anyString(),eq(true))).thenAnswer(call->{String url=call.getArgument(0);wireQueries.add(java.net.URLDecoder.decode(url.substring(url.indexOf("&q=")+3),java.nio.charset.StandardCharsets.UTF_8));
   return wireQueries.size()==1?"{\"results\":[{\"title\":\"Hotel Albuquerque\",\"url\":\"http://1.1.1.1/hotel\",\"content\":\"Book rooms\"}]}":"{\"results\":[{\"title\":\"Vue ref and reactive API\",\"url\":\"https://1.1.1.1/verified-api\",\"content\":\"Vue ref and reactive documentation\"}]}";});
  when(web.get("https://1.1.1.1/verified-api",false)).thenReturn("<title>Verified test API</title><main><p>ref and reactive fixture documentation: a verified paragraph.</p></main>");
  var progress=new ArrayList<Map<String,Object>>();var result=rag().search(1,original,List.of(),progress::add);
  assertEquals("ok",result.status());assertEquals(wireQueries,result.plannedQueries());assertEquals(2,wireQueries.size());
  for(String query:wireQueries){assertTrue(query.contains("Vue"));assertTrue(query.contains("ref"));assertTrue(query.contains("reactive"));assertFalse(query.contains("给我"));assertFalse(query.contains("适用场景"));assertFalse(query.contains("site:"));}
  assertEquals(1,result.sources().size());assertTrue(result.sources().get(0).pageRead());assertEquals("https://1.1.1.1/verified-api",result.sources().get(0).url());assertTrue(result.contextText().contains("verified paragraph"));
  assertEquals(2,progress.stream().filter(p->"searching".equals(p.get("phase"))).count());verify(web,times(2)).get(anyString(),eq(true));verify(web,times(1)).get(anyString(),eq(false));
 }
 @Test void relevantFirstSearchDoesNotTriggerSpeculativeAdditionalQueries()throws Exception{
  when(web.get(anyString(),eq(true))).thenReturn("{\"results\":[{\"title\":\"Vue ref reactive\",\"url\":\"https://1.1.1.1/api\",\"content\":\"Vue ref reactive documentation\"}]}");
  when(web.get("https://1.1.1.1/api",false)).thenReturn("<main>Vue ref reactive fixture.</main>");
  var result=rag().search(1,"请联网查看 Vue 的 ref 和 reactive 有什么区别？给我两个场景。",List.of(),p->{});
  assertEquals("ok",result.status());assertEquals(1,result.plannedQueries().size());verify(web,times(1)).get(anyString(),eq(true));
 }
 @Test void EntityOnlyHomepageIsNotEvidenceForTheRequestedMultiSubjectComparison()throws Exception{
  when(web.get(anyString(),eq(true))).thenReturn("{\"results\":[{\"title\":\"Vue home\",\"url\":\"https://1.1.1.1/home\",\"content\":\"A progressive framework\"}]}");
  var result=rag().search(1,"Vue 官方文档 ref reactive 区别",List.of(),p->{});
  assertEquals("empty",result.status());assertTrue(result.sources().isEmpty());assertEquals(2,result.plannedQueries().size());
  verify(web,never()).get(anyString(),eq(false));verifyNoInteractions(models);
 }
 @Test void allEnginesUnavailableDoesNotCauseAnExtraQueryOrBecomeInventedEvidence()throws Exception{
  when(web.get(anyString(),eq(true))).thenReturn("{\"results\":[],\"unresponsive_engines\":[[\"engine\",\"CAPTCHA\"]]}");
  var result=rag().search(1,"Vue 官方文档 ref reactive 区别",List.of(),p->{});
  assertEquals("unavailable",result.status());assertTrue(result.sources().isEmpty());assertTrue(result.reason().contains("验证码"));assertEquals(1,result.plannedQueries().size());
  verify(web,times(1)).get(anyString(),eq(true));verify(web,never()).get(anyString(),eq(false));verifyNoInteractions(models);
 }
 @Test void readableBlogDoesNotSuppressOfficialFocusedSearchOrBecomeAnOfficialSource()throws Exception{
  var wireQueries=new ArrayList<String>();
  when(web.get(anyString(),eq(true))).thenAnswer(call->{String url=call.getArgument(0);wireQueries.add(java.net.URLDecoder.decode(url.substring(url.indexOf("&q=")+3),java.nio.charset.StandardCharsets.UTF_8));
   return "{\"results\":[{\"title\":\"Vue.js 官方 ref reactive 教程\",\"url\":\"https://1.1.1.1/blog\",\"content\":\"Vue ref reactive 官方文档详解\"}]}";});
  when(web.get("https://1.1.1.1/blog",false)).thenReturn("<title>Vue.js 自称官方教程</title><main>Vue ref reactive third-party fixture.</main>");
  var result=rag().search(1,"请联网查看 Vue 官方文档：ref 和 reactive 有什么区别？给我两个场景。",List.of(),p->{});
  assertEquals("ok",result.status());assertEquals(2,wireQueries.size());assertEquals(wireQueries,result.plannedQueries());
  assertTrue(wireQueries.get(1).contains("official documentation"));assertFalse(wireQueries.get(1).contains("site:"));
  assertTrue(result.reason().contains("非官方"));assertTrue(result.contextText().contains("回答开头必须明确"));assertTrue(result.contextText().contains("网页自称官方不是认证依据"));
  assertEquals(1,result.sources().size());assertTrue(result.sources().get(0).pageRead());verify(web,times(2)).get(anyString(),eq(true));
 }
 @Test void optionalOfficialSearchFailureKeepsReadableThirdPartyEvidenceClearlyLabelled()throws Exception{
  when(web.get(anyString(),eq(true))).thenReturn("{\"results\":[{\"title\":\"Vue ref reactive blog\",\"url\":\"https://1.1.1.1/blog\",\"content\":\"Vue ref reactive\"}]}").thenThrow(new java.io.IOException("search unavailable"));
  when(web.get("https://1.1.1.1/blog",false)).thenReturn("<main>Vue ref reactive third-party evidence.</main>");
  var result=rag().search(1,"Vue 官方文档 ref reactive 区别",List.of(),p->{});
  assertEquals("ok",result.status());assertEquals(2,result.plannedQueries().size());assertEquals(1,result.sources().size());assertTrue(result.reason().contains("非官方"));
  verify(web,times(2)).get(anyString(),eq(true));verify(web,times(1)).get(anyString(),eq(false));
 }

 @Test void unrelatedChineseResultsAreEmptyRatherThanBlockedAndNeverRead()throws Exception{
  when(web.get(anyString(),eq(true))).thenReturn("{\"results\":[{\"title\":\"请字笔画笔顺\",\"url\":\"https://127.0.0.1/请.html\",\"content\":\"请是什么意思，怎么写\"}],\"unresponsive_engines\":[[\"engine\",\"429 too many requests\"]]}");
  var result=rag().search(1,"请现在联网查询上海今天的天气，给出实际搜索来源",List.of(),p->{});
  assertEquals("empty",result.status());assertTrue(result.sources().isEmpty());verify(web,never()).get(anyString(),eq(false));verifyNoInteractions(models);
 }
 @Test void chineseRelevantSnippetSurvivesPageFailureWhileQuestionOnlyResultIsExcluded()throws Exception{
  when(web.get(anyString(),eq(true))).thenReturn("{\"results\":[{\"title\":\"什么是花瓶\",\"url\":\"https://1.1.1.1/film\",\"content\":\"有什么含义\"},{\"title\":\"杭州天气预报\",\"url\":\"https://1.1.1.1/report\",\"content\":\"杭州今天降雨，天气变化\"}]}");
  when(web.get("https://1.1.1.1/report",false)).thenThrow(new java.io.IOException("unavailable"));
  var result=rag().search(1,"今天杭州天气有什么变化",List.of(),p->{});
  assertEquals("ok",result.status());assertEquals(1,result.sources().size());assertEquals("https://1.1.1.1/report",result.sources().get(0).url());assertFalse(result.sources().get(0).pageRead());verify(web,never()).get("https://1.1.1.1/film",false);
 }
 @Test void overviewRetainsSeveralSubstantiveChangesBeyondLongNavigation()throws Exception{
  String url="https://1.1.1.1/releases/current.html";
  String navigation="<li>current.html release navigation</li>".repeat(180);
  when(web.get(url,false)).thenReturn("<main><div class='toctree-wrapper'><ul>"+navigation+"</ul></div><section><h2>Overview</h2><p>The release introduces these changes.</p><ul><li>The scheduler now supports priority queues.</li><li>The storage engine supports atomic snapshots.</li><li>The parser accepts multiline expressions.</li></ul></section><section><h2>Implementation details</h2><p>current.html changes build flags.</p></section></main>");
  var result=rag().search(1,"读取 "+url+"，用中文说明三项主要变化",List.of(),p->{});
  assertTrue(result.contextText().contains("priority queues"));
  assertTrue(result.contextText().contains("atomic snapshots"));
  assertTrue(result.contextText().contains("multiline expressions"));
  assertFalse(result.contextText().contains("release navigation"));
  verifyNoInteractions(models);
 }
 @Test void nearbyDatesKeepTheirEventLabelsDespiteHigherMatchingBody()throws Exception{
  String url="https://1.1.1.1/releases/9";
  when(web.get(url,false)).thenReturn("<main><h1>Engine 9.2</h1><p><strong>Release date:</strong> Apr. 12, 2030</p><p>Announcement published: Apr. 14, 2030</p><p>Version: 9.2</p><h2>Changes</h2>"+"<p>更新内容包括存储优化和安全修复，更新内容及时间详情。</p>".repeat(20)+"</main>");
  var result=rag().search(1,"请阅读 "+url+" 说明更新内容与时间",List.of(),p->{});
  String context=result.contextText();
  assertTrue(context.contains("Release date: Apr. 12, 2030"));
  assertTrue(context.contains("Announcement published: Apr. 14, 2030"));
  assertTrue(context.contains("更新内容包括存储优化"));
  assertTrue(PromptBudget.tokens(context)<=2200);
  assertTrue(PromptBudget.tokens(result.sources().get(0).excerpts().get(0))<=1800);
 }

 @Test void articleHeaderTitleKeepsAdjacentReleaseDateUnderDifferentQueriesButNotSiteChrome()throws Exception{
  String url="https://1.1.1.1/releases/nebula-7",home="https://1.1.1.1/";
  when(web.get(url,false)).thenReturn("<header role='banner'><h1>Site name</h1><p>Site updated: 2029-01-01</p></header><main><article><header class='article-header'><h1>Nebula 7</h1></header><p><strong>Release date:</strong> Apr. 12, 2030</p><h2>Maintenance release notes</h2>"+"<p>Nebula 7 maintenance release fixes storage and updates build support.</p>".repeat(15)+"</article></main>");
  when(web.get(home,false)).thenReturn("<main><h1>Welcome</h1><ul><li>2030-04-14 Nebula 7 announced</li><li>2030-04-10 Nebula 6 announced</li></ul></main>");
  for(String query:List.of("请核对 "+url+" 与 "+home+" 的 Nebula 7 发布日期，区分发布日期与首页新闻日期", "读取 "+url+" 的维护更新", "请核实 Nebula 7 release date "+url)){
   var result=rag().search(1,query,List.of(),p->{});
   var release=result.sources().stream().filter(v->v.url().equals(url)).findFirst().orElseThrow();
   assertTrue(release.pageRead());String excerpt=String.join("\n",release.excerpts());
   assertTrue(excerpt.contains("Release date: Apr. 12, 2030"),excerpt);assertFalse(excerpt.contains("2029-01-01"));
   assertTrue(PromptBudget.tokens(result.contextText())<=2200);
   if(result.sources().size()==2){var other=result.sources().stream().filter(v->v.url().equals(home)).findFirst().orElseThrow();assertTrue(String.join("\n",other.excerpts()).contains("2030-04-14 Nebula 7 announced"));}
  }
 }
 @Test void mainContentHeaderSurvivesButBannerHeaderIsNotDateEvidence()throws Exception{
  String url="https://1.1.1.1/notes";
  when(web.get(url,false)).thenReturn("<main><header role='banner'><h1>Company</h1><p>Updated: 2029-01-01</p></header><header><h1>Engine release</h1></header><p>Release date: Apr. 12, 2030</p><h2>Changes</h2><p>The engine supports snapshots.</p></main>");
  var result=rag().search(1,"读取 "+url+" 核对引擎更新",List.of(),p->{});String text=result.contextText();
  assertTrue(text.contains("Release date: Apr. 12, 2030"));assertFalse(text.contains("2029-01-01"));
 }
}
