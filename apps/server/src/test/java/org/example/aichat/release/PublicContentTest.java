package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicContentTest {
  static final String DAILY="https://daily.juya.uk/rss.xml";
  HttpResponse<InputStream> response(int status,byte[] bytes,boolean retryAfter){
    var body=new ByteArrayInputStream(bytes);var headers=HttpHeaders.of(retryAfter?Map.of("Retry-After",List.of("60")):Map.of(),(k,v)->true);
    return new HttpResponse<>(){
      public int statusCode(){return status;} public InputStream body(){return body;} public HttpHeaders headers(){return headers;}
      public HttpRequest request(){return HttpRequest.newBuilder(java.net.URI.create(DAILY)).build();}
      public Optional<HttpResponse<InputStream>> previousResponse(){return Optional.empty();}
      public Optional<javax.net.ssl.SSLSession> sslSession(){return Optional.empty();}
      public java.net.URI uri(){return java.net.URI.create(DAILY);} public HttpClient.Version version(){return HttpClient.Version.HTTP_2;}
    };
  }
  HttpResponse<InputStream> response(int status,String body){return response(status,body.getBytes(StandardCharsets.UTF_8),false);}
  @Test void aTransientDailyReadRetriesOnceAndReturnsOnlyTheSuccessfulResponse()throws Exception{
    var client=mock(HttpClient.class);when(client.<InputStream>send(any(),any())).thenThrow(new HttpTimeoutException("temporary")).thenReturn(response(200,"真实读取的期刊内容"));
    assertEquals("真实读取的期刊内容",new PublicContent(client).get(DAILY));verify(client,times(2)).send(any(),any());
  }
  @Test void exhaustedRetryMapsToActionable503WithoutClaimingTheCacheWasUpdated()throws Exception{
    var client=mock(HttpClient.class);when(client.<InputStream>send(any(),any())).thenThrow(new IOException("connection reset"));
    var error=assertThrows(PublicContent.Unavailable.class,()->new PublicContent(client).get(DAILY));verify(client,times(2)).send(any(),any());
    var result=new ApiErrors().publicContent(error);assertEquals(503,result.getStatusCode().value());var body=(Map<?,?>)result.getBody();
    assertEquals("public_content_unavailable",body.get("code"));String message=body.get("message").toString();assertTrue(message.contains("本次未更新"));assertTrue(message.contains("已有期刊会保留"));assertTrue(message.contains("读取最新日报”重试"));assertFalse(message.contains("已更新"));assertFalse(message.contains("connection reset"));
  }
  @Test void transientGatewayResponseCanRecover()throws Exception{
    var client=mock(HttpClient.class);when(client.<InputStream>send(any(),any())).thenReturn(response(503,"busy"),response(200,"fresh"));
    assertEquals("fresh",new PublicContent(client).get(DAILY));verify(client,times(2)).send(any(),any());
  }
  @ParameterizedTest @ValueSource(ints={302,404,429})
  void redirectsMissingPagesAndRateLimitsAreNotRetriedOrFollowed(int status)throws Exception{
    var client=mock(HttpClient.class);when(client.<InputStream>send(any(),any())).thenReturn(response(status,"unavailable"));
    assertThrows(PublicContent.Unavailable.class,()->new PublicContent(client).get(DAILY));verify(client).send(any(),any());
  }
  @Test void retryAfterIsRespectedWithoutAnImmediateSecondRequest()throws Exception{
    var client=mock(HttpClient.class);when(client.<InputStream>send(any(),any())).thenReturn(response(503,new byte[0],true));
    assertThrows(PublicContent.Unavailable.class,()->new PublicContent(client).get(DAILY));verify(client).send(any(),any());
  }
  @Test void certificateFailureDoesNotWeakenTlsOrRetry()throws Exception{
    var client=mock(HttpClient.class);when(client.<InputStream>send(any(),any())).thenThrow(new SSLHandshakeException("certificate"));
    var error=assertThrows(PublicContent.Unavailable.class,()->new PublicContent(client).get(DAILY));assertTrue(error.getMessage().contains("安全连接校验失败"));verify(client).send(any(),any());
  }
  @Test void oversizedContentStillFailsWithoutRetry()throws Exception{
    var client=mock(HttpClient.class);when(client.<InputStream>send(any(),any())).thenReturn(response(200,new byte[2*1024*1024+1],false));
    var error=assertThrows(PublicContent.Unavailable.class,()->new PublicContent(client).get(DAILY));assertTrue(error.getMessage().contains("大小限制"));verify(client).send(any(),any());
  }
  @Test void fixedSourceRestrictionsAreUnchanged()throws Exception{
    var client=mock(HttpClient.class);var content=new PublicContent(client);
    for(String source:List.of("http://daily.juya.uk/rss.xml","https://example.org/rss.xml","https://user@daily.juya.uk/rss.xml","https://daily.juya.uk:443/rss.xml","https://daily.juya.uk/rss.xml#fragment"))assertThrows(IllegalArgumentException.class,()->content.get(source));
    verifyNoInteractions(client);
  }
  @Test void retryIsLimitedToDailyReads()throws Exception{
    var client=mock(HttpClient.class);when(client.<InputStream>send(any(),any())).thenThrow(new HttpTimeoutException("temporary"));
    var error=assertThrows(PublicContent.Unavailable.class,()->new PublicContent(client).get("https://api.open-meteo.com/v1/forecast"));assertFalse(error.getMessage().contains("期刊"));verify(client).send(any(),any());
  }
  @Test void interruptionIsPreservedAndNeverRetried()throws Exception{
    var client=mock(HttpClient.class);when(client.<InputStream>send(any(),any())).thenThrow(new InterruptedException("cancelled"));
    try{assertThrows(InterruptedException.class,()->new PublicContent(client).get(DAILY));assertTrue(Thread.currentThread().isInterrupted());verify(client).send(any(),any());}finally{Thread.interrupted();}
  }
}
