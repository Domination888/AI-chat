package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import org.example.aichat.rp.RpState;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class ToolAndSkillContextFixture {
  static final String TOKEN="tool-skill-test-local-only-1234567890";
  static final Path DATA;
static{try{DATA=Files.createTempDirectory("tool-skill-context-");}catch(Exception e){throw new RuntimeException(e);}}
  @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("story.mode",()->"offline");r.add("story.data-dir",()->DATA.toString());r.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("test.sqlite")+"?busy_timeout=10000&journal_mode=WAL");r.add("story.local-token",()->TOKEN);}
  @Autowired TestRestTemplate client;
@Autowired ChatApi chat;
@Autowired StoryStore stories;
@Autowired SkillRuntime skills;
@Autowired Streams streams;
@Autowired RuntimeMode mode;
@MockitoSpyBean McpRegistry mcp;
@MockitoSpyBean Quota quota;
@Autowired ObjectMapper json;
  @MockitoBean PublicContent content;
@MockitoBean OpenAiTransport models;
@MockitoBean MemoryIndex memory;
@MockitoBean InterestInference inference;
@MockitoBean ProactiveService proactive;
@MockitoBean SearchRag search;
  List<List<Map<String,Object>>> captured=new CopyOnWriteArrayList<>();
  Map<String,Object> lastResponseFormat;
  String rawStructured,lastEvents,lastStoredReply,lastStoredStatus;
OpenAiTransport.Usage structuredUsage;
  @BeforeEach void setup()throws Exception{
    stories.db().update("DELETE FROM companion_skill");stories.db().update("DELETE FROM companion_mcp");
    when(memory.recall(anyString(),anyString())).thenReturn("");
    when(models.tools(any(),anyList(),anyList(),any(),any())).thenAnswer(call->call.getArgument(1));
    when(models.stream(any(),anyList(),any(),any())).thenAnswer(call->{captured.add(new ArrayList<>(call.getArgument(1)));((java.util.function.Consumer<String>)call.getArgument(3)).accept("这是测试回复。");return new OpenAiTransport.Usage(3,4,false);});
    when(models.stream(any(),anyList(),any(),any(),anyMap())).thenAnswer(this::structuredReply);
  }
  Object structuredReply(org.mockito.invocation.InvocationOnMock call)throws Exception{
    List<Map<String,Object>> messages=call.getArgument(1);captured.add(new ArrayList<>(messages));lastResponseFormat=call.getArgument(4);java.util.function.Consumer<String> output=call.getArgument(3);
    String services=System.getProperty("skillreal.services");
    if(services!=null){
      var config=json.readTree(Path.of(services).toFile());var actual=new ModelConnections.Connection(config.path("llm").path("base").asText().replaceAll("/+$","")+"/v1",config.path("llm").path("model").asText(),config.path("token").asText(),false);var raw=new StringBuilder();
      structuredUsage=new OpenAiTransport(json,mode).stream(actual,messages,call.getArgument(2),text->{raw.append(text);output.accept(text);},lastResponseFormat);rawStructured=raw.toString();return structuredUsage;
    }
    var schema=json.valueToTree(lastResponseFormat).at("/json_schema/schema/properties");int count=schema.path("items").path("minItems").asInt();var items=new ArrayList<String>();for(int i=1;i<=count;i++)items.add("执行检查步骤"+i+"。");String question=schema.path("question").has("minLength")?"你想先从哪步开始？":"",closing=schema.path("closing").has("minLength")?"先准备再行动。":"";rawStructured=json.writeValueAsString(Map.of("items",items,"closing",closing,"question",question));output.accept(rawStructured);structuredUsage=new OpenAiTransport.Usage(5,6,false);return structuredUsage;
  }
  String chapter(){String c="tool_skill_"+UUID.randomUUID();int role=stories.db().queryForObject("SELECT MIN(id) FROM role_card",Integer.class);var state=new RpState();state.mode="companion";stories.create(1,c,role,null,state);return c;}
  String ask(String c,String message){var headers=new HttpHeaders();headers.setBearerAuth(TOKEN);headers.setContentType(MediaType.APPLICATION_JSON);int role=((Number)stories.owned(1,c).get("role_id")).intValue();var response=client.exchange("/api/chat",HttpMethod.POST,new HttpEntity<>(Map.of("conversationId",c,"roleId",role,"message",message),headers),String.class);assertEquals(200,response.getStatusCode().value());lastEvents=response.getBody();var rows=stories.db().queryForList("SELECT assistant_text,status FROM rp_turn WHERE conversation_id=? ORDER BY id DESC LIMIT 1",c);if(!rows.isEmpty()){lastStoredReply=rows.get(0).get("assistant_text").toString();lastStoredStatus=rows.get(0).get("status").toString();}return response.getBody();}
  JsonNode metadata(String c)throws Exception{return json.readTree(stories.db().queryForObject("SELECT context_json FROM message_context WHERE conversation_id=? ORDER BY history_id DESC LIMIT 1",String.class,c));}
  void prime(){mcp.save(1,json.valueToTree(Map.of("id","prime","name","内置质数工具","builtin",true,"enabled",true,"allowModelCalls",true)));}
  void capture(String name,List<Map<String,Object>> messages)throws Exception{String prefix=System.getProperty("toolskill.capture");if(prefix!=null){var evidence=new LinkedHashMap<String,Object>();evidence.put("kind",System.getProperty("skillreal.services")==null?"Actual ChatApi final prompt assembly; generation was mocked, not a model-compliance claim":"Actual ChatApi HTTP/SSE flow with real Qwen JSON schema generation in an isolated database; no UI or account writes");evidence.put("messages",messages);if(lastResponseFormat!=null)evidence.put("responseFormat",lastResponseFormat);if(rawStructured!=null)evidence.put("rawStructured",rawStructured);if(structuredUsage!=null)evidence.put("modelUsage",structuredUsage);if(lastStoredReply!=null){evidence.put("storedReply",lastStoredReply);evidence.put("storedStatus",lastStoredStatus);evidence.put("events",lastEvents);}json.writerWithDefaultPrettyPrinter().writeValue(Path.of(prefix+"-"+name+".json").toFile(),evidence);}}
  Object latestUser(List<Map<String,Object>> messages){for(int i=messages.size()-1;i>=0;i--)if("user".equals(messages.get(i).get("role")))return messages.get(i).get("content");throw new AssertionError("No user message");}
  void countedSkill(String keyword,int count){skills.save(1,json.valueToTree(Map.of("name","counted-task","enabled",true,"keywords",List.of(keyword),"instructions","请列出"+count+"项可立即执行的整理步骤，最后问一个简短问题。")));}
}
