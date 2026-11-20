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

class McpRegistryTransportTest extends HttpMcpTransportFixture {

  @Test void registryKeepsBlankCredentialsOnlyAtTheSameRemoteAddress()throws Exception{
    var mode=offline();var dataSource=new org.sqlite.SQLiteDataSource();dataSource.setUrl("jdbc:sqlite:"+mode.data().resolve("config.sqlite"));var db=new org.springframework.jdbc.core.JdbcTemplate(dataSource);db.execute("CREATE TABLE companion_mcp(user_id INTEGER,id TEXT,config_json TEXT,PRIMARY KEY(user_id,id))");var stories=mock(StoryStore.class);when(stories.db()).thenReturn(db);var registry=new McpRegistry(stories,mode,JSON);var firstHeaders=new CopyOnWriteArrayList<String>();var secondHeaders=new CopyOnWriteArrayList<String>();
    try(var first=credentialFixture(firstHeaders);var second=credentialFixture(secondHeaders)){
      var config=JSON.createObjectNode().put("id","scope").put("name","credential scope").put("transport","http").put("url",first.url()).put("enabled",true);config.set("headers",JSON.valueToTree(Map.of("Authorization","Bearer old-fixture-only")));registry.save(1,config);assertEquals("echo",registry.tools(1,"scope").get(0).get("name"));assertFalse(firstHeaders.isEmpty());assertTrue(firstHeaders.stream().allMatch("Bearer old-fixture-only"::equals));
      config.put("transport","streamable-http");config.set("headers",JSON.createObjectNode());registry.save(1,config);assertEquals("http",registry.list(1).get(0).path("transport").asText());assertEquals("echo",registry.tools(1,"scope").get(0).get("name"));assertTrue(firstHeaders.stream().allMatch("Bearer old-fixture-only"::equals));
      config.put("url",second.url());registry.save(1,config);assertEquals("echo",registry.tools(1,"scope").get(0).get("name"));assertFalse(secondHeaders.isEmpty());assertTrue(secondHeaders.stream().allMatch(String::isEmpty),"A new endpoint must never receive the previous endpoint's Authorization");assertFalse(registry.list(1).get(0).path("hasSecrets").asBoolean());
    }finally{registry.close();}
  }

  @Test void stdioEnvironmentIsScopedToTheExactCommandAndNeverRetainedForHttp()throws Exception{
    var mode=offline();var dataSource=new org.sqlite.SQLiteDataSource();dataSource.setUrl("jdbc:sqlite:"+mode.data().resolve("config.sqlite"));var db=new org.springframework.jdbc.core.JdbcTemplate(dataSource);db.execute("CREATE TABLE companion_mcp(user_id INTEGER,id TEXT,config_json TEXT,PRIMARY KEY(user_id,id))");var stories=mock(StoryStore.class);when(stories.db()).thenReturn(db);var registry=new McpRegistry(stories,mode,JSON);
    try{
      var config=JSON.createObjectNode().put("id","environment").put("name","stdio scope").put("transport","stdio");config.set("command",JSON.valueToTree(List.of("fixture-command")));config.set("env",JSON.valueToTree(Map.of("FIXTURE_KEY","old-fixture-only")));registry.save(1,config);
      config.remove("env");registry.save(1,config);assertTrue(registry.list(1).get(0).path("hasSecrets").asBoolean());
      config.set("command",JSON.valueToTree(List.of("different-fixture-command")));registry.save(1,config);assertFalse(registry.list(1).get(0).path("hasSecrets").asBoolean());
      config.set("env",JSON.valueToTree(Map.of("FIXTURE_KEY","explicit-fixture-only")));registry.save(1,config);assertTrue(registry.list(1).get(0).path("hasSecrets").asBoolean());
      config.put("transport","http").put("url","http://127.0.0.1:1/mcp");registry.save(1,config);assertFalse(registry.list(1).get(0).path("hasSecrets").asBoolean());
    }finally{registry.close();}
  }

