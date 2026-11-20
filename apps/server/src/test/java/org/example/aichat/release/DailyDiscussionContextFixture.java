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
import java.time.*;
import java.util.*;
import org.example.aichat.rp.RpState;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class DailyDiscussionContextFixture {
 static final String TOKEN="daily-context-test-token-1234567890";
 static final Path DATA;
 static{try{DATA=Files.createTempDirectory("daily-context-test-");}catch(Exception e){throw new RuntimeException(e);}}
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("story.mode",()->"offline");r.add("story.data-dir",()->DATA.toString());r.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("test.sqlite")+"?busy_timeout=10000&journal_mode=WAL");r.add("story.local-token",()->TOKEN);}
 @Autowired AiDailyService dailyEvidence;
 @Autowired CompanionService companionEvidence;
@Autowired RuntimeMode runtimeEvidence;
@Autowired WebAccess webEvidence;
 @Autowired TestRestTemplate client;
@Autowired ObjectMapper json;
@Autowired StoryStore stories;
 @MockitoBean SearchRag search;
@MockitoBean PublicContent content;
@MockitoBean OpenAiTransport models;
@MockitoBean MemoryIndex memory;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean SkillRuntime skills;
 final List<List<Map<String,Object>>> prompts=new ArrayList<>();
 String date(){return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();}
 @BeforeEach void setup()throws Exception{
  prompts.clear();stories.db().update("DELETE FROM companion_daily");
  when(memory.recall(anyString(),anyString())).thenReturn("MEMORY_DISTRACTOR：用户问日报，角色却说：这周末的短篇集该挑哪本呢？");
  when(models.stream(any(),anyList(),any(),any())).thenAnswer(call->{prompts.add(new ArrayList<>(call.getArgument(1)));((java.util.function.Consumer<String>)call.getArgument(3)).accept("单元测试回复。");return new OpenAiTransport.Usage(1,1,false);});
  when(content.get(contains("rss.xml"))).thenReturn("<rss><channel><item><title>AI 日报 "+date()+"</title><link>https://daily.juya.uk/"+date()+"/</link></item></channel></rss>");
  when(content.get(contains("/markdown/"))).thenReturn("# AI 日报\n\n### [测试新闻一](https://example.org/one)\n> 第一条摘要。\n\n第一条正文。\n\n---\n\n### [测试新闻二](https://example.org/two)\n> 第二条摘要。\n\n第二条专属详情。\n");
  call("/api/v1/me/companion",HttpMethod.PUT,Map.of("dailyEnabled",true));call("/api/v1/me/ai-daily/refresh",HttpMethod.POST,Map.of());
 }
 String call(String path,HttpMethod method,Object body){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.setBearerAuth(TOKEN);var r=client.exchange(path,method,new HttpEntity<>(body,h),String.class);assertTrue(r.getStatusCode().is2xxSuccessful(),r.getBody());return r.getBody();}
 String chapter(){var state=new RpState();state.mode="companion";state.location="SCENE_LOCATION";state.commitments=List.of("周末一起挑短篇集");String c="daily_"+UUID.randomUUID();int role=stories.db().queryForObject("SELECT MIN(id) FROM role_card",Integer.class);stories.create(1,c,role,null,state);return c;}
 String send(String c,String message){int role=((Number)stories.owned(1,c).get("role_id")).intValue();String response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",c,"roleId",role,"message",message));assertTrue(response.contains("event:done"),response);return response;}
}
