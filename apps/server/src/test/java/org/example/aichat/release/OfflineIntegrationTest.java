package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.util.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class OfflineIntegrationTest {
  static final Path data;static final HttpServer model;
  static final String TOKEN="test-only-local-token-12345678901234567890";
  static {
    try {data=Files.createTempDirectory("story-offline-test-");model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      model.createContext("/v1/chat/completions",r->{String request=new String(r.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);if(request.contains("__FAIL_CHAT_UX__")){r.sendResponseHeaders(502,-1);r.close();return;}String payload="data: {\"choices\":[{\"delta\":{\"content\":\"你好。<sce\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"ne>{\\\"location\\\":\\\"花园\\\"}</scene>\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";r.getResponseHeaders().set("Content-Type","text/event-stream");r.sendResponseHeaders(200,0);r.getResponseBody().write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));r.close();});
      model.createContext("/v1/embeddings",r->{r.getRequestBody().readAllBytes();String body="{\"data\":[{\"embedding\":["+String.join(",",Collections.nCopies(1024,"0.1"))+"]}]}";r.sendResponseHeaders(200,0);r.getResponseBody().write(body.getBytes());r.close();});model.start();
    }catch(Exception e){throw new RuntimeException(e);}
  }
  @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("story.mode",()->"offline");r.add("story.data-dir",()->data.toString());r.add("spring.datasource.url",()->"jdbc:sqlite:"+data.resolve("test.sqlite")+"?busy_timeout=10000&journal_mode=WAL");r.add("story.local-token",()->TOKEN);r.add("story.llm-url",()->"http://127.0.0.1:"+model.getAddress().getPort()+"/v1");r.add("story.embedding-url",()->"http://127.0.0.1:"+model.getAddress().getPort()+"/v1");}
  @Autowired TestRestTemplate client;@Autowired ObjectMapper json;@Autowired StoryStore stories;
  @LocalServerPort int port;
  @AfterAll static void shutdown(){model.stop(0);}
  ResponseEntity<String> call(String path,HttpMethod method,Object body,boolean authenticated){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(authenticated)h.setBearerAuth(TOKEN);return client.exchange(path,method,new HttpEntity<>(body,h),String.class);}
  JsonNode result(ResponseEntity<String> response)throws Exception{assertTrue(response.getStatusCode().is2xxSuccessful(),response.getBody());return response.getBody()==null?null:json.readTree(response.getBody());}
  @Test void endToEndWorldStateAttachmentsBranchAndRewind()throws Exception{
    int role=result(call("/api/roles",HttpMethod.GET,null,true)).get(0).path("id").asInt();String a="conv_"+UUID.randomUUID();
    var session=result(call("/api/rp/sessions",HttpMethod.POST,Map.of("conversationId",a,"userId",999,"roleId",role,"state",Map.of("location","书店","relationship","初识")),true));
    assertEquals(1,stories.db().queryForObject("SELECT user_id FROM conversation WHERE id=?",Integer.class,a));
    var reply=call("/api/chat",HttpMethod.POST,Map.of("conversationId",a,"roleId",role,"message","一起去花园吧"),true);assertEquals(200,reply.getStatusCode().value());assertTrue(reply.getBody().contains("你好。"));assertFalse(reply.getBody().contains("<scene>"));assertTrue(reply.getBody().contains("event:done"));
    assertEquals("花园",result(call("/api/rp/"+a,HttpMethod.GET,null,true)).path("state").path("location").asText());
    var history=result(call("/api/conversation/"+a+"/history",HttpMethod.GET,null,true));long first=history.get(0).path("id").asLong(),last=history.get(1).path("id").asLong();
    String b=result(call("/api/rp/"+a+"/branch/"+last,HttpMethod.POST,Map.of(),true)).path("conversationId").asText();assertNotEquals(stories.sessions().worldId(a),stories.sessions().worldId(b));assertEquals(2,result(call("/api/conversation/"+b+"/history",HttpMethod.GET,null,true)).size());
    result(call("/api/rp/"+a+"/rewind/"+first,HttpMethod.POST,Map.of(),true));assertEquals("书店",stories.sessions().state(a).location);assertEquals("花园",stories.sessions().state(b).location);assertEquals(0,result(call("/api/conversation/"+a+"/history",HttpMethod.GET,null,true)).size());
  }
  @Test void branchTitlesPreserveSourceNameAndAvoidOnlyTheOwnersTitles()throws Exception{
    int role=result(call("/api/roles",HttpMethod.GET,null,true)).get(0).path("id").asInt();
    String source="title_source_"+UUID.randomUUID(),name="雨夜归途";
    result(call("/api/rp/sessions",HttpMethod.POST,Map.of("conversationId",source,"roleId",role,"state",Map.of("location","书店")),true));
    assertEquals("书店",stories.owned(1,source).get("title"));
    // A manually named chapter must supply the branch name, rather than its scene location.
    stories.db().update("UPDATE conversation SET title=? WHERE id=?",name,source);
    var original=stories.owned(1,source);
    result(call("/api/rp/"+source+"/greeting",HttpMethod.POST,Map.of(),true));
    long through=result(call("/api/conversation/"+source+"/history",HttpMethod.GET,null,true)).get(0).path("id").asLong();
    String foreign="title_foreign_"+UUID.randomUUID();
    stories.create(2,foreign,role,null,new org.example.aichat.rp.RpState());
    stories.db().update("UPDATE conversation SET title=? WHERE id=?",name+" · 分支 1",foreign);
    String first=result(call("/api/rp/"+source+"/branch/"+through,HttpMethod.POST,Map.of(),true)).path("conversationId").asText();
    assertEquals(name+" · 分支 1",stories.owned(1,first).get("title"),"Another user's title must not consume this user's number");
    var firstBefore=stories.owned(1,first);
    String reserved="title_reserved_"+UUID.randomUUID();
    stories.create(1,reserved,role,null,new org.example.aichat.rp.RpState());
    stories.db().update("UPDATE conversation SET title=? WHERE id=?",name+" · 分支 2",reserved);
    var reservedBefore=stories.owned(1,reserved);
    String second=result(call("/api/rp/"+source+"/branch/"+through,HttpMethod.POST,Map.of(),true)).path("conversationId").asText();
    Map<String,String> titles=new HashMap<>();
    for(var chapter:result(call("/api/v1/conversations",HttpMethod.GET,null,true)))titles.put(chapter.path("id").asText(),chapter.path("title").asText());
    assertEquals(name,titles.get(source));assertEquals(name+" · 分支 1",titles.get(first));
    assertEquals(name+" · 分支 3",titles.get(second),"Every existing title owned by the user must be reserved");
    assertEquals(3,Set.of(titles.get(source),titles.get(first),titles.get(second)).size());
    assertFalse(titles.containsKey(foreign));
    assertEquals(original,stories.owned(1,source));assertEquals(firstBefore,stories.owned(1,first));
    assertEquals(reservedBefore,stories.owned(1,reserved));assertEquals(name+" · 分支 1",stories.owned(2,foreign).get("title"));
  }
  @Test @Timeout(20) void failedReplyIsPersistedBeforeTheClientReceivesTheErrorEvent()throws Exception{
    int role=result(call("/api/roles",HttpMethod.GET,null,true)).get(0).path("id").asInt();
    String conversation="failure_"+UUID.randomUUID(),message="__FAIL_CHAT_UX__ 请介绍一本书";
    result(call("/api/rp/sessions",HttpMethod.POST,Map.of("conversationId",conversation,"roleId",role,"state",Map.of("location","书店")),true));
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/chat"))
      .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+TOKEN).header("Content-Type","application/json")
      .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("conversationId",conversation,"roleId",role,"message",message)))).build();
    var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200,response.statusCode());
    // Read live SSE frames, not a buffered response: the UI reloads history as soon as
    // it receives error, while the stream can still be open on the server.
    try(var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))){
      String event="",payload="",line;boolean errorReceived=false;
      while((line=reader.readLine())!=null){
        if(line.startsWith("event:"))event=line.substring(6).strip();
        else if(line.startsWith("data:"))payload+=line.substring(5).strip();
        else if(line.isEmpty()){
          if("error".equals(event)){
            assertTrue(json.readTree(payload).path("message").asText().contains("502"),payload);
            // Deliberately no polling, retries or waiting for EOF before this GET.
            var history=result(call("/api/conversation/"+conversation+"/history",HttpMethod.GET,null,true));
            assertEquals(2,history.size());assertEquals(message,history.get(0).path("content").asText());
            var reply=history.get(1);assertEquals("assistant",reply.path("sender").asText());
            assertEquals("failed",reply.path("status").asText(),"The error event must not expose an unclassified empty reply");
            assertTrue(reply.path("turn_id").asLong()>0);assertEquals("",reply.path("content").asText());
            errorReceived=true;break;
          }
          event="";payload="";
        }
      }
      assertTrue(errorReceived,"The failed model request must send an SSE error frame");
    }
  }
  @Test void nativeMemoryHasAnExplicitInstantAndBranchPreservesIt()throws Exception{
    int role=result(call("/api/roles",HttpMethod.GET,null,true)).get(0).path("id").asInt();
    String conversation="memory_time_"+UUID.randomUUID();
    result(call("/api/rp/sessions",HttpMethod.POST,Map.of("conversationId",conversation,"roleId",role,"state",Map.of()),true));
    var before=java.time.Instant.now().minusSeconds(1);
    var reply=call("/api/chat",HttpMethod.POST,Map.of("conversationId",conversation,"roleId",role,"message","记下这段相遇"),true);
    assertEquals(HttpStatus.OK,reply.getStatusCode());assertTrue(reply.getBody().contains("event:done"));
    var memory=result(call("/api/memory?conversationId="+conversation,HttpMethod.GET,null,true));
    assertEquals(1,memory.size());
    String recorded=memory.get(0).path("created_at").asText();
    var instant=java.time.Instant.parse(recorded);
    assertFalse(instant.isBefore(before));assertFalse(instant.isAfter(java.time.Instant.now()));
    long history=memory.get(0).path("history_id").asLong();
    String branch=result(call("/api/rp/"+conversation+"/branch/"+history,HttpMethod.POST,Map.of(),true)).path("conversationId").asText();
    var copied=result(call("/api/memory?conversationId="+branch,HttpMethod.GET,null,true));
    assertEquals(recorded,copied.get(0).path("created_at").asText(),"A branch copies when the event happened, not the current local wall clock");
    // Imported values without a zone remain unchanged rather than silently
    // acquiring the computer's current zone or being shifted by eight hours.
    stories.db().update("UPDATE rp_turn SET created_at=? WHERE conversation_id=?","2024-01-02 03:04:05",branch);
    assertEquals("2024-01-02 03:04:05",result(call("/api/memory?conversationId="+branch,HttpMethod.GET,null,true)).get(0).path("created_at").asText());
  }
  @Test void authorizationCoversStoriesSettingsAndAdmin()throws Exception{
    assertEquals(401,call("/api/roles",HttpMethod.GET,null,false).getStatusCode().value());assertEquals(401,call("/api/v1/me",HttpMethod.GET,null,false).getStatusCode().value());assertEquals(403,call("/api/v1/admin/users",HttpMethod.GET,null,true).getStatusCode().value());
    assertEquals(404,call("/api/conversation/missing/history",HttpMethod.GET,null,true).getStatusCode().value());
    int role=result(call("/api/roles",HttpMethod.GET,null,true)).get(0).path("id").asInt();String id="private_"+UUID.randomUUID();stories.create(2,id,role,null,new org.example.aichat.rp.RpState());
    assertEquals(404,call("/api/rp/"+id,HttpMethod.GET,null,true).getStatusCode().value());assertEquals(404,call("/api/chat/interrupt",HttpMethod.POST,Map.of("conversationId",id),true).getStatusCode().value());
    assertEquals(400,call("/api/v1/me/model-connection",HttpMethod.PUT,Map.of("baseUrl","https://example.com/v1","model","test","apiKey","secret","allowNetwork",false),true).getStatusCode().value());
  }
  @Test void encryptedKeyAndSignedAttachments()throws Exception{
    result(call("/api/v1/me/model-connection",HttpMethod.PUT,Map.of("baseUrl","http://127.0.0.1:12345/v1","model","test","apiKey","secret-key-value"),true));
    String metadata=call("/api/v1/me/model-connection",HttpMethod.GET,null,true).getBody();assertFalse(metadata.contains("secret-key-value"));assertFalse(stories.db().queryForObject("SELECT key_cipher FROM model_connection WHERE user_id=1",String.class).contains("secret-key-value"));result(call("/api/v1/me/model-connection",HttpMethod.DELETE,null,true));
    var files=new Attachments(stories.db(),new FilesystemObjects(new RuntimeMode(new org.springframework.mock.env.MockEnvironment().withProperty("story.data-dir",data.toString()))),json,new RuntimeMode(new org.springframework.mock.env.MockEnvironment().withProperty("story.data-dir",data.toString())));
    String image="data:image/png;base64,"+Base64.getEncoder().encodeToString(new byte[]{(byte)137,80,78,71,13,10,26,10,0,0,0,0});var refs=files.store(1,"test-attachments",List.of(image));String ref=refs.get(0);assertTrue(ref.startsWith("attachment:"));assertThrows(Exception.class,()->files.forModel(2,refs));assertThrows(Exception.class,()->files.content(1,ref.substring(11),java.time.Instant.now().getEpochSecond()+60,"fake"));
  }
  @Test void externalModelRequiresOptInAndPreservesSavedCredentials()throws Exception{
    var settings=new HashMap<String,Object>(Map.of("baseUrl","https://api.provider.invalid/v1","model","deepseek-flash","apiKey","offline-proxy-test-secret","allowNetwork",false));
    assertEquals(400,call("/api/v1/me/model-connection",HttpMethod.PUT,settings,true).getStatusCode().value());
    settings.put("allowNetwork",true);
    try {
      result(call("/api/v1/me/model-connection",HttpMethod.PUT,settings,true));
      var metadata=result(call("/api/v1/me/model-connection",HttpMethod.GET,null,true));
      assertTrue(metadata.path("custom").asBoolean());assertTrue(metadata.path("allowNetwork").asBoolean());assertTrue(metadata.path("hasKey").asBoolean());assertFalse(metadata.toString().contains("offline-proxy-test-secret"));
      settings.put("apiKey","");result(call("/api/v1/me/model-connection",HttpMethod.PUT,settings,true));
      assertTrue(result(call("/api/v1/me/model-connection",HttpMethod.GET,null,true)).path("hasKey").asBoolean());
    } finally {result(call("/api/v1/me/model-connection",HttpMethod.DELETE,null,true));}
  }
  @Test void portableExportDoesNotExposeModelKeys()throws Exception{
    var data=result(call("/api/v1/me/data",HttpMethod.GET,null,true));assertEquals("story-space-v1",data.path("format").asText());assertTrue(data.has("history"));assertFalse(data.has("model_connection"));assertFalse(data.has("account"));
  }
  @Test void electronOriginReceivesCorsHeadersAndForeignWebsitesDoNot(){
    var headers=new HttpHeaders();headers.setOrigin("app://story");var allowed=client.exchange("/api/health",HttpMethod.GET,new HttpEntity<>(null,headers),String.class);
    assertEquals("app://story",allowed.getHeaders().getAccessControlAllowOrigin());headers.setOrigin("https://untrusted.example");var denied=client.exchange("/api/health",HttpMethod.GET,new HttpEntity<>(null,headers),String.class);assertNull(denied.getHeaders().getAccessControlAllowOrigin());
  }
}
