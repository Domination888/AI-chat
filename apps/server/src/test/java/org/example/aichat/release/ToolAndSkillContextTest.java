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

class ToolAndSkillContextTest extends ToolAndSkillContextFixture {

  @Test void scheduledOnlyDoesNotLeakAcrossAnyOrdinaryMatchingEntry()throws Exception{
    var reminder=json.valueToTree(Map.of("name","crane-reminder","enabled",true,"instructions","定时纸鹤到了","schedule",Map.of("enabled",false),"proactive",Map.of("enabled",true),"mcpTools",List.of("remind")));
    skills.save(1,reminder);var tools=List.<Map<String,Object>>of(Map.of("name","remind"),Map.of("name","independent"));
    assertTrue(skills.matchingTasks(1,"天气").isEmpty());assertFalse(skills.context(1,"天气").contains("定时纸鹤到了"));assertFalse(skills.context(1,"天气",true).contains("定时纸鹤到了"));assertEquals(1,skills.filterTools(1,"天气",tools).size());assertEquals(2,skills.filterTools(2,"天气",tools).size());
    ((com.fasterxml.jackson.databind.node.ObjectNode)reminder).put("triggerMode","keywords_and_schedule").putArray("keywords").add("纸鹤");skills.save(1,reminder);
    assertEquals(1,skills.matchingTasks(1,"纸鹤").size());assertTrue(skills.matchingTasks(1,"纸鹤",true).isEmpty());
    ((com.fasterxml.jackson.databind.node.ObjectNode)reminder).put("triggerMode","keywords").putArray("keywords");assertThrows(IllegalArgumentException.class,()->skills.save(1,reminder));
    ((com.fasterxml.jackson.databind.node.ObjectNode)reminder).put("triggerMode","invalid");assertThrows(IllegalArgumentException.class,()->skills.save(1,reminder));
  }

  @Test void lengthPersistsPartialAndAccountsOnceWithoutReplayingTools()throws Exception{
    prime();
    when(models.tools(any(),anyList(),anyList(),any(),any())).thenAnswer(call->{List<Map<String,Object>> tools=call.getArgument(2);BiFunction<Map<String,Object>,Map<String,Object>,String> execute=call.getArgument(3);execute.apply(tools.get(0),Map.of("number",97));return call.getArgument(1);});
    clearInvocations(quota,inference,models,mcp);
    doAnswer(call->{((OpenAiTransport.Control)call.getArgument(2)).finishReason="length";((java.util.function.Consumer<String>)call.getArgument(3)).accept("这些资料未");return new OpenAiTransport.Usage(31,1200,false);}).when(models).stream(any(),anyList(),any(),any());
    String c=chapter(),events=ask(c,"请调用工具判断97是否是质数。");
    assertTrue(events.contains("达到长度上限"));assertTrue(events.contains("interrupted"));assertTrue(events.contains("incomplete"));
    var row=stories.db().queryForMap("SELECT assistant_text AS content,status FROM rp_turn WHERE conversation_id=? ORDER BY id DESC LIMIT 1",c);
    assertEquals("这些资料未",row.get("content"));assertEquals("interrupted",row.get("status"));
    var context=stories.db().queryForObject("SELECT context_json FROM message_context WHERE conversation_id=? ORDER BY history_id DESC LIMIT 1",String.class,c);assertTrue(json.readTree(context).at("/completion/incomplete").asBoolean());assertEquals("length",json.readTree(context).at("/completion/reason").asText());
    verify(quota,times(1)).record(eq(1),anyBoolean(),eq(new OpenAiTransport.Usage(31,1200,false)));verify(inference,never()).afterTurn(anyInt(),anyString());verify(models,times(1)).stream(any(),anyList(),any(),any());verify(mcp,times(1)).execute(anyInt(),anyString(),anyString(),any());
  }

