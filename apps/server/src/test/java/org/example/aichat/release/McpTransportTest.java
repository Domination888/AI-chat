package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.springframework.mock.env.MockEnvironment;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.io.OutputStream;

class McpTransportTest {
 @Test void sseInitializeDiscoverAndCallRemainOnOneOrigin()throws Exception{
  var json=new ObjectMapper();var server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);var executor=Executors.newFixedThreadPool(4);server.setExecutor(executor);var released=new CountDownLatch(1);var stream=new CompletableFuture<OutputStream>();var methods=new CopyOnWriteArrayList<String>();
  server.createContext("/sse",exchange->{exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);try(var output=exchange.getResponseBody()){stream.complete(output);output.write("event:endpoint\ndata:/messages?session=test\n\n".getBytes(StandardCharsets.UTF_8));output.flush();try{released.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}});
  server.createContext("/messages",exchange->{try{assertEquals("Bearer test-only",exchange.getRequestHeaders().getFirst("Authorization"));var message=json.readTree(exchange.getRequestBody());String method=message.path("method").asText();methods.add(method);if(message.hasNonNull("id")){
    Object result=switch(method){case "initialize"->Map.of("protocolVersion","2024-11-05","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","fixture","version","1"));case "tools/list"->Map.of("tools",List.of(Map.of("name","echo","description","return the argument","inputSchema",Map.of("type","object","properties",Map.of("text",Map.of("type","string"))))));case "tools/call"->{boolean fail=message.path("params").path("arguments").path("text").asText().equals("server-error");yield Map.of("content",List.of(Map.of("type","text","text",fail?"body-private token-private":message.path("params").path("arguments").path("text").asText())),"isError",fail);}default->Map.of();};
    var output=stream.get(1,TimeUnit.SECONDS);synchronized(output){output.write(("event:message\ndata:"+json.writeValueAsString(Map.of("jsonrpc","2.0","id",message.get("id"),"result",result))+"\n\n").getBytes(StandardCharsets.UTF_8));output.flush();}}
    exchange.sendResponseHeaders(202,-1);
   }catch(Exception e){exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}});
  server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline"));
  var transport=new SseMcpTransport(base+"/sse",Map.of("Authorization","Bearer test-only"),mode,json);
  try(var client=new DefaultMcpClient.Builder().transport(transport).initializationTimeout(Duration.ofSeconds(5)).toolExecutionTimeout(Duration.ofSeconds(5)).build()){
   assertEquals("echo",client.listTools().get(0).name());assertEquals("迁移成功",McpRegistry.executeClient(client,"echo",json.readTree("{\"text\":\"迁移成功\"}")));assertTrue(methods.contains("notifications/initialized"));var rejected=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->McpRegistry.executeClient(client,"echo",json.readTree("{\"text\":\"server-error\"}")));assertEquals(502,rejected.getStatusCode().value());assertFalse(rejected.getReason().contains("private"));assertTrue(rejected.getReason().contains("工具执行失败"));assertThrows(IllegalArgumentException.class,()->transport.postAddress("http://127.0.0.1:1/private"));assertThrows(IllegalArgumentException.class,()->transport.postAddress("https://example.com/messages"));
  }finally{released.countDown();server.stop(0);executor.shutdownNow();}
 }
 @Test void commercialTransportRequiresItsRestrictedProxy()throws Exception{var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","cloud"));var error=assertThrows(IllegalArgumentException.class,()->new SseMcpTransport("https://8.8.8.8/sse",Map.of(),mode,new ObjectMapper()));assertTrue(error.getMessage().contains("出站代理"));}
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({"401,mcp_auth_required,鉴权","403,mcp_access_denied,拒绝访问","404,mcp_endpoint_missing,地址不存在","410,mcp_endpoint_retired,已停用","405,mcp_transport_mismatch,请求方式","429,mcp_rate_limited,限流","503,mcp_http_error,HTTP 503"})
 void initializeHttpErrorsKeepSafeRecoveryDetails(int status,String code,String hint)throws Exception{
  var server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/sse",exchange->{exchange.getResponseHeaders().add("Content-Type","text/plain");exchange.getResponseHeaders().add("X-Secret","header-private");byte[] body="body-private token-private".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});
  server.start();
  try{
   var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline"));
   var transport=new SseMcpTransport("http://127.0.0.1:"+server.getAddress().getPort()+"/sse",Map.of("Authorization","Bearer token-private"),mode,new ObjectMapper());
   var error=assertThrows(McpConnectionFailure.class,()->McpRegistry.connect(transport,"safe-error-fixture"));
   assertEquals(code,error.code());assertTrue(error.getMessage().contains(hint),error.getMessage());
   var response=new ApiErrors().mcpConnection(error);assertEquals(502,response.getStatusCode().value());
   String text=response.getBody().toString();for(String secret:List.of("body-private","header-private","token-private","127.0.0.1"))assertFalse(text.contains(secret),text);
   assertEquals(code,((Map<?,?>)response.getBody()).get("code"));
  }finally{server.stop(0);}
 }
 @Test void successfulHttpWithoutSseIsAProtocolError()throws Exception{
  var server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);server.createContext("/sse",exchange->{exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
  try{var transport=new SseMcpTransport("http://127.0.0.1:"+server.getAddress().getPort()+"/sse",Map.of(),new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline")),new ObjectMapper());var error=assertThrows(McpConnectionFailure.class,()->McpRegistry.connect(transport,"protocol-fixture"));assertEquals("mcp_transport_mismatch",error.code());assertTrue(error.getMessage().contains("Streamable HTTP"));}finally{server.stop(0);}
 }
 @Test void refusedConnectionIsNotReportedAsAuthenticationOrTimeout()throws Exception{
  int port;try(var socket=new java.net.ServerSocket(0,0,java.net.InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
  var transport=new SseMcpTransport("http://127.0.0.1:"+port+"/sse",Map.of(),new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline")),new ObjectMapper());
  var error=assertThrows(McpConnectionFailure.class,()->McpRegistry.connect(transport,"refused-fixture"));assertEquals("mcp_connection_failed",error.code());assertTrue(error.getMessage().contains("服务已启动"));
 }
 @Test void timeoutAndTlsClassificationsNeverExposeRawExceptionDetails(){
  for(Throwable timeout:List.of(new TimeoutException("token-private"),new java.net.http.HttpTimeoutException("header-private"),new java.net.SocketTimeoutException("body-private"))){var error=McpConnectionFailure.from(new CompletionException(timeout));assertEquals("mcp_timeout",error.code());assertFalse(error.getMessage().contains("private"));}
  var tls=McpConnectionFailure.from(new java.io.IOException(new javax.net.ssl.SSLException("certificate-private")));assertEquals("mcp_tls_error",tls.code());assertTrue(tls.getMessage().contains("不会跳过"));assertFalse(tls.getMessage().contains("certificate-private"));
 }

 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(ints={401,405})
 void initializationPostErrorsAlsoKeepTheirHttpClassification(int status)throws Exception{
  var server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);var executor=Executors.newFixedThreadPool(2);server.setExecutor(executor);var released=new CountDownLatch(1);
  server.createContext("/sse",exchange->{exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);try(var out=exchange.getResponseBody()){out.write("event: endpoint\ndata: /messages\n\n".getBytes(StandardCharsets.UTF_8));out.flush();try{released.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}});
  server.createContext("/messages",exchange->{exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(status,-1);exchange.close();});server.start();
  try{var transport=new SseMcpTransport("http://127.0.0.1:"+server.getAddress().getPort()+"/sse",Map.of(),new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline")),new ObjectMapper());var error=assertThrows(McpConnectionFailure.class,()->McpRegistry.connect(transport,"post-error-fixture"));assertEquals(McpConnectionFailure.http(status).code(),error.code());assertTrue(error.getMessage().contains("HTTP "+status));}finally{released.countDown();server.stop(0);executor.shutdownNow();}
 }

}
