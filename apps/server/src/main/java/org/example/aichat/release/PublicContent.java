package org.example.aichat.release;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/** These providers are fixed by the application, never supplied by a feed or a user. */
@Component
public class PublicContent {
  private static final Set<String> HOSTS=Set.of("daily.juya.uk","nominatim.openstreetmap.org","ipwho.is","api.open-meteo.com","geocoding-api.open-meteo.com");
  private final HttpClient client;
  public PublicContent(){this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build());}
  PublicContent(HttpClient client){this.client=client;}
  public static final class Unavailable extends IOException {
    Unavailable(String message,IOException cause){super(message,cause);}
  }
  private static final class ResponseFailure extends IOException {
    final int status;final boolean retryAfter;
    ResponseFailure(int status,boolean retryAfter){super("HTTP "+status);this.status=status;this.retryAfter=retryAfter;}
  }
  private static final class ContentLimitFailure extends IOException {}
  public String get(String value)throws Exception {
    URI uri=URI.create(value);
    if(!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost()) || uri.getUserInfo()!=null || uri.getPort()!=-1 || uri.getFragment()!=null)throw new IllegalArgumentException("不支持的内容来源");
    var req=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).header("User-Agent","StorySpace/1.0 (companion features)").GET().build();
    boolean daily=uri.getHost().equals("daily.juya.uk");
    for(int attempt=0;;attempt++)try{
      var response=client.send(req,HttpResponse.BodyHandlers.ofInputStream());
      try(var body=response.body()) {
        if(response.statusCode()!=200)throw new ResponseFailure(response.statusCode(),response.headers().firstValue("Retry-After").isPresent());
        byte[] bytes=body.readNBytes(2*1024*1024+1);
        if(bytes.length>2*1024*1024)throw new ContentLimitFailure();
        return new String(bytes,StandardCharsets.UTF_8);
      }
    }catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
    catch(IOException e){
      // One retry is safe for this fixed, read-only feed. Do not retry rate limits,
      // redirects, certificate failures, oversized responses or Retry-After responses.
      if(daily&&attempt==0&&retryable(e))continue;
      String reason=reason(e);
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("内容来源读取失败 host={} category={} attempts={}",uri.getHost(),reason,attempt+1);
      String message=daily?"AI 日报来源"+reason+"，本次未更新。已有期刊会保留；请稍后点击“读取最新日报”重试。":"公共资料来源"+reason+"，请稍后重试。";
      throw new Unavailable(message,e);
    }
  }
  private static boolean retryable(IOException error){
    if(error instanceof ResponseFailure response)return !response.retryAfter&&Set.of(502,503,504).contains(response.status);
    return !(error instanceof ContentLimitFailure)&&!(error instanceof javax.net.ssl.SSLException);
  }
  private static String reason(IOException error){
    if(error instanceof HttpTimeoutException)return "连接或读取超时";
    if(error instanceof javax.net.ssl.SSLException)return "安全连接校验失败，请联系维护者检查证书";
    if(error instanceof ContentLimitFailure)return "返回内容超过大小限制，请联系维护者检查来源";
    if(error instanceof ResponseFailure response){
      if(response.status==429)return "访问过于频繁";
      if(response.status>=300&&response.status<400)return "地址发生跳转，请联系维护者确认来源地址";
      return "暂时无法读取（HTTP "+response.status+"）";
    }
    return "暂时无法连接";
  }
}
