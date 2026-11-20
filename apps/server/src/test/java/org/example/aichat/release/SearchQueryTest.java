package org.example.aichat.release;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchQueryTest {
  @Test void naturalRequestsKeepTopicsAndDropResponseInstructions(){
    assertEquals("Python 官网的入门教程",SearchTerms.searchQuery("请联网搜索 Python 官网的入门教程，给我一个官方学习入口，并附来源链接。"));
    assertEquals("杭州今天的公开天气",SearchTerms.searchQuery("请查询杭州今天的公开天气，说明数据来源和查询时间；如果查不到，请直接说明。"));
    assertEquals("Python 最新进展",SearchTerms.searchQuery("Python 最新进展"));
    assertEquals("摄影，杭州",SearchTerms.searchQuery("摄影，杭州"));
    assertEquals("\"Python tutorial\" site:python.org",SearchTerms.searchQuery("\"Python tutorial\" site:python.org"));
  }
  @Test void documentComparisonKeepsTechnicalSubjectsInsteadOfTheRequestedAnswerFormat(){
    String original="请联网查看 Vue 官方文档：ref 和 reactive 有什么区别？给我两个适用场景，并附上官方来源。";
    String planned=SearchTerms.searchQuery(original);assertEquals("Vue 官方文档 ref reactive 区别",planned);
    assertEquals("Vue ref reactive official documentation difference",SearchTerms.focusedQuery(planned));
    for(String instruction:new String[]{"请联网","给我","适用场景","附上"})assertFalse(planned.contains(instruction));
  }
  @Test void focusedSearchRetainsOtherSubjectsAndDoesNotInventAnOfficialHostname(){
    String planned=SearchTerms.searchQuery("请查阅 FastAPI 与 Pydantic 的请求校验文档？提供两个示例，并附来源。");
    String focused=SearchTerms.focusedQuery(planned);assertTrue(focused.contains("FastAPI"));assertTrue(focused.contains("Pydantic"));assertTrue(focused.contains("请求校验"));
    assertFalse(focused.contains("示例"));assertFalse(focused.contains("site:"));assertFalse(focused.contains("https:"));
    assertEquals("",SearchTerms.focusedQuery("Vue ref reactive site:vuejs.org 官方文档"));
  }
  @Test void shortCodeNamesAreAnchorsAndSubstringMatchesDoNotCountAsTheRequestedApi(){
    assertTrue(SearchTerms.relevance("Go SQL","Go SQL database guide","https://1.1.1.1/guide","database/sql usage")>0);
    assertEquals(0,SearchTerms.relevance("ref reactive","Reference catalogue","https://1.1.1.1/reference","reactive reference overview"));
    assertEquals(0,SearchTerms.relevance("Vue ref reactive documentation","Vue cinema tickets","https://1.1.1.1/films","Buy cinema tickets"));
  }
  @Test void actualBrandDomainMetadataOutranksSeoChineseOverlapForAnOfficialRequest(){
    String query="Vue 官方文档 ref reactive 区别";
    double official=SearchTerms.relevance(query,"Reactivity Fundamentals | Vue.js","https://vuejs.org/guide/essentials/reactivity-fundamentals.html","Vue ref() and reactive() state APIs");
    double seo=SearchTerms.relevance(query,"Vue 官方文档 ref reactive 区别详解","https://blog.example/articles/vue/ref-reactive-documentation","Vue 官方文档 ref reactive 区别，完整文档教程");
    assertTrue(official>seo,"Domain and actual punctuated brand should beat answer-shaped SEO overlap");
    assertFalse(SearchTerms.brandDomainAffinity(query,"Vue.js 官方文档权威教程","https://blog.example/vue"));
    assertFalse(SearchTerms.brandDomainAffinity(query,"Vue.js documentation","https://vuejs-community.example/"));
    assertEquals(0,SearchTerms.relevance(query,"Vue.js","https://vuejs.org/","A progressive framework homepage"),"Brand alone is not ref/reactive evidence");
  }
  @Test void punctuatedBrandRuleWorksForOtherProductsAndKeepsTheirApiSubjects(){
    String query="Node 官方文档 fs writeFile";
    assertTrue(SearchTerms.relevance(query,"File system | Node.js","https://nodejs.org/api/fs.html","Node fs writeFile API")>
      SearchTerms.relevance(query,"Node 官方文档 fs writeFile","https://blog.example/node-documentation","Node 官方文档 fs writeFile 文档"));
    assertTrue(SearchTerms.brandDomainAffinity("Python 官方文档 pathlib","Python pathlib documentation","https://docs.python.org/3/library/pathlib.html"));
    assertEquals(0,SearchTerms.relevance("Node.js 官方文档 fs writeFile","Node.js","https://nodejs.org/","Node.js homepage"),"Node.js is one brand anchor, not two API matches");
    String planned=SearchTerms.searchQuery("请联网查 Vue 官方文档的 ref 和 reactive 区别，说明适用场景并附官方来源。");
    assertTrue(planned.startsWith("Vue"));assertFalse(planned.contains("请联网查"));assertTrue(SearchTerms.focusedQuery(planned).contains("official documentation"));
  }
  @Test void chineseQuestionsRequireSubjectEvidenceRatherThanQuestionOrFormatWords(){
    assertEquals(0,SearchTerms.relevance("今天杭州天气有什么变化？请附来源","树大招风电影里任贤齐每见一个官都送一个花瓶是什么意思？","https://example.com/film","这个花瓶，你送给我的时候，我觉得它是真的"));
    assertEquals(0,SearchTerms.relevance("请现在联网查询上海今天的天气，给出实际搜索来源","请字笔画笔顺","https://example.com/请.html","请是什么意思，怎么写"));
    assertEquals(0,SearchTerms.relevance("南京博物院开放时间","上海科技馆开放时间","https://example.com/museum","开放时间查询"));
    assertEquals(0,SearchTerms.relevance("如何修理自行车刹车","如何学习摄影","https://example.com/photo","学习摄影的方法"));
    assertTrue(SearchTerms.relevance("今天杭州天气有什么变化","杭州天气预报","https://example.com/report","杭州降雨天气变化")>0);
    assertTrue(SearchTerms.relevance("南京博物院开放时间","南京博物院参观公告","https://example.com/museum","开放时间为九点")>0);
    assertTrue(SearchTerms.relevance("摄影","摄影入门","https://example.com/photo","摄影基础")>0);
  }
}
