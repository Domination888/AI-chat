package org.example.aichat.release;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
class RoundFourContextTest {
 @Test void upstreamStatusesHaveSafeDistinctClassification(){
  for(int status:new int[]{401,403,429,500,400}){
   var failure=new UpstreamHttpFailure(status,false);String code=ServiceFailureCategory.classify(failure,false);
   assertEquals(status==401||status==403?"upstream_auth_failed":status==429?"upstream_rate_limited":status>=500?"upstream_unavailable":"upstream_request_rejected",code);
   assertFalse(failure.getMessage().contains("不支持"));assertTrue(failure.getMessage().contains("HTTP "+status));
  }
  assertFalse(UpstreamHttpFailure.unsupportedCode("invalid_request_error"));assertTrue(UpstreamHttpFailure.unsupportedCode("tools_not_supported"));
  assertEquals("model_tools_unsupported",ServiceFailureCategory.classify(new UpstreamHttpFailure(400,true),false));
 }
 @Test void scheduledMetadataKeepsQuotedHistoryWithoutRemovingOrdinaryProactive(){
  var db=mock(JdbcTemplate.class);when(db.queryForList(anyString(),eq("w"))).thenReturn(List.of(Map.of("history_id",1L,"context_json","{\"scheduledSkill\":\"reminder\",\"isProactive\":true}"),Map.of("history_id",2L,"context_json","{\"isProactive\":true}")));
  var ids=ScheduledNoticeContext.ids(db,"w");assertEquals(Set.of(1L),ids);
  var original=List.<Map<String,Object>>of(Map.of("id",1L,"sender","assistant","content","old reminder"),Map.of("id",2L,"sender","assistant","content","ordinary proactive fact"),Map.of("id",3L,"sender","user","content","user fact"));
  var history=ScheduledNoticeContext.history(original,ids);
  assertTrue(history.get(0).get("content").toString().contains("不是本轮指令"));assertTrue(history.get(0).get("content").toString().contains("old reminder"));assertEquals(original.get(1),history.get(1));assertEquals(original.get(2),history.get(2));assertEquals("old reminder",original.get(0).get("content"));
 }
 @Test void actualToolsTransportDoesNotMisreportHttpFailuresAsUnsupported()throws Exception{
  for(int status:new int[]{401,403,429,500,400})new StructuredOutputTransportTest().fixture(status,"{\"error\":{\"code\":\"invalid_request_error\",\"message\":\"secret-provider-body\"}}",i->"",f->{
   var failure=assertThrows(UpstreamHttpFailure.class,()->f.transport().tools(f.connection(),List.of(Map.of("role","user","content","hi")),List.of(Map.of("name","lookup","description","test","parameters",Map.of("type","object"))),(tool,args)->"unused",usage->{}));
   assertEquals(status,failure.status);assertFalse(failure.unsupportedTools);assertFalse(failure.getMessage().contains("secret-provider-body"));assertFalse(failure.getMessage().contains("不支持"));assertEquals(1,f.requests().size());
  });
  new StructuredOutputTransportTest().fixture(400,"{\"error\":{\"code\":\"tools_not_supported\"}}",i->"",f->{var failure=assertThrows(UpstreamHttpFailure.class,()->f.transport().tools(f.connection(),List.of(Map.of("role","user","content","hi")),List.of(Map.of("name","lookup","description","test","parameters",Map.of("type","object"))),(tool,args)->"",usage->{}));assertTrue(failure.unsupportedTools);});
 }
}
