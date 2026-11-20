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

abstract class HttpMcpTransportFixture {
  static final ObjectMapper JSON=new ObjectMapper();
  static RuntimeMode offline()throws Exception{return new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline").withProperty("story.data-dir",Files.createTempDirectory("mcp-http-fixture-").toString()));}
  static void bytes(HttpExchange exchange,int status,String type,String body)throws IOException{
    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);if(type!=null)exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
  }
  static Object initialize(){return Map.of("protocolVersion","2025-06-18","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","HTTP fixture","version","1"));}
  static Object tools(){return Map.of("tools",List.of(Map.of("name","echo","description","return the argument","inputSchema",Map.of("type","object","properties",Map.of("text",Map.of("type","string")),"required",List.of("text")))));}
  static String result(JsonNode id,Object value)throws Exception{return JSON.writeValueAsString(Map.of("jsonrpc","2.0","id",id,"result",value));}
  static final class Fixture implements AutoCloseable{
    final HttpServer server;final ExecutorService executor=Executors.newFixedThreadPool(6);
    Fixture(HttpHandler handler)throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/mcp",handler);server.start();}
    String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/mcp";}
    public void close(){server.stop(0);executor.shutdownNow();}
  }
  static Fixture credentialFixture(List<String> received)throws Exception{return new Fixture(exchange->{try{
    received.add(Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"),""));var message=JSON.readTree(exchange.getRequestBody());
    if(!message.hasNonNull("id")){exchange.sendResponseHeaders(202,-1);return;}
    bytes(exchange,200,"application/json",result(message.get("id"),message.path("method").asText().equals("initialize")?initialize():tools()));
  }catch(Exception e){try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{exchange.close();}});}
}
