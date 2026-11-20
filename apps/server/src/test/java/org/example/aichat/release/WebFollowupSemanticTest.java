package org.example.aichat.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebFollowupSemanticTest {
  final ObjectMapper json=new ObjectMapper();
  final String original="请阅读 https://example.org/release 与 https://example.org/ ，核对 Nebula 的发布日期与公告日期";
  final List<SearchRag.Source> sources=List.of(new SearchRag.Source("Release","https://example.org/release","",List.of("release evidence"),1,true),new SearchRag.Source("Home","https://example.org/","",List.of("announcement evidence"),1,true));
  List<Map<String,Object>> history(Map<String,Object> extra)throws Exception{
    var metadata=new LinkedHashMap<String,Object>(extra);metadata.put("webQuestion",original);metadata.put("sources",sources);
    return List.of(Map.of("sender","user","content","上一次省略实体的自然追问"),Map.of("sender","assistant","content","UNTRUSTED_OLD_ANSWER","context_json",json.writeValueAsString(metadata)));
  }
  @Test void unknownWordingUsesOneBoundedJudgmentAndKeepsOriginalProvenance()throws Exception{
    for(String q:List.of("所以哪天才是正式发布日期，公告日期又代表什么？","为何会有两种时间标注？","Which one should I call the actual release?")){
      var calls=new AtomicInteger();
      var result=WebFollowupContext.resolve(q,history(Map.of()),json,(current,prior,targets)->{calls.incrementAndGet();assertEquals(q,current);assertEquals(original,prior);assertEquals(2,targets.size());return true;});
      assertTrue(result.inherited());assertEquals(1,calls.get());assertEquals(original,result.originalQuestion());assertTrue(result.retrievalQuestion().contains("https://example.org/release"));assertTrue(result.retrievalQuestion().length()<=1000);assertFalse(result.fallbackContext().contains("UNTRUSTED_OLD_ANSWER"));
    }
  }
  @Test void negativeJudgmentNeverReusesSourcesForAnIndependentQuestion()throws Exception{
    for(String q:List.of("今晚杭州会下雨吗？","Nebula该怎么安装？","所以杭州新机场正式发布日期是哪天？")){
      var calls=new AtomicInteger();var result=WebFollowupContext.resolve(q,history(Map.of()),json,(current,prior,targets)->{calls.incrementAndGet();return false;});
      assertFalse(result.inherited());assertEquals(q,result.retrievalQuestion());assertTrue(result.priorSources().isEmpty());assertEquals(1,calls.get());
    }
  }
  @Test void HardBoundariesAndUnrelatedInterveningTurnCannotBeOverriddenByAJudge()throws Exception{
    WebFollowupContext.ReferenceJudge forbidden=(q,p,s)->{fail("hard boundary must not call classifier");return true;};
    for(String q:List.of("换个话题，哪天才是正式发布？","请按 https://other.example/new 阅读刚才的发布日期"))assertFalse(WebFollowupContext.resolve(q,history(Map.of()),json,forbidden).inherited());
    for(var flag:List.of(Map.<String,Object>of("aiDaily",Map.of()),Map.<String,Object>of("scheduledSkill",Map.of()),Map.<String,Object>of("isProactive",true)))assertFalse(WebFollowupContext.resolve("它说明了什么？",history(flag),json,forbidden).inherited());
    var interrupted=new ArrayList<>(history(Map.of()));interrupted.add(Map.of("sender","user","content","今晚杭州天气"));interrupted.add(Map.of("sender","assistant","content","weather"));
    assertFalse(WebFollowupContext.resolve("它说明了什么？",interrupted,json,forbidden).inherited());
    assertFalse(WebFollowupContext.resolve("问".repeat(401),history(Map.of()),json,forbidden).inherited());
  }
  @Test void classifierReceivesOnlyBoundedUserQuestionsAndSourceIdentitiesAndBillsItsActualUsage()throws Exception{
    var models=mock(OpenAiTransport.class);var connection=new ModelConnections.Connection("http://127.0.0.1:1/v1","test","",false);
    var billed=new ArrayList<OpenAiTransport.Usage>();
    when(models.complete(eq(connection),anyList(),eq(100))).thenAnswer(call->{
      List<Map<String,Object>> prompt=call.getArgument(1);String payload=prompt.get(1).get("content").toString();
      assertFalse(payload.contains("release evidence"));assertFalse(payload.contains("UNTRUSTED_OLD_ANSWER"));assertEquals(3,json.readTree(payload).size());
      return new OpenAiTransport.Completion("{\"kind\":\"dependent_reference\"}",new OpenAiTransport.Usage(90,7,false));
    });
    assertTrue(WebFollowupContext.classifyReference("那另一个日期指什么？",original,sources,models,connection,json,billed::add));
    assertEquals(List.of(new OpenAiTransport.Usage(90,7,false)),billed);
    for(String value:List.of("{\"kind\":\"uncertain\"}","{\"kind\":\"standalone_or_new_topic\"}","{\"kind\":\"dependent_reference\",\"url\":\"https://evil.example\"}","{\"kind\":\"dependent_reference\"} trailing","not json")){
      when(models.complete(eq(connection),anyList(),eq(100))).thenReturn(new OpenAiTransport.Completion(value,new OpenAiTransport.Usage(2,1,false)));
      assertFalse(WebFollowupContext.classifyReference("它意味着什么？",original,sources,models,connection,json,billed::add));
    }
  }
}
