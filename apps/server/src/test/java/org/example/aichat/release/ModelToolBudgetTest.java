package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class ModelToolBudgetTest {
  static final ObjectMapper JSON=new ObjectMapper();
  List<Map<String,Object>> tools(){return List.of(Map.of("serverId","fixture","name","calculate","description","fixture calculation","parameters",Map.of("type","object","properties",Map.of())));}
  void run(int firstCalls,int expectedCalls)throws Exception{
    var requests=new ArrayList<JsonNode>();var executions=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/chat/completions",exchange->{try{
      requests.add(JSON.readTree(exchange.getRequestBody()));int count=requests.size()==1?firstCalls:0;var calls=new ArrayList<Object>();for(int i=0;i<count;i++)calls.add(Map.of("id","call-"+i,"type","function","function",Map.of("name","mcp_0","arguments","{}")));
      byte[] response=JSON.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("role","assistant","content","","tool_calls",calls)))));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
    }finally{exchange.close();}});server.start();
    try{
      var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline").withProperty("story.data-dir",Files.createTempDirectory("model-tool-budget-").toString()));var transport=new OpenAiTransport(JSON,mode);var connection=new ModelConnections.Connection("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture","",false);
      var original=List.<Map<String,Object>>of(Map.of("role","user","content","完成当前请求"));
      if(firstCalls>4){var failure=assertThrows(OpenAiTransport.ToolCallLimitException.class,()->transport.tools(connection,original,tools(),(tool,args)->{executions.incrementAndGet();return "executed";},usage->{}));assertTrue(failure.getMessage().contains("尚未执行这批调用"));assertTrue(failure.getMessage().contains("拆成更小"));}
      else{var result=transport.tools(connection,original,tools(),(tool,args)->{executions.incrementAndGet();return "真实测试结果";},usage->{});assertTrue(result.get(result.size()-1).get("content").toString().contains("实际成功执行了"+expectedCalls+"次"));}
      assertEquals(expectedCalls,executions.get());assertTrue(requests.get(0).path("messages").get(0).path("content").asText().contains("每一轮最多调用4次"));for(var request:requests)assertEquals("auto",request.path("tool_choice").asText());
    }finally{server.stop(0);}
  }
  @Test void excessCallsAreRejectedBeforeAnyExecution()throws Exception{run(5,0);}
  @Test void permittedCallsExecuteWithinTheSameHardLimit()throws Exception{run(4,4);}
  @Test void ordinaryPlannerCanChooseNotToCallATool()throws Exception{run(0,0);}
}
