package org.example.aichat.release;

import com.fasterxml.jackson.databind.*;
import dev.langchain4j.mcp.client.protocol.*;
import dev.langchain4j.mcp.client.transport.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** SSE MCP with bounded messages, no redirects, same-origin POST and cloud egress enforcement. */
final class SseMcpTransport implements McpTransport {
  private final URI address;private final boolean cloud;private final ObjectMapper json;private final Map<String,String> headers;private final HttpClient http;
  private final ExecutorService workers=Executors.newCachedThreadPool(r->{var t=new Thread(r,"mcp-sse");t.setDaemon(true);return t;});
  private volatile CompletableFuture<URI> endpoint=new CompletableFuture<>();private volatile McpOperationHandler handler;private volatile InputStream input;private volatile boolean closed;private volatile Runnable failure=()->{};private volatile McpConnectionFailure lastFailure;
  SseMcpTransport(String address,Map<String,String> headers,RuntimeMode mode,ObjectMapper json){
    this.address=URI.create(address);this.cloud=mode.cloud();this.headers=Map.copyOf(headers);this.json=json;
    if(cloud)NetworkPolicy.publicHttps(address);else{try{NetworkPolicy.requireLoopback(address);}catch(IllegalArgumentException e){NetworkPolicy.publicHttps(address);}}
    var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER);
    if(cloud){String proxy=mode.value("byok-proxy-host","");if(proxy.isBlank())throw new IllegalArgumentException("商业 MCP 公网出站代理未配置");builder.proxy(ProxySelector.of(new InetSocketAddress(proxy,mode.number("byok-proxy-port",3128))));}
    http=builder.build();
  }
  URI postAddress(String value){URI post=address.resolve(value.strip());if(!Objects.equals(address.getScheme(),post.getScheme())||!Objects.equals(address.getHost(),post.getHost())||address.getPort()!=post.getPort()||post.getUserInfo()!=null||post.getFragment()!=null)throw new IllegalArgumentException("MCP POST 端点必须与 SSE 地址同源");if(cloud)NetworkPolicy.publicPage(post.toString());return post;}
  private HttpRequest.Builder request(URI uri){var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20));headers.forEach(builder::header);return builder;}
  public void start(McpOperationHandler handler){this.handler=handler;closed=false;lastFailure=null;endpoint=new CompletableFuture<>();workers.execute(()->{
    try{var response=http.send(request(address).header("Accept","text/event-stream").GET().build(),HttpResponse.BodyHandlers.ofInputStream());input=response.body();try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){
      if(response.statusCode()!=200)throw McpConnectionFailure.http(response.statusCode());if(!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).contains("text/event-stream"))throw McpConnectionFailure.protocol();String event="";var data=new StringBuilder();String line;
      while(!closed&&(line=OpenAiTransport.boundedLine(reader))!=null){if(line.isEmpty()){if(!data.isEmpty()){String value=data.toString();if(value.endsWith("\n"))value=value.substring(0,value.length()-1);if(event.equals("endpoint"))endpoint.complete(postAddress(value));else if(event.equals("message")||event.isEmpty())handler.handle(json.readTree(value));}event="";data.setLength(0);continue;}
        if(line.startsWith("event:"))event=line.substring(6).strip();else if(line.startsWith("data:")){String value=line.substring(5);if(value.startsWith(" "))value=value.substring(1);data.append(value).append('\n');if(data.length()>1024*1024)throw new IOException("MCP 消息超过 1MiB");}}
      if(!closed)throw new IOException("MCP SSE 连接已断开");
    }}catch(Exception e){if(!closed){boolean hadEndpoint=endpoint.isDone()&&!endpoint.isCompletedExceptionally();lastFailure=McpConnectionFailure.from(e);endpoint.completeExceptionally(lastFailure);handler.cancelAllPendingOperations("MCP 连接不可用");if(hadEndpoint)try{failure.run();}catch(RuntimeException callbackError){if(!closed)throw callbackError;}}}finally{input=null;}
  });}
  public CompletableFuture<JsonNode> initialize(McpInitializeRequest request){return executeOperationWithResponse(request).thenCompose(result->send(new McpInitializationNotification(),false).thenApply(ignored->result));}
  public CompletableFuture<JsonNode> executeOperationWithResponse(McpClientMessage message){return send(message,true);}
  public void executeOperationWithoutResponse(McpClientMessage message){send(message,false);}
  private CompletableFuture<JsonNode> send(McpClientMessage message,boolean response){
    var result=new CompletableFuture<JsonNode>();if(closed)return CompletableFuture.failedFuture(new IOException("MCP 连接已关闭"));if(response)handler.startOperation(message.getId(),result);
    workers.execute(()->{try{URI post=endpoint.get(15,TimeUnit.SECONDS);if(cloud)NetworkPolicy.publicPage(post.toString());var reply=http.send(request(post).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message))).build(),HttpResponse.BodyHandlers.ofInputStream());
      try(var body=reply.body()){if(reply.statusCode()<200||reply.statusCode()>=300)throw McpConnectionFailure.http(reply.statusCode());if(body.readNBytes(1024*1024+1).length>1024*1024)throw new IOException("MCP 响应超过 1MiB");}if(!response)result.complete(json.nullNode());
    }catch(Exception e){lastFailure=McpConnectionFailure.from(e);result.completeExceptionally(lastFailure);}});return result;
  }
  McpConnectionFailure connectionFailure(Throwable error){return lastFailure!=null?lastFailure:McpConnectionFailure.from(error);}
  public void checkHealth(){if(closed||endpoint.isCompletedExceptionally())throw new IllegalStateException("MCP 连接不可用，请重新连接");}
  public void onFailure(Runnable failure){this.failure=failure;}
  public void close()throws IOException{closed=true;endpoint.completeExceptionally(new IOException("MCP 连接已关闭"));if(input!=null)input.close();if(handler!=null)handler.cancelAllPendingOperations("MCP 连接已关闭");workers.shutdownNow();}
}
