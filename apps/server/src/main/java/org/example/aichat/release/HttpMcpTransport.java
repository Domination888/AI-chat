package org.example.aichat.release;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.mcp.client.protocol.*;
import dev.langchain4j.mcp.client.transport.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Streamable HTTP MCP. Sessions, credentials and responses stay on one validated endpoint. */
final class HttpMcpTransport implements McpTransport {
  private static final int MESSAGE_LIMIT=1024*1024,STREAM_LIMIT=4*MESSAGE_LIMIT,SESSION_LIMIT=1024;
  private static final Set<String> VERSIONS=Set.of("2025-06-18","2025-03-26","2024-11-05");
  private static final Set<String> CONTROL_HEADERS=Set.of("accept","content-type","mcp-session-id","mcp-protocol-version","host","content-length","connection","transfer-encoding","upgrade");
  private final URI address;private final boolean cloud;private final ObjectMapper json;private final Map<String,String> headers;private final HttpClient http;private final Duration timeout;
  private final ExecutorService workers=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"mcp-http");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
  private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"mcp-http-timeout");t.setDaemon(true);return t;});
  private final Set<Operation> active=ConcurrentHashMap.newKeySet();private final Object sessionLock=new Object();
  private volatile McpOperationHandler handler;private volatile ObjectNode initializeMessage;private volatile String sessionId,protocolVersion;private volatile boolean initialized,closed;private volatile McpConnectionFailure lastFailure;

  HttpMcpTransport(String address,Map<String,String> headers,RuntimeMode mode,ObjectMapper json){this(address,headers,mode,json,Duration.ofSeconds(20));}
  // A shorter deadline is useful to verify stalled body handling without slow tests.
  HttpMcpTransport(String address,Map<String,String> headers,RuntimeMode mode,ObjectMapper json,Duration timeout){
    this.address=URI.create(address);this.cloud=mode.cloud();this.json=json;this.timeout=timeout;
    if(timeout.isZero()||timeout.isNegative()||timeout.compareTo(Duration.ofSeconds(20))>0)throw new IllegalArgumentException("MCP 请求超时须在 20 秒以内");
    if(this.address.getUserInfo()!=null||this.address.getFragment()!=null)throw new IllegalArgumentException("MCP 地址不允许用户信息或片段");
    validateAddress();var safeHeaders=new LinkedHashMap<String,String>();
    for(var entry:headers.entrySet()){
      String name=entry.getKey(),value=entry.getValue();
      if(name==null||!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")||value==null||value.length()>8192||value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw new IllegalArgumentException("MCP 请求头格式不正确");
      if(CONTROL_HEADERS.contains(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("MCP 协议、会话和传输请求头由应用管理，请仅填写服务鉴权等自定义请求头");
      safeHeaders.put(name,value);
    }
    this.headers=Map.copyOf(safeHeaders);
    var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER);
    if(cloud){String proxy=mode.value("byok-proxy-host","");if(proxy.isBlank())throw new IllegalArgumentException("商业 MCP 公网出站代理未配置");builder.proxy(ProxySelector.of(new InetSocketAddress(proxy,mode.number("byok-proxy-port",3128))));}
    http=builder.build();
  }

  private void validateAddress(){if(cloud)NetworkPolicy.publicHttps(address.toString());else{try{NetworkPolicy.requireLoopback(address.toString());}catch(IllegalArgumentException e){NetworkPolicy.publicHttps(address.toString());}}}
  private HttpRequest.Builder request(String session,String protocol,Duration deadline){
    validateAddress();var builder=HttpRequest.newBuilder(address).timeout(deadline);headers.forEach(builder::header);
    builder.setHeader("Accept","application/json, text/event-stream").setHeader("Content-Type","application/json");
    if(session!=null)builder.setHeader("Mcp-Session-Id",session);if(protocol!=null)builder.setHeader("MCP-Protocol-Version",protocol);return builder;
  }
  public void start(McpOperationHandler handler){if(closed)throw McpConnectionFailure.closed();this.handler=Objects.requireNonNull(handler);}
  public CompletableFuture<JsonNode> initialize(McpInitializeRequest message){
    initializeMessage=json.valueToTree(message);
    return submit(operation->{synchronized(sessionLock){return initializeSession(operation);}});
  }
  private JsonNode initializeSession(Operation operation)throws Exception{
    operation.check();initialized=false;sessionId=null;protocolVersion=null;
    var message=initializeMessage;if(message==null)throw McpConnectionFailure.protocol();
    var reply=exchange(message,null,null,true,true,operation);
    String version=reply.path("result").path("protocolVersion").asText();
    if(!VERSIONS.contains(version))throw McpConnectionFailure.protocolVersion();
    protocolVersion=version;
    exchange(json.valueToTree(new McpInitializationNotification()),sessionId,version,false,false,operation);
    operation.check();
    initialized=true;return reply;
  }
  public CompletableFuture<JsonNode> executeOperationWithResponse(McpClientMessage message){return send(message,true);}
  public void executeOperationWithoutResponse(McpClientMessage message){send(message,false);}
  private CompletableFuture<JsonNode> send(McpClientMessage message,boolean response){
    ObjectNode payload=json.valueToTree(message);
    return submit(operation->{synchronized(sessionLock){
      if(response&&!initialized)throw McpConnectionFailure.closed();
      String session=sessionId,protocol=protocolVersion;
      try{return exchange(payload,session,protocol,response,false,operation);}
      catch(SessionExpired expired){
        // A session 404 explicitly rejects the request. Reinitialize once; never
        // replay timeouts, disconnected streams or other possibly executed calls.
        if(Objects.equals(sessionId,session)||!initialized)initializeSession(operation);
        try{return exchange(payload,sessionId,protocolVersion,response,false,operation);}
        catch(SessionExpired again){throw McpConnectionFailure.sessionExpired();}
      }
    }});
  }
  @FunctionalInterface private interface Work{JsonNode run(Operation operation)throws Exception;}
  private final class Operation{
    final CompletableFuture<JsonNode> result=new CompletableFuture<>();final Set<InputStream> inputs=ConcurrentHashMap.newKeySet();volatile Future<?> task;
    void check()throws IOException{if(closed||result.isDone()||Thread.currentThread().isInterrupted())throw new IOException("MCP 请求已结束");}
    void attach(InputStream input)throws IOException{inputs.add(input);try{check();}catch(IOException e){input.close();throw e;}}
    void finishInputs(){for(var input:inputs)try{input.close();}catch(IOException ignored){}inputs.clear();}
    void fail(McpConnectionFailure error){if(result.completeExceptionally(error)){lastFailure=error;var running=task;if(running!=null)running.cancel(true);finishInputs();}}
  }
  private CompletableFuture<JsonNode> submit(Work work){
    if(closed)return CompletableFuture.failedFuture(McpConnectionFailure.closed());
    var operation=new Operation();active.add(operation);
    ScheduledFuture<?> deadline;
    try{deadline=timer.schedule(()->operation.fail(McpConnectionFailure.from(new TimeoutException())),timeout.toMillis(),TimeUnit.MILLISECONDS);}
    catch(RejectedExecutionException e){active.remove(operation);return CompletableFuture.failedFuture(McpConnectionFailure.closed());}
    operation.result.whenComplete((value,error)->{deadline.cancel(false);active.remove(operation);if(operation.result.isCancelled())operation.finishInputs();});
    try{operation.task=workers.submit(()->{try{operation.check();operation.result.complete(work.run(operation));}catch(Exception e){operation.fail(McpConnectionFailure.from(e));}finally{operation.finishInputs();active.remove(operation);}});}
    catch(RejectedExecutionException e){operation.fail(McpConnectionFailure.busy());}
    return operation.result;
  }
  private static final class SessionExpired extends IOException{}
  private JsonNode exchange(ObjectNode payload,String session,String protocol,boolean response,boolean initialize,Operation operation)throws Exception{
    operation.check();var reply=http.send(request(session,protocol,timeout).POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build(),HttpResponse.BodyHandlers.ofInputStream());
    var input=reply.body();operation.attach(input);
    try(input){
      int status=reply.statusCode();if(status==404&&session!=null)throw new SessionExpired();
      if(status<200||status>=300)throw McpConnectionFailure.http(status);
      if(initialize){String offered=reply.headers().firstValue("Mcp-Session-Id").orElse(null);if(offered!=null&&(offered.isEmpty()||offered.length()>SESSION_LIMIT||!offered.chars().allMatch(c->c>=0x21&&c<=0x7e)))throw McpConnectionFailure.protocol();sessionId=offered;}
      if(!response){byte[] body=readMessage(input);if(!new String(body,StandardCharsets.UTF_8).isBlank())throw McpConnectionFailure.protocol();return json.nullNode();}
      if(status!=200)throw McpConnectionFailure.protocol();
      String type=reply.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().toLowerCase(Locale.ROOT);
      if(type.equals("application/json"))return responseFrame(parse(readMessage(input)),payload.get("id"));
      if(type.equals("text/event-stream"))return readEvents(input,payload.get("id"),operation,initialize?sessionId:session,initialize?payload.path("params").path("protocolVersion").asText(null):protocol);
      throw McpConnectionFailure.protocol();
    }finally{operation.inputs.remove(input);}
  }
  private byte[] readMessage(InputStream input)throws IOException{byte[] body=input.readNBytes(MESSAGE_LIMIT+1);if(body.length>MESSAGE_LIMIT)throw McpConnectionFailure.messageLimit();return body;}
  private JsonNode parse(byte[] body)throws IOException{return json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);}
  private JsonNode parse(String body)throws IOException{if(body.getBytes(StandardCharsets.UTF_8).length>MESSAGE_LIMIT)throw McpConnectionFailure.messageLimit();return json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);}
  private JsonNode responseFrame(JsonNode frame,JsonNode id){
    if(frame==null||!frame.isObject()||!frame.path("jsonrpc").asText().equals("2.0")||!sameId(id,frame.get("id"))||frame.has("method")||frame.has("result")==frame.has("error"))throw McpConnectionFailure.protocol();
    return frame;
  }
  private static boolean sameId(JsonNode left,JsonNode right){if(left==null||right==null||left.isNull()||right.isNull())return false;return left.isIntegralNumber()&&right.isIntegralNumber()?left.bigIntegerValue().equals(right.bigIntegerValue()):left.equals(right);}
  private JsonNode readEvents(InputStream input,JsonNode id,Operation operation,String session,String protocol)throws Exception{
    var bounded=new FilterInputStream(input){int count;private void count(int n){if(n>0&&(count+=n)>STREAM_LIMIT)throw McpConnectionFailure.messageLimit();}public int read()throws IOException{int value=super.read();if(value!=-1)count(1);return value;}public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);count(n);return n;}};
    var reader=new BufferedReader(new InputStreamReader(bounded,StandardCharsets.UTF_8));var data=new StringBuilder();String event="",line;int frames=0;
    while((line=OpenAiTransport.boundedLine(reader))!=null){
      operation.check();
      if(line.isEmpty()){
        if(!data.isEmpty()){if(++frames>64)throw McpConnectionFailure.messageLimit();var result=event(event,data,id,operation,session,protocol);if(result!=null)return result;}
        event="";data.setLength(0);
      }else if(line.startsWith("event:"))event=line.substring(6).strip();
      else if(line.startsWith("data:")){String value=line.substring(5);if(value.startsWith(" "))value=value.substring(1);data.append(value).append('\n');if(data.length()>MESSAGE_LIMIT)throw McpConnectionFailure.messageLimit();}
    }
    if(!data.isEmpty()){var result=event(event,data,id,operation,session,protocol);if(result!=null)return result;}
    throw McpConnectionFailure.protocol();
  }
  private JsonNode event(String event,StringBuilder data,JsonNode id,Operation operation,String session,String protocol)throws Exception{
    if(!event.isEmpty()&&!event.equals("message"))return null;
    JsonNode frame=parse(data.toString());if(frame==null||!frame.isObject()||!frame.path("jsonrpc").asText().equals("2.0"))throw McpConnectionFailure.protocol();
    if(frame.has("result")||frame.has("error"))return responseFrame(frame,id);
    String method=frame.path("method").asText();if(method.isBlank())throw McpConnectionFailure.protocol();
    if(frame.hasNonNull("id")){
      if(!frame.get("id").isTextual()&&!frame.get("id").isIntegralNumber())throw McpConnectionFailure.protocol();
      // Handle server pings without waiting for initialization to finish. Unknown
      // requests receive a local error; raw remote frames never enter log output.
      var reply=json.createObjectNode().put("jsonrpc","2.0");reply.set("id",frame.get("id"));
      if(method.equals("ping"))reply.set("result",json.createObjectNode());
      else if(method.equals("roots/list"))reply.set("result",json.createObjectNode().set("roots",json.createArrayNode()));
      else reply.set("error",json.createObjectNode().put("code",-32601).put("message","Client method not supported"));
      exchange(reply,session,protocol,false,false,operation);
    }else if(method.equals("notifications/tools/list_changed")&&handler!=null)handler.handle(frame);
    return null;
  }
  McpConnectionFailure connectionFailure(Throwable error){return lastFailure!=null?lastFailure:McpConnectionFailure.from(error);}
  public void checkHealth(){if(closed||!initialized)throw McpConnectionFailure.closed();}
  // There is no permanent GET connection to fail; each bounded POST reports its own failure.
  public void onFailure(Runnable failure){}
  public void close(){
    if(closed)return;closed=true;for(var operation:active)operation.fail(McpConnectionFailure.closed());workers.shutdownNow();timer.shutdownNow();
    String session=sessionId;sessionId=null;initialized=false;
    if(session!=null)try{
      // DELETE is optional on the server (405 is allowed). Do not drain or expose
      // its body, follow redirects, or hold application shutdown for 20 seconds.
      var future=http.sendAsync(request(session,protocolVersion,Duration.ofSeconds(2)).DELETE().build(),HttpResponse.BodyHandlers.ofInputStream());
      try{var reply=future.get(2,TimeUnit.SECONDS);reply.body().close();}catch(Exception e){future.cancel(true);}
    }catch(Exception ignored){}
  }
}
