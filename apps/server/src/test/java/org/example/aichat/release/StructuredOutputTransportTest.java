package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StructuredOutputTransportTest {
  static final ObjectMapper JSON=new ObjectMapper();
  final SkillOutputContract contract=new SkillOutputContract(2,SkillOutputContract.Question.ONE,false);
  interface Reply { String get(int index); }
  void fixture(int firstStatus,String error,Reply reply,java.util.function.Consumer<Fixture> test)throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var requests=new ArrayList<JsonNode>();
    server.createContext("/v1/chat/completions",exchange->{try{requests.add(JSON.readTree(exchange.getRequestBody()));int status=requests.size()==1?firstStatus:200;String body=status==200?reply.get(requests.size()):error;byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",status==200?"text/event-stream":"application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});server.start();
    try{var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline").withProperty("story.data-dir",Files.createTempDirectory("structured-model-").toString()));test.accept(new Fixture(new OpenAiTransport(JSON,mode),new ModelConnections.Connection("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture","",false),requests));}finally{server.stop(0);}
  }
  record Fixture(OpenAiTransport transport,ModelConnections.Connection connection,List<JsonNode> requests) {
    OpenAiTransport.Usage run(OpenAiTransport.Control control,StringBuilder raw,Map<String,Object> format)throws Exception{return transport.stream(connection,List.of(Map.of("role","user","content","完成当前任务")),control,raw::append,format);}
  }
  String stream(String content,String reason,boolean done)throws Exception{
    return "data: "+JSON.writeValueAsString(Map.of("choices",List.of(Map.of("delta",Map.of("content",content)))))+"\n\ndata: "+JSON.writeValueAsString(Map.of("choices",reason==null?List.of():List.of(Map.of("delta",Map.of(),"finish_reason",reason)),"usage",Map.of("prompt_tokens",11,"completion_tokens",7)))+"\n\n"+(done?"data: [DONE]\n\n":"");
  }
  @Test void ordinaryLengthKeepsPartialAndTheFinalActualUsageWithoutRetry()throws Exception{
    String body=stream("这些资料未","length",true);
    fixture(200,"",i->body,f->{try{var control=new OpenAiTransport.Control();var partial=new StringBuilder();var usage=f.transport.stream(f.connection,List.of(Map.of("role","user","content","日报")),control,partial::append);assertEquals("这些资料未",partial.toString());assertEquals("length",control.finishReason);assertEquals(new OpenAiTransport.Usage(11,7,false),usage);assertEquals(1,f.requests.size());}catch(Exception e){throw new RuntimeException(e);}});
  }
  @Test void strictSchemaUsesTheExistingStreamProtocolWithoutChangingSampling()throws Exception{
    String body=stream("{\"items\":[\"甲\",\"乙\"],\"closing\":\"\",\"question\":\"好吗？\"}","stop",true);
    fixture(200,"",i->body,f->{try{var control=new OpenAiTransport.Control();var raw=new StringBuilder();assertEquals(new OpenAiTransport.Usage(11,7,false),f.run(control,raw,contract.responseFormat()));assertTrue(contract.render(JSON.readTree(raw.toString())).startsWith("1. 甲"));assertEquals("json_schema",f.requests.get(0).at("/response_format/type").asText());assertFalse(f.requests.get(0).has("temperature"));assertEquals("stop",control.finishReason);f.transport.stream(f.connection,List.of(Map.of("role","user","content","普通聊天")),new OpenAiTransport.Control(),v->{});assertFalse(f.requests.get(1).has("response_format"));}catch(Exception e){throw new RuntimeException(e);}});
  }
  @Test void onlyExplicitUnsupportedSchemaGetsOneJsonObjectCompatibilityRequest()throws Exception{
    String body=stream("{\"items\":[\"甲\",\"乙\"],\"closing\":\"\",\"question\":\"好吗？\"}","stop",true);
    fixture(400,"{\"error\":{\"message\":\"response_format json_schema is not supported\"}}",i->body,f->{try{f.run(new OpenAiTransport.Control(),new StringBuilder(),contract.responseFormat());assertEquals(2,f.requests.size());assertEquals("json_schema",f.requests.get(0).at("/response_format/type").asText());assertEquals("json_object",f.requests.get(1).at("/response_format/type").asText());}catch(Exception e){throw new RuntimeException(e);}});
    fixture(400,"{\"error\":{\"message\":\"invalid schema properties\"}}",i->body,f->{assertThrows(java.io.IOException.class,()->f.run(new OpenAiTransport.Control(),new StringBuilder(),contract.responseFormat()));assertEquals(1,f.requests.size());});
  }
  @Test void abruptEofRetainsActualUsageAndDoesNotTryAnotherGeneration()throws Exception{
    String body=stream("{\"items\":[",null,false);fixture(200,"",i->body,f->{var control=new OpenAiTransport.Control();assertThrows(java.io.IOException.class,()->f.run(control,new StringBuilder(),contract.responseFormat()));assertEquals(new OpenAiTransport.Usage(11,7,false),control.observedUsage);assertEquals(1,f.requests.size());});
  }
  @Test void actualProviderUnavailableWordingPreservesTheWholeSchemaInOneCompatibilityCall()throws Exception{
    String body=stream("{\"items\":[\"甲\",\"乙\"],\"closing\":\"\",\"question\":\"好吗？\"}","stop",true);
    for(int status:List.of(400,422))fixture(status,"{\"error\":{\"message\":\"This response_format type is unavailable now (request_id: fixture)\",\"type\":\"invalid_request_error\"}}",i->body,f->{try{
      var raw=new StringBuilder();var usage=f.run(new OpenAiTransport.Control(),raw,contract.responseFormat());assertEquals(new OpenAiTransport.Usage(11,7,false),usage);assertTrue(contract.render(JSON.readTree(raw.toString())).contains("好吗？"));assertEquals(2,f.requests.size());
      var first=f.requests.get(0);var retry=f.requests.get(1);assertEquals("json_object",retry.at("/response_format/type").asText());
      var retryMessages=(com.fasterxml.jackson.databind.node.ArrayNode)retry.path("messages").deepCopy();String schemaPrompt=retryMessages.remove(0).path("content").asText();
      assertTrue(schemaPrompt.contains(JSON.writeValueAsString(first.at("/response_format/json_schema/schema"))));assertEquals(first.path("messages"),retryMessages);assertEquals(first.path("model"),retry.path("model"));assertFalse(retry.has("tools"));
    }catch(Exception e){throw new RuntimeException(e);}});
  }
  @Test void unrelatedFailuresAndInvalidSchemasAreNotFormatCompatibilitySignals(){
    for(String detail:List.of("model unavailable now", "max_tokens invalid type", "response_format invalid schema properties", "billing temporarily unavailable"))assertFalse(OpenAiTransport.schemaFormatUnavailable(detail),detail);
  }
  void hangingErrorBody(boolean cancel)throws Exception{
    var headersSent=new CountDownLatch(1);var release=new CountDownLatch(1);var requests=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var executor=Executors.newSingleThreadExecutor();server.setExecutor(executor);
    server.createContext("/v1/chat/completions",exchange->{try{requests.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(400,0);exchange.getResponseBody().write("{\"error\":{\"message\":\"json_schema not supported".getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();headersSent.countDown();release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
    try{
      var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline").withProperty("story.data-dir",Files.createTempDirectory("structured-cancel-").toString()));var fixture=new Fixture(new OpenAiTransport(JSON,mode),new ModelConnections.Connection("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture","",false),List.of());var control=new OpenAiTransport.Control();
      long started=System.nanoTime();var pending=CompletableFuture.supplyAsync(()->{try{fixture.run(control,new StringBuilder(),contract.responseFormat());return null;}catch(Exception failure){return failure;}});assertTrue(headersSent.await(2,TimeUnit.SECONDS));if(cancel)control.stop();Exception failure=pending.get(cancel?1:4,TimeUnit.SECONDS);assertNotNull(failure);assertEquals(1,requests.get());if(!cancel){assertTrue(failure.getMessage().contains("超时"));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<3500);}
    }finally{release.countDown();server.stop(0);executor.shutdownNow();}
  }
  @Test void cancellationInterruptsAHangingFirstSchemaErrorWithoutCompatibilityCall()throws Exception{hangingErrorBody(true);}
  @Test void anUnfinishedSchemaErrorHasAHardTimeLimitWithoutCompatibilityCall()throws Exception{hangingErrorBody(false);}
}
