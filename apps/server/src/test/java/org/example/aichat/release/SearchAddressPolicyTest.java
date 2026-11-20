package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class SearchAddressPolicyTest {
  final ObjectMapper json=new ObjectMapper();
  final CompanionService companion=mock(CompanionService.class);
  final RuntimeMode mode=mock(RuntimeMode.class);
  final WebAccess web=mock(WebAccess.class);
  final OpenAiTransport models=mock(OpenAiTransport.class);
  SearchRag fixture(String reply)throws Exception{
    when(companion.settings(1)).thenReturn(json.createObjectNode().put("searchEnabled",true));
    when(mode.value("search-url","http://127.0.0.1:8888")).thenReturn("http://127.0.0.1:8888");
    when(web.get(anyString(),eq(true))).thenReturn(reply);
    return new SearchRag(companion,mode,web,json,models);
  }
  @Test void resultsBlockedByFakeDnsAreNotMisreportedAsEngineRateLimits()throws Exception{
    var response=json.createObjectNode();response.putArray("unresponsive_engines").addArray().add("brave").add("too many requests");var results=response.putArray("results");
    for(int i=0;i<10;i++)results.addObject().put("title","Python 官方资料").put("url","https://198.18.0.33/doc/"+i).put("content","Python documentation");
    var progress=new ArrayList<Map<String,Object>>();var result=fixture(response.toString()).search(1,"Python documentation",List.of(),progress::add);
    assertEquals("unavailable",result.status());assertTrue(result.sources().isEmpty());assertTrue(result.reason().contains("搜索已返回结果"));assertTrue(result.reason().contains("10 条来源地址校验未通过"));assertTrue(result.reason().contains("Fake-IP"));assertFalse(result.reason().contains("brave"));assertTrue(result.contextText().contains("不会访问内网地址"));
    assertEquals("search_unavailable",progress.get(progress.size()-1).get("phase"));verify(web,never()).get(anyString(),eq(false));
  }
  @Test void privateAddressesRemainBlockedWithoutAssumingTheyAreFakeDns()throws Exception{
    var result=fixture("{\"results\":[{\"title\":\"private\",\"url\":\"https://127.0.0.1/private\"}]}").search(1,"private",List.of(),p->{});
    assertTrue(result.reason().contains("来源地址校验未通过"));assertFalse(result.reason().contains("Fake-IP"));verify(web,never()).get(anyString(),eq(false));
  }
  @Test void relevantHttpResultsExplainTheProtocolRestrictionWithoutInventingADnsFailure()throws Exception{
    var result=fixture("{\"results\":[{\"title\":\"Python documentation\",\"url\":\"http://1.1.1.1/guide\",\"content\":\"Python guide\"}]}").search(1,"Python documentation",List.of(),p->{});
    assertEquals("unavailable",result.status());assertTrue(result.reason().contains("HTTPS"));assertTrue(result.reason().contains("443"));
    assertFalse(result.reason().contains("DNS"));assertFalse(result.reason().contains("Fake-IP"));verify(web,never()).get(anyString(),eq(false));
  }
  @Test void irrelevantHttpAndPrivateResultsCannotBecomeTheTopicsDnsDiagnosis()throws Exception{
    var result=fixture("{\"results\":[{\"title\":\"Hotel Albuquerque\",\"url\":\"http://1.1.1.1/hotel\",\"content\":\"Hotel reservations\"},{\"title\":\"Local booking\",\"url\":\"https://127.0.0.1/private\",\"content\":\"Hotel\"}],\"unresponsive_engines\":[[\"brave\",\"too many requests\"]]}")
      .search(1,"请联网查看 Vue 官方文档：ref 和 reactive 有什么区别？给我两个适用场景，并附上官方来源。",List.of(),p->{});
    assertEquals("empty",result.status());assertTrue(result.sources().isEmpty());assertTrue(result.reason().contains("不够相关"));
    assertFalse(result.reason().contains("DNS"));assertFalse(result.reason().contains("地址校验"));assertEquals(2,result.plannedQueries().size());
    verify(web,times(2)).get(anyString(),eq(true));verify(web,never()).get(anyString(),eq(false));verifyNoInteractions(models);
  }
  @Test void acceptedPublicSourcesRemainAvailableWhenAnotherResultIsBlocked()throws Exception{
    var rag=fixture("{\"results\":[{\"title\":\"blocked\",\"url\":\"https://198.19.0.1/\"},{\"title\":\"public\",\"url\":\"https://1.1.1.1/docs\",\"content\":\"public documentation\"}]}");
    when(web.get("https://1.1.1.1/docs",false)).thenReturn("<main>Public documentation.</main>");
    var result=rag.search(1,"documentation",List.of(),p->{});assertEquals("ok",result.status());assertEquals(1,result.sources().size());assertEquals("https://1.1.1.1/docs",result.sources().get(0).url());verify(web,never()).get("https://198.19.0.1/",false);
  }
  @Test void benchmarkAddressesExplainRecoveryButStillRejectInTheSharedMcpPolicy(){
    for(String address:List.of("https://198.18.0.101/sse","https://198.19.255.255/sse")){
      var error=assertThrows(NetworkPolicy.AddressRejected.class,()->NetworkPolicy.publicHttps(address));assertTrue(error.possibleFakeDns());assertTrue(error.getMessage().contains("真实公网 DNS"));
    }
    assertThrows(IllegalArgumentException.class,()->NetworkPolicy.publicHttps("https://[fdfe:dcba:9876::66]/sse"));
    assertThrows(IllegalArgumentException.class,()->NetworkPolicy.publicHttps("https://127.0.0.1/sse"));
    assertEquals("1.1.1.1",NetworkPolicy.publicHttps("https://1.1.1.1/sse").getHost());
  }
}
