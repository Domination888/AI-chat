package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.time.*;
import org.example.aichat.rp.RpState;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class CompanionIntegrationFixture {
  static final String TOKEN="companion-test-local-token-12345678901234567890";
  static final Path DATA;
  static {try{DATA=Files.createTempDirectory("story-companion-test-");}catch(Exception e){throw new RuntimeException(e);}}
  @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("story.mode",()->"offline");r.add("story.data-dir",()->DATA.toString());r.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("test.sqlite")+"?busy_timeout=10000&journal_mode=WAL");r.add("story.local-token",()->TOKEN);}
  @Autowired TestRestTemplate client;
@Autowired ObjectMapper json;
@Autowired StoryStore stories;
@Autowired CompanionService companion;
@Autowired AiDailyService daily;
@Autowired RuntimeMode mode;
  @Autowired InterestInference inference;
@Autowired McpRegistry mcp;
@Autowired ProactiveService proactive;
@Autowired MemoryIndex memoryIndex;
  @Autowired SkillRuntime skills;
  @org.springframework.boot.test.web.server.LocalServerPort int port;
  @MockitoBean WebAccess web;
  @MockitoBean PublicContent content;
@MockitoBean OpenAiTransport models;
@MockitoBean MemoryIndex memory;
  @BeforeEach void setup()throws Exception{
    stories.db().update("DELETE FROM companion_interest");stories.db().update("DELETE FROM companion_setting");stories.db().update("DELETE FROM companion_daily");
    when(memory.recall(anyString(),anyString())).thenReturn("");
    when(models.stream(any(),anyList(),any(),any())).thenAnswer(call->{((java.util.function.Consumer<String>)call.getArgument(3)).accept("陪你一起聊聊。");return new OpenAiTransport.Usage(10,10,false);});
    when(content.get(contains("rss.xml"))).thenReturn(rss());when(content.get(contains("/markdown/"))).thenReturn(markdown());
    when(content.get(contains("nominatim"))).thenReturn("{\"address\":{\"city\":\"杭州\"}}");when(content.get(contains("open-meteo"))).thenReturn("{\"current\":{\"temperature_2m\":20,\"weather_code\":0}}");
  }
  String rss(){return "<rss><channel><item><title>AI 日报 "+LocalDate.now(ZoneId.of("Asia/Shanghai"))+"</title><link>https://daily.juya.uk/"+LocalDate.now(ZoneId.of("Asia/Shanghai"))+"/</link></item></channel></rss>";}
  String markdown(){return "# AI 日报\n\n### [开源模型发布](https://example.org/model)\n> 官方公布了一个模型。\n\n这里是正文。\n\n---\n\n### [推理工具更新](https://example.org/tool)\n> 新工具提供本地推理。\n\n第二条正文。\n";}
  ResponseEntity<String> call(String path,HttpMethod method,Object body){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.setBearerAuth(TOKEN);return client.exchange(path,method,new HttpEntity<>(body,h),String.class);}
  JsonNode result(ResponseEntity<String> response)throws Exception{assertTrue(response.getStatusCode().is2xxSuccessful(),response.getBody());return response.getBody()==null?null:json.readTree(response.getBody());}
  String chapter(String mode){int role=stories.db().queryForObject("SELECT MIN(id) FROM role_card",Integer.class);String conv="companion_"+UUID.randomUUID();var state=new RpState();state.mode=mode;stories.create(1,conv,role,null,state);return conv;}
  JsonNode dailyReplyContext(String conv,String message,boolean search)throws Exception{
    int role=((Number)stories.owned(1,conv).get("role_id")).intValue();
    var response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",conv,"roleId",role,"message",message,"search",search));
    assertTrue(response.getBody().contains("event:done"),response.getBody());
    var history=result(call("/api/conversation/"+conv+"/history",HttpMethod.GET,null));
    String saved=history.get(history.size()-1).path("context_json").asText("{}");JsonNode context=json.readTree(saved.isBlank()?"{}":saved);
    if(context.has("sources")){
      String frame=Arrays.stream(response.getBody().split("\n\n")).filter(part->part.startsWith("event:sources\n")).findFirst().orElseThrow();
      assertEquals(context.path("sources"),json.readTree(frame.substring(frame.indexOf("data:")+5)).path("sources"));
    }else assertFalse(response.getBody().contains("event:sources"));
    return context;
  }
}