  @Test void realBuiltinExecutionSurvivesHistoryReloadWithItsResult()throws Exception{
    prime();when(models.tools(any(),anyList(),anyList(),any(),any())).thenAnswer(call->{List<Map<String,Object>> tools=call.getArgument(2);BiFunction<Map<String,Object>,Map<String,Object>,String> execute=call.getArgument(3);assertEquals("97 是质数",execute.apply(tools.get(0),Map.of("number",97)));return call.getArgument(1);});
    String c=chapter(),events=ask(c,"请调用工具判断97是否是质数。");assertTrue(events.contains("succeeded"));
    var context=metadata(c);assertEquals("completed",context.path("toolPlanning").path("phase").asText());var row=context.path("toolCalls").get(0);assertEquals("isPrime",row.path("name").asText());assertEquals("内置质数工具",row.path("serverName").asText());assertEquals(97,row.path("arguments").path("number").asInt());assertEquals("97 是质数",row.path("result").asText());assertEquals("succeeded",row.path("phase").asText());
  }

  @Test void failedExecutionIsPersistedWithoutInventingAResult()throws Exception{
    prime();when(models.tools(any(),anyList(),anyList(),any(),any())).thenAnswer(call->{List<Map<String,Object>> tools=call.getArgument(2);BiFunction<Map<String,Object>,Map<String,Object>,String> execute=call.getArgument(3);execute.apply(tools.get(0),Map.of("number",0));return call.getArgument(1);});
    String c=chapter(),events=ask(c,"判断0是不是质数。");assertTrue(events.contains("event:error"));var row=metadata(c).path("toolCalls").get(0);assertEquals("failed",row.path("phase").asText());assertFalse(row.has("result"));assertTrue(row.path("error").asText().contains("参数"));assertEquals("failed",stories.db().queryForObject("SELECT status FROM rp_turn WHERE conversation_id=? ORDER BY id DESC LIMIT 1",String.class,c));
  }

  @Test void unusedToolsAndRejectedPlanningNeverCreateExecutionCards()throws Exception{
    prime();String unused=chapter();ask(unused,"判断97是不是质数。");assertEquals("not_called",metadata(unused).path("toolPlanning").path("phase").asText());assertFalse(metadata(unused).has("toolCalls"));
    when(models.tools(any(),anyList(),anyList(),any(),any())).thenThrow(new OpenAiTransport.ToolCallLimitException());String limited=chapter();assertTrue(ask(limited,"列出某个范围的全部质数。").contains("拆成更小"));var context=metadata(limited);assertEquals("failed",context.path("toolPlanning").path("phase").asText());assertTrue(context.path("toolPlanning").path("message").asText().contains("4次"));assertFalse(context.has("toolCalls"));
  }

  @Test void revokedExplicitRequestCannotClaimThePreviousTurnsExecution()throws Exception{
    prime();when(models.tools(any(),anyList(),anyList(),any(),any())).thenAnswer(call->{List<Map<String,Object>> tools=call.getArgument(2);BiFunction<Map<String,Object>,Map<String,Object>,String> execute=call.getArgument(3);execute.apply(tools.get(0),Map.of("number",97));return call.getArgument(1);});
    String c=chapter();ask(c,"请调用isPrime判断97是否为质数。");assertEquals("succeeded",metadata(c).path("toolCalls").get(0).path("phase").asText());
    mcp.save(1,json.valueToTree(Map.of("id","prime","name","内置质数工具","builtin",true,"enabled",true,"allowModelCalls",false)));clearInvocations(models,mcp);
    String events=ask(c,"请调用isPrime判断101是否为质数，并说明有没有实际调用。");
    assertTrue(events.contains("本轮未执行 MCP 工具"));assertTrue(events.contains("允许模型调用"));assertFalse(events.contains("我确实调用"));
    verify(models,never()).tools(any(),anyList(),anyList(),any(),any());verify(models,never()).stream(any(),anyList(),any(),any());verify(mcp,never()).execute(anyInt(),anyString(),anyString(),any());
    var context=metadata(c);assertEquals("not_called",context.path("toolPlanning").path("phase").asText());assertFalse(context.has("toolCalls"));
    assertEquals("complete",stories.db().queryForObject("SELECT status FROM rp_turn WHERE conversation_id=? ORDER BY id DESC LIMIT 1",String.class,c));
    var history=json.valueToTree(stories.history(1,c,80,null,""));assertTrue(history.get(history.size()-1).path("content").asText().contains("本轮未执行"));
    if(System.getProperty("mcpfacts.capture")!=null)json.writerWithDefaultPrettyPrinter().writeValue(Path.of(System.getProperty("mcpfacts.capture")).toFile(),Map.of("kind","Actual ChatApi HTTP/SSE lifecycle with revoked authorization; model/tools were not invoked for the rejected request","events",events,"history",history));
  }

