package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import org.springframework.mock.env.MockEnvironment;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class HttpMcpTransportTest extends HttpMcpTransportFixture {

  @ParameterizedTest @ValueSource(booleans={false,true})
  void jsonAndSseInitializeDiscoverCallNotifyAndCloseWithBoundSession(boolean sse)throws Exception{
    var methods=new CopyOnWriteArrayList<String>();var problem=new AtomicReference<Throwable>();var deleted=new CountDownLatch(1);var pingAnswered=new CountDownLatch(1);
    try(var fixture=new Fixture(exchange->{try{
      assertEquals("Bearer test-only",exchange.getRequestHeaders().getFirst("Authorization"));
      assertTrue(exchange.getRequestHeaders().getFirst("Accept").contains("application/json"));assertTrue(exchange.getRequestHeaders().getFirst("Accept").contains("text/event-stream"));
      if(exchange.getRequestMethod().equals("DELETE")){assertEquals("fixture-session",exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));assertEquals("2025-06-18",exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));methods.add("DELETE");deleted.countDown();exchange.sendResponseHeaders(405,-1);return;}
      assertEquals("POST",exchange.getRequestMethod());JsonNode message=JSON.readTree(exchange.getRequestBody());String method=message.path("method").asText();methods.add(method);
      if(method.equals("initialize")){assertNull(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));assertNull(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));exchange.getResponseHeaders().set("Mcp-Session-Id","fixture-session");}
      else{assertEquals("fixture-session",exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));assertEquals("2025-06-18",exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));}
      if(message.has("result")){assertEquals("server-ping",message.path("id").asText());pingAnswered.countDown();exchange.sendResponseHeaders(202,-1);return;}
      if(!message.hasNonNull("id")){assertEquals("notifications/initialized",method);exchange.sendResponseHeaders(202,-1);return;}
      Object value=switch(method){case "initialize"->initialize();case "tools/list"->tools();case "tools/call"->Map.of("content",List.of(Map.of("type","text","text",message.path("params").path("arguments").path("text").asText())),"isError",false);case "ping"->Map.of();default->throw new IllegalArgumentException("unexpected fixture method");};
      String response=result(message.get("id"),value);
      if(sse){exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");exchange.sendResponseHeaders(200,0);try(var stream=exchange.getResponseBody()){
        if(method.equals("initialize")){stream.write("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"server-ping\",\"method\":\"ping\"}\n\n".getBytes(StandardCharsets.UTF_8));stream.flush();assertTrue(pingAnswered.await(2,TimeUnit.SECONDS));}
        stream.write(": heartbeat\n\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n".getBytes(StandardCharsets.UTF_8));stream.write(("event: message\ndata: "+response+"\n\n").getBytes(StandardCharsets.UTF_8));stream.flush();
      }}else bytes(exchange,200,"application/json; charset=utf-8",response);
    }catch(Throwable e){problem.compareAndSet(null,e);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{exchange.close();}})){
      var transport=new HttpMcpTransport(fixture.url(),Map.of("Authorization","Bearer test-only"),offline(),JSON);
      try(var client=McpRegistry.connect(transport,"http-fixture")){assertEquals("echo",client.listTools().get(0).name());assertEquals("协议已连接",McpRegistry.executeClient(client,"echo",JSON.readTree("{\"text\":\"协议已连接\"}")));}
      assertTrue(deleted.await(2,TimeUnit.SECONDS));assertTrue(methods.contains("notifications/initialized"));assertTrue(methods.indexOf("notifications/initialized")<methods.indexOf("tools/list"));assertNull(problem.get(),()->String.valueOf(problem.get()));
    }
  }

  @ParameterizedTest @CsvSource({"401,mcp_auth_required","405,mcp_transport_mismatch","307,mcp_http_error"})
  void remoteHttpErrorsAndRedirectsStaySafeAndNeverFollowLocation(int status,String code)throws Exception{
    var redirected=new AtomicInteger();try(var fixture=new Fixture(exchange->{exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Location","/leak");exchange.getResponseHeaders().set("X-Private","header-private");bytes(exchange,status,"text/plain","body-private token-private");})){fixture.server.createContext("/leak",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});
      var transport=new HttpMcpTransport(fixture.url(),Map.of("Authorization","Bearer token-private"),offline(),JSON);var failure=assertThrows(McpConnectionFailure.class,()->McpRegistry.connect(transport,"safe-http-error"));assertEquals(code,failure.code());String response=new ApiErrors().mcpConnection(failure).getBody().toString();for(String secret:List.of("body-private","header-private","token-private","127.0.0.1"))assertFalse(response.contains(secret));assertEquals(0,redirected.get());
    }
  }

  @Test void expiredSessionIsReinitializedOnceBeforeTheRejectedRequestIsRepeated()throws Exception{
    var initializes=new AtomicInteger();var lists=new AtomicInteger();var calls=new AtomicInteger();var notifications=new AtomicInteger();var sessionHeaders=new CopyOnWriteArrayList<String>();var problem=new AtomicReference<Throwable>();
    try(var fixture=new Fixture(exchange->{try{
      if(exchange.getRequestMethod().equals("DELETE")){exchange.sendResponseHeaders(200,-1);return;}
      var message=JSON.readTree(exchange.getRequestBody());String method=message.path("method").asText();
      if(method.equals("initialize")){assertNull(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));exchange.getResponseHeaders().set("Mcp-Session-Id","session-"+initializes.incrementAndGet());bytes(exchange,200,"application/json",result(message.get("id"),initialize()));}
      else{String session=exchange.getRequestHeaders().getFirst("Mcp-Session-Id");sessionHeaders.add(session);if(method.equals("notifications/initialized")){notifications.incrementAndGet();exchange.sendResponseHeaders(202,-1);}
        else if(method.equals("tools/list")){if(lists.incrementAndGet()==1){exchange.sendResponseHeaders(404,-1);}else bytes(exchange,200,"application/json",result(message.get("id"),tools()));}
        else if(method.equals("tools/call")){calls.incrementAndGet();bytes(exchange,200,"application/json",result(message.get("id"),Map.of("content",List.of(Map.of("type","text","text","once")),"isError",false)));}
      }
    }catch(Throwable e){problem.compareAndSet(null,e);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{exchange.close();}})){
      try(var client=McpRegistry.connect(new HttpMcpTransport(fixture.url(),Map.of(),offline(),JSON),"session-recovery")){assertEquals("echo",client.listTools().get(0).name());assertEquals("once",McpRegistry.executeClient(client,"echo",JSON.readTree("{\"text\":\"once\"}")));}
      assertEquals(2,initializes.get());assertEquals(2,notifications.get());assertEquals(2,lists.get());assertEquals(1,calls.get());assertEquals(List.of("session-1","session-1","session-2","session-2","session-2"),sessionHeaders);assertNull(problem.get());
    }
  }

  @Test void missingProxyFailsClosedAndConfiguredProxyCannotFallBackToDirect()throws Exception{
    var missing=new RuntimeMode(new MockEnvironment().withProperty("story.mode","cloud"));assertTrue(assertThrows(IllegalArgumentException.class,()->new HttpMcpTransport("https://8.8.8.8/mcp",Map.of(),missing,JSON)).getMessage().contains("出站代理"));
    try(var proxy=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){proxy.setSoTimeout(3000);var received=new CompletableFuture<String>();var worker=Executors.newSingleThreadExecutor();
      worker.execute(()->{try(var socket=proxy.accept()){socket.setSoTimeout(3000);var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String line=reader.readLine();received.complete(line);while((line=reader.readLine())!=null&&!line.isBlank()){}socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();}catch(Exception e){received.completeExceptionally(e);}});
      try{var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","cloud").withProperty("story.byok-proxy-host","127.0.0.1").withProperty("story.byok-proxy-port",String.valueOf(proxy.getLocalPort())));var transport=new HttpMcpTransport("https://8.8.8.8/mcp",Map.of(),mode,JSON,Duration.ofSeconds(2));assertThrows(McpConnectionFailure.class,()->McpRegistry.connect(transport,"proxy-denies"));assertTrue(received.get(3,TimeUnit.SECONDS).startsWith("CONNECT 8.8.8.8:443 "));assertThrows(IllegalArgumentException.class,()->new HttpMcpTransport("http://127.0.0.1/mcp",Map.of(),mode,JSON));}finally{worker.shutdownNow();}
    }
  }

  @Test void bodyDeadlineAppliesAfterSseHeadersHaveArrived()throws Exception{
    var release=new CountDownLatch(1);try(var fixture=new Fixture(exchange->{try{exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);try(var body=exchange.getResponseBody()){body.write(": waiting\n\n".getBytes(StandardCharsets.UTF_8));body.flush();release.await(5,TimeUnit.SECONDS);}}catch(Exception ignored){}finally{exchange.close();}})){
      long started=System.nanoTime();var transport=new HttpMcpTransport(fixture.url(),Map.of(),offline(),JSON,Duration.ofMillis(300));var failure=assertThrows(McpConnectionFailure.class,()->McpRegistry.connect(transport,"stalled-body"));assertEquals("mcp_timeout",failure.code());assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(3))<0);
    }finally{release.countDown();}
  }

  @ParameterizedTest @ValueSource(strings={"application/json","text/event-stream"})
  void oversizedMessagesAreRejectedWithoutEchoingTheBody(String type)throws Exception{
    String large="x".repeat(1024*1024+1);try(var fixture=new Fixture(exchange->{exchange.getRequestBody().readAllBytes();bytes(exchange,200,type,type.equals("text/event-stream")?"event: message\ndata: "+large+"\n\n":large);})){
      var failure=assertThrows(McpConnectionFailure.class,()->McpRegistry.connect(new HttpMcpTransport(fixture.url(),Map.of(),offline(),JSON),"large-body"));assertTrue(Set.of("mcp_message_too_large","mcp_connection_failed").contains(failure.code()));assertTrue(failure.getMessage().length()<200);
    }
  }

  @ParameterizedTest @ValueSource(strings={"bad session",""})
  void sessionHeadersMustBeBoundedVisibleAscii(String session)throws Exception{
    try(var fixture=new Fixture(exchange->{try{var message=JSON.readTree(exchange.getRequestBody());exchange.getResponseHeaders().set("Mcp-Session-Id",session);bytes(exchange,200,"application/json",result(message.get("id"),initialize()));}catch(Exception ignored){exchange.close();}})){
      var failure=assertThrows(McpConnectionFailure.class,()->McpRegistry.connect(new HttpMcpTransport(fixture.url(),Map.of(),offline(),JSON),"bad-session"));assertEquals("mcp_transport_mismatch",failure.code());assertFalse(failure.getMessage().contains("bad session"));
    }
  }

  @Test void wrongResponseIdAndUnknownProtocolNeverReachTheOperationHandler()throws Exception{
    for(boolean wrongId:List.of(false,true))try(var fixture=new Fixture(exchange->{try{var message=JSON.readTree(exchange.getRequestBody());Object reply=wrongId?Map.of("jsonrpc","2.0","id","private-token","result",initialize()):Map.of("jsonrpc","2.0","id",message.get("id"),"result",Map.of("protocolVersion","future-private","capabilities",Map.of(),"serverInfo",Map.of("name","fixture","version","1")));bytes(exchange,200,"application/json",JSON.writeValueAsString(reply));}catch(Exception ignored){exchange.close();}})){
      var failure=assertThrows(McpConnectionFailure.class,()->McpRegistry.connect(new HttpMcpTransport(fixture.url(),Map.of(),offline(),JSON),"wrong-frame"));assertEquals(wrongId?"mcp_transport_mismatch":"mcp_protocol_version",failure.code());assertFalse(failure.getMessage().contains("private"));
    }
  }

  @Test void userHeadersCannotOverrideTransportSessionOrProtocol()throws Exception{for(String header:List.of("mcp-session-id","MCP-Protocol-Version","Accept","Content-Type","Host"))assertTrue(assertThrows(IllegalArgumentException.class,()->new HttpMcpTransport("http://127.0.0.1:1/mcp",Map.of(header,"private-value"),offline(),JSON)).getMessage().contains("由应用管理"));}
}