  @Test void discoveredNamesRemainKnownAfterRevocationAndRestartWithoutReconnecting()throws Exception{
    var mode=offline();var dataSource=new org.sqlite.SQLiteDataSource();dataSource.setUrl("jdbc:sqlite:"+mode.data().resolve("catalog.sqlite"));var db=new org.springframework.jdbc.core.JdbcTemplate(dataSource);db.execute("CREATE TABLE companion_mcp(user_id INTEGER,id TEXT,config_json TEXT,PRIMARY KEY(user_id,id))");var stories=mock(StoryStore.class);when(stories.db()).thenReturn(db);var registry=new McpRegistry(stories,mode,JSON);var received=new CopyOnWriteArrayList<String>();
    try(var fixture=credentialFixture(received)){
      var cfg=JSON.createObjectNode().put("id","catalog").put("name","测试回声服务").put("transport","http").put("url",fixture.url()).put("enabled",true).put("allowModelCalls",true);registry.save(1,cfg);assertEquals("echo",registry.tools(1,"catalog").get(0).get("name"));
      cfg.put("allowModelCalls",false);registry.save(1,cfg);registry.close();int count=received.size();
      var restarted=new McpRegistry(stories,mode,JSON);try{
        assertEquals("echo",restarted.requestedTools(1,"请调用echo返回测试文字。").get(0).get("name"));assertTrue(restarted.requestedTools(2,"请调用echo返回测试文字。").isEmpty());assertEquals(count,received.size());
        assertTrue(restarted.modelTools(1,"请调用echo返回测试文字。").isEmpty());assertEquals(count,received.size());
        cfg.put("url","http://127.0.0.1:1/new-endpoint");cfg.set("discoveredToolNames",JSON.valueToTree(List.of("echo")));restarted.save(1,cfg);assertTrue(restarted.requestedTools(1,"请调用echo返回测试文字。").isEmpty());
      }finally{restarted.close();}
    }finally{registry.close();}
  }

  @Test void storedLegacyRecordWithoutTransportStillConnectsAsSseAndEditsKeepItsProtocol()throws Exception{
    var mode=offline();var dataSource=new org.sqlite.SQLiteDataSource();dataSource.setUrl("jdbc:sqlite:"+mode.data().resolve("legacy.sqlite"));var db=new org.springframework.jdbc.core.JdbcTemplate(dataSource);db.execute("CREATE TABLE companion_mcp(user_id INTEGER,id TEXT,config_json TEXT,PRIMARY KEY(user_id,id))");var stories=mock(StoryStore.class);when(stories.db()).thenReturn(db);var registry=new McpRegistry(stories,mode,JSON);var stream=new CompletableFuture<OutputStream>();var release=new CountDownLatch(1);var legacyGets=new AtomicInteger();
    try(var fixture=new Fixture(exchange->{try{
      if(!exchange.getRequestMethod().equals("GET")){exchange.sendResponseHeaders(405,-1);return;}
      legacyGets.incrementAndGet();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);try(var output=exchange.getResponseBody()){stream.complete(output);output.write("event: endpoint\ndata: /legacy-messages\n\n".getBytes(StandardCharsets.UTF_8));output.flush();release.await(5,TimeUnit.SECONDS);}
    }catch(Exception ignored){}finally{exchange.close();}})){
      fixture.server.createContext("/legacy-messages",exchange->{try{var message=JSON.readTree(exchange.getRequestBody());if(message.hasNonNull("id")){Object value=message.path("method").asText().equals("initialize")?initialize():tools();var output=stream.get(1,TimeUnit.SECONDS);synchronized(output){output.write(("event: message\ndata: "+result(message.get("id"),value)+"\n\n").getBytes(StandardCharsets.UTF_8));output.flush();}}exchange.sendResponseHeaders(202,-1);}catch(Exception e){try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{exchange.close();}});
      var record=JSON.createObjectNode().put("id","legacy").put("name","old record").put("url",fixture.url()).put("enabled",true);byte[] key=Base64.getUrlDecoder().decode(Files.readString(mode.data().resolve(".mcp-key")));db.update("INSERT INTO companion_mcp VALUES(?,?,?)",1,"legacy",Secrets.encrypt(record.toString(),key,"mcp:1:legacy"));
      assertEquals("sse",registry.list(1).get(0).path("transport").asText());String stored=db.queryForObject("SELECT config_json FROM companion_mcp WHERE user_id=1 AND id='legacy'",String.class);assertFalse(JSON.readTree(Secrets.decrypt(stored,key,"mcp:1:legacy")).has("transport"),"Reading must not rewrite legacy storage");
      assertEquals("echo",registry.tools(1,"legacy").get(0).get("name"));assertEquals(1,legacyGets.get());record.put("name","edited old record");registry.save(1,record);assertEquals("sse",registry.list(1).get(0).path("transport").asText());
    }finally{release.countDown();registry.close();}
  }
}