  @Test void unmetSkillConditionsAlsoReturnARealNonExecutionResult()throws Exception{
    prime();skills.save(1,json.valueToTree(Map.of("name","restricted-prime","enabled",true,"keywords",List.of("数学课"),"mcpTools",List.of("isPrime"))));
    String c=chapter();assertTrue(ask(c,"请调用isPrime判断101是否为质数。").contains("技能关键词"));
    verify(models,never()).tools(any(),anyList(),anyList(),any(),any());verify(models,never()).stream(any(),anyList(),any(),any());verify(mcp,never()).execute(anyInt(),anyString(),anyString(),any());assertFalse(metadata(c).has("toolCalls"));
  }

  @Test void codeAndStoryMentionsDoNotBlockOrdinaryModelReplies()throws Exception{
    mcp.save(1,json.valueToTree(Map.of("id","prime","name","内置质数工具","builtin",true,"enabled",true,"allowModelCalls",false)));
    for(String message:List.of("请解释如何调用isPrime。","请使用isPrime写一段示例代码。","故事里他说：请调用isPrime。","不要调用isPrime，我们继续聊天。"))assertTrue(ask(chapter(),message).contains("这是测试回复"));
    verify(models,times(4)).stream(any(),anyList(),any(),any());verify(models,never()).tools(any(),anyList(),anyList(),any(),any());verify(mcp,never()).execute(anyInt(),anyString(),anyString(),any());
  }

  @Test void authorizedExplicitRequestRequiresARealCallbackForSuccessfulExecutionMetadata()throws Exception{
    prime();when(models.tools(any(),anyList(),anyList(),any(),any())).thenAnswer(call->{List<Map<String,Object>> tools=call.getArgument(2);BiFunction<Map<String,Object>,Map<String,Object>,String> execute=call.getArgument(3);assertEquals("101 是质数",execute.apply(tools.get(0),Map.of("number",101)));return call.getArgument(1);});
    String c=chapter();assertTrue(ask(c,"请调用isPrime判断101是否为质数，并说明有没有实际调用。").contains("succeeded"));
    verify(mcp).execute(eq(1),eq("prime"),eq("isPrime"),any());verify(models).stream(any(),anyList(),any(),any());
    var context=metadata(c);assertEquals("succeeded",context.path("toolCalls").get(0).path("phase").asText());assertEquals(101,context.path("toolCalls").get(0).path("arguments").path("number").asInt());
    var facts=captured.get(0).get(captured.get(0).size()-1);assertEquals("system",facts.get("role"));assertTrue(facts.get("content").toString().contains("isPrime：执行成功"));
  }

  @Test void authorizedButUnusedToolDoesNotLetFinalGenerationInventAnExecution()throws Exception{
    prime();String c=chapter();assertTrue(ask(c,"请调用isPrime判断101是否为质数。").contains("模型没有发起实际调用"));
    verify(models).tools(any(),anyList(),anyList(),any(),any());verify(models,never()).stream(any(),anyList(),any(),any());verify(mcp,never()).execute(anyInt(),anyString(),anyString(),any());assertEquals("not_called",metadata(c).path("toolPlanning").path("phase").asText());assertFalse(metadata(c).has("toolCalls"));
  }

  @Test void persistedArgumentsHideCredentialFieldsAndBoundLongInput(){
    var row=ToolExecutionTrace.started(Map.of("name","echo","serverId","private"),Map.of("Authorization","Bearer credential-must-not-persist","nested",Map.of("apiKey","another-secret"),"text","x".repeat(10000)));
    String encoded=json.valueToTree(row).toString();assertFalse(encoded.contains("credential-must-not-persist"));assertFalse(encoded.contains("another-secret"));assertTrue(encoded.contains("已隐藏"));assertTrue(encoded.length()<1000);assertEquals("running",row.get("phase"));
  }
}
