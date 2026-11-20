package org.example.aichat.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalModelOptionsTest {
  final OpenAiTransport transport=new OpenAiTransport(new ObjectMapper(),mock(RuntimeMode.class));
  Map<String,Object> options(String url,String model,boolean custom){var payload=new HashMap<String,Object>();transport.visibleAnswerOptions(new ModelConnections.Connection(url,model,"",custom),payload);return payload;}
  @Test void KnownDeepSeekBoundedAnswersReserveTheirBudgetForVisibleContent(){
    assertEquals(Map.of("thinking",Map.of("type","disabled")),options("https://api.deepseek.com/v1","deepseek-flash",false));
    assertEquals(Map.of("thinking",Map.of("type","disabled")),options("https://api.deepseek.com","deepseek-v4-pro",true));
  }
  @Test void ProprietaryOptionsDoNotLeakToAnUnknownProviderOrANameLookalike(){
    assertTrue(options("https://api.deepseek.com.example.org/v1","deepseek-flash",true).isEmpty());
    assertTrue(options("https://gateway.example.org/v1","deepseek-flash",true).isEmpty());
    assertTrue(options("https://api.deepseek.com/v1","other-model",true).isEmpty());
    assertEquals(Map.of("chat_template_kwargs",Map.of("enable_thinking",false)),options("http://127.0.0.1:12345/v1","Qwen3-4B",false));
    assertTrue(options("http://127.0.0.1:12345/v1","other-model",true).isEmpty());
  }
  @Test void StreamingChatActuallySendsTheVisibleAnswerOptions()throws Exception {
    var json=new ObjectMapper();var sent=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/chat/completions",exchange->{
      sent.set(json.readTree(exchange.getRequestBody().readAllBytes()));
      byte[] bytes=("data: {\"choices\":[{\"delta\":{\"content\":\"可见答复\"}}]}\n\n"
          +"data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4}}\n\n"
          +"data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
      try(var out=exchange.getResponseBody()){out.write(bytes);}
    });server.start();
    try {
      // Route the request to a local protocol fixture while retaining the known
      // provider's option policy. This catches complete-only wiring regressions.
      var streamTransport=new OpenAiTransport(json,mock(RuntimeMode.class)) {
        @Override void visibleAnswerOptions(ModelConnections.Connection c,Map<String,Object> payload){
          super.visibleAnswerOptions(new ModelConnections.Connection("https://api.deepseek.com/v1",c.model(),"",false),payload);
        }
      };
      var text=new StringBuilder();var control=new OpenAiTransport.Control();
      streamTransport.stream(new ModelConnections.Connection("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","deepseek-flash","",false),List.of(Map.of("role","user","content","回答当前问题")),control,text::append);
      assertEquals("disabled",sent.get().path("thinking").path("type").asText());
      assertEquals(1200,sent.get().path("max_tokens").asInt());
      assertTrue(sent.get().path("stream").asBoolean());assertEquals("可见答复",text.toString());
      assertEquals("stop",control.finishReason);assertEquals(4,control.observedUsage.output());
    }finally{server.stop(0);}
  }
}
