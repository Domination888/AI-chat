package org.example.aichat.release;

import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;

@Component
public class WebAccess {
  private final RuntimeMode mode;
  public WebAccess(RuntimeMode mode){this.mode=mode;}
  public String get(String address,boolean configuredBackend)throws Exception {
    URI uri=URI.create(address);
    if(!configuredBackend)NetworkPolicy.publicPage(address);
    else if(!address.startsWith(mode.value("search-url","http://127.0.0.1:8888").replaceAll("/+$","")+"/"))throw new IllegalArgumentException("搜索服务地址不正确");
    var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER);
    String proxy=mode.value("byok-proxy-host","");if(!configuredBackend&&mode.cloud()){
      if(proxy.isBlank())throw new IllegalArgumentException("公网检索代理未配置");client.proxy(ProxySelector.of(new InetSocketAddress(proxy,mode.number("byok-proxy-port",3128))));
    }
    var response=client.build().send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(configuredBackend?12:5)).header("User-Agent","StorySpace/1.0 Research").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
    try(var input=response.body()){
      if(response.statusCode()!=200)throw new java.io.IOException("检索来源返回 HTTP "+response.statusCode());
      byte[] bytes=input.readNBytes(1024*1024+1);if(bytes.length>1024*1024)throw new java.io.IOException("检索页面超过 1MiB");
      String type=response.headers().firstValue("Content-Type").orElse("");if(!configuredBackend&&!type.contains("text/")&&!type.contains("application/xhtml"))throw new java.io.IOException("来源不是文本页面");
      return new String(bytes,StandardCharsets.UTF_8);
    }
  }
}
