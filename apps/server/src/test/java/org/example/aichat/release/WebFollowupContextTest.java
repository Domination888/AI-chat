package org.example.aichat.release;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WebFollowupContextTest {
 final ObjectMapper json=new ObjectMapper();
 final String url="https://example.org/release/nebula-7";
 List<Map<String,Object>> history()throws Exception{
  return List.of(Map.of("sender","user","content","请阅读 "+url+" 核对 Nebula 7 的发布日期与主要更新"),Map.of("sender","assistant","content","FAKE assistant claim must never enter evidence", "context_json",json.writeValueAsString(Map.of("sources",List.of(Map.of("title","Release","url",url,"excerpts",List.of("Release date: 2030-02-03"),"pageRead",true))))));
 }
 @Test void resolvesExplicitWebReferenceWithoutUsingAssistantProse()throws Exception{
  var result=WebFollowupContext.resolve("请将刚才的两网页核对依据详细展开",history(),json);
  assertTrue(result.inherited());assertTrue(result.retrievalQuestion().contains("Nebula 7"));assertTrue(result.retrievalQuestion().contains(url));assertFalse(result.fallbackContext().contains("FAKE"));assertTrue(result.fallbackContext().contains("本轮未重新核实"));
 }
 @Test void sameUrlCanRecoverOmittedSubject()throws Exception{
  assertTrue(WebFollowupContext.resolve("请读取 "+url+" 逐项解释发布日期",history(),json).inherited());
 }
 @Test void newSubjectsAndUnrelatedQuestionsDoNotInherit()throws Exception{
  for(String question:List.of("今天天气怎样", "请读取 "+url+" 解释 Kotlin", "刚才网页里的杭州天气", "换个话题，刚才网页详细展开"))assertFalse(WebFollowupContext.resolve(question,history(),json).inherited(),question);
 }
 @Test void failedReferentialTurnCanReachThePreviousStructuredSources()throws Exception{
  var rows=new ArrayList<>(history());rows.add(Map.of("sender","user","content","请将刚才的网页详细展开"));rows.add(Map.of("sender","assistant","content","no sources","context_json","{\"sources\":[]}"));
  assertTrue(WebFollowupContext.resolve("请将刚才的网页依据展开",rows,json).inherited());
  rows.add(Map.of("sender","user","content","今天天气怎样"));rows.add(Map.of("sender","assistant","content","weather"));assertFalse(WebFollowupContext.resolve("刚才网页详细展开",rows,json).inherited());
 }
 @Test void untrustedAssistantTextAloneCannotCreateSources(){assertFalse(WebFollowupContext.resolve("刚才网页详细展开",List.of(Map.of("sender","user","content","请读 "+url),Map.of("sender","assistant","content","date 2030-02-03")),json).inherited());}
 @Test void dateQuestionKeepsDatedHomepageEntriesWithoutLatinEntity(){
  String html="<title>Home</title><main><h1>Home</h1>"+"<p>Get started with our docs</p>".repeat(12)+"<ul><li><time datetime='2030-02-04'>2030-02-04</time> Nebula 7 announced</li><li>2030-01-10 Nebula 6 announced</li></ul></main>";
  String text=SearchPage.parse(html,url,"请核对主页公告日期及发布日期").excerpt();
  assertTrue(text.contains("2030-02-04 Nebula 7 announced"));assertTrue(text.contains("日期仅属于本条"));
 }
 List<Map<String,Object>> searched(String question,Map<String,Object> extra)throws Exception{
  var context=new LinkedHashMap<String,Object>(extra);context.put("webQuestion",question);context.put("sources",List.of(Map.of("title","Nebula release","url",url,"excerpts",List.of("Release date: 2030-02-03"),"pageRead",true)));
  return List.of(Map.of("sender","user","content",question),Map.of("sender","assistant","content","FAKE","context_json",json.writeValueAsString(context)));
 }
 @Test void markedOrdinarySearchWithoutExplicitUrlIsEligible()throws Exception{
  var result=WebFollowupContext.resolve("请将刚才网页依据详细展开",searched("请联网核对 Nebula 7 的发布日期",Map.of()),json);
  assertTrue(result.inherited());assertEquals("请联网核对 Nebula 7 的发布日期",result.originalQuestion());assertTrue(result.referenceContext().contains("不是事实依据"));assertFalse(result.referenceContext().contains("FAKE"));
 }
 @Test void successfulFollowupsKeepCanonicalSubjectAndBoundedQuestion()throws Exception{
  var rows=new ArrayList<>(searched("请联网核对 Nebula 7 的发布日期",Map.of()));
  for(int i=0;i<5;i++){
   String current="请将刚才网页依据详细展开";var result=WebFollowupContext.resolve(current,rows,json);assertTrue(result.inherited());assertEquals("请联网核对 Nebula 7 的发布日期",result.originalQuestion());assertEquals(1,result.retrievalQuestion().split("本轮追问：",-1).length-1);
   var saved=searched(result.originalQuestion(),Map.of());rows.add(Map.of("sender","user","content",current));rows.add(saved.get(1));
  }
 }
 @Test void markedSearchStillRejectsTopicChangesDifferentUrlsAndOtherToolKinds()throws Exception{
  var rows=searched("请联网核对 Nebula 7 的发布日期",Map.of());
  for(String question:List.of("请读取 https://example.org/other 核对发布日期","刚才网页请解释 Kotlin","换个话题，刚才网页展开"))assertFalse(WebFollowupContext.resolve(question,rows,json).inherited());
  for(var excluded:List.of(Map.<String,Object>of("aiDaily",Map.of()),Map.<String,Object>of("isProactive",true),Map.<String,Object>of("scheduledSkill",Map.of())))assertFalse(WebFollowupContext.resolve("刚才网页详细展开",searched("Nebula 7 发布日期",excluded),json).inherited());
 }
 @Test void longOriginalQuestionKeepsCompleteUrlsInsideSearchLimit()throws Exception{
  String original="请联网核对 Nebula 7 的发布日期。"+"详细材料说明".repeat(145);
  assertTrue(original.length()<1000);
  var result=WebFollowupContext.resolve("刚才网页详细展开",searched(original,Map.of()),json);
  assertTrue(result.inherited());assertTrue(result.retrievalQuestion().length()<=1000);assertTrue(result.retrievalQuestion().endsWith(url));assertEquals(List.of(url),SearchTerms.explicitUrls(result.retrievalQuestion()));assertTrue(result.originalQuestion().length()<=700);
 }
 @Test void oversizedCompleteTargetsConservativelyDoNotInherit()throws Exception{
  String huge="https://example.org/"+"x".repeat(850);
  String context=json.writeValueAsString(Map.of("webQuestion","Nebula release date","sources",List.of(Map.of("url",huge,"title","Nebula","pageRead",true))));
  assertFalse(WebFollowupContext.resolve("刚才网页详细展开",List.of(Map.of("sender","user","content","Nebula release date"),Map.of("sender","assistant","context_json",context)),json).inherited());
 }
 @Test void naturalPoliteWebFollowupsKeepTopicWhileNewSubjectsDoNot()throws Exception{
  var rows=searched("请联网核对 Nebula 7 的发布日期",Map.of());
  for(String question:List.of("你能把刚才两个网页的依据再说清楚一点吗？","能不能详细说说刚才那两个页面的时间依据？","麻烦你帮我把上述来源的依据具体解释一下呢？","可不可以说说这些页面的日期为什么不同？"))assertTrue(WebFollowupContext.resolve(question,rows,json).inherited(),question);
  for(String question:List.of("你能把刚才网页里的杭州天气说清楚一点吗？","能不能说说刚才页面里的公司收入？","请帮我解释刚才网页中的量子计算新技术？","能不能帮我说说杭州天气？"))assertFalse(WebFollowupContext.resolve(question,rows,json).inherited(),question);
 }

 @Test void secondNaturalDateMeaningQuestionKeepsCanonicalSourcesWithoutAWebpageKeyword()throws Exception{
  String original="请阅读 "+url+" 核对 Nebula 7 的发布日期与公告日期";
  var rows=new ArrayList<>(searched(original,Map.of()));
  String first="你能把刚才两个网页的依据再说清楚一点吗？";
  var resolved=WebFollowupContext.resolve(first,rows,json);assertTrue(resolved.inherited());
  rows.add(Map.of("sender","user","content",first));rows.add(searched(original,Map.of()).get(1));
  for(String current:List.of("那么你能再确认一次，两处日期各自的含义和你判断的依据吗？","两种日期分别对应什么，依据是什么？","What do those two dates mean?")){
   var result=WebFollowupContext.resolve(current,rows,json);assertTrue(result.inherited(),current);assertEquals(original,result.originalQuestion());assertTrue(result.retrievalQuestion().contains(url));assertFalse(result.retrievalQuestion().contains(first));assertEquals(1,result.retrievalQuestion().split("本轮追问：",-1).length-1);
  }
  for(String current:List.of("那么你能再确认一次杭州天气的依据吗？","两处日期里的公司收入依据是什么？","换个话题，两处日期各自含义是什么？","这家公司成立的日期是什么？","判断的依据是什么？"))assertFalse(WebFollowupContext.resolve(current,rows,json).inherited(),current);
  assertFalse(WebFollowupContext.resolve("两处日期各自的含义是什么？",searched("请核对 Nebula 7 的内存用量",Map.of()),json).inherited());
 }

 @Test void boundedReleaseTimingQuestionsUseOnlyAdjacentDateTaskNotPersonalCalendar()throws Exception{
  var rows=searched("请核对 Nebula 7 的发布日期与首页公告日期",Map.of());
  for(String q:List.of("所以哪天才是正式发布日期？","那公告和发布到底分别是哪一天？","那正式发布究竟是哪天？"))assertTrue(WebFollowupContext.resolve(q,rows,json).inherited(),q);
  for(String q:List.of("现在是几号？","下次旅行日期是哪天？","所以杭州新机场正式发布日期是哪天？"))assertFalse(WebFollowupContext.resolve(q,rows,json).inherited(),q);
  var nonDate=searched("请说明 Nebula 7 的性能",Map.of());
  assertFalse(WebFollowupContext.resolve("所以哪天才是正式发布日期？",nonDate,json).inherited());
  var interrupted=new ArrayList<>(rows);interrupted.add(Map.of("sender","user","content","下次旅行日期是哪天？"));interrupted.add(Map.of("sender","assistant","content","请提供计划"));
  assertFalse(WebFollowupContext.resolve("所以哪天才是正式发布日期？",interrupted,json).inherited());
 }
}
