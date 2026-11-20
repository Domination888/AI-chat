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

class CompanionIntegrationTest extends CompanionIntegrationFixture {

  @Test void editedSkillPermissionsAndKeywordsControlModelTools(){
    var tools=List.<Map<String,Object>>of(Map.of("name","isPrime"),Map.of("name","independentTool"));
    skills.save(700,json.valueToTree(Map.of("name","permission-test","enabled",false,"mcpTools",List.of("isPrime"))));assertEquals(List.of(Map.of("name","independentTool")),skills.filterTools(700,"质数",tools));
    skills.save(700,json.valueToTree(Map.of("name","permission-test","enabled",true,"keywords",List.of("质数"),"mcpTools",List.of("isPrime"))));assertEquals(tools,skills.filterTools(700,"判断质数",tools));assertEquals(1,skills.filterTools(700,"聊聊今天",tools).size());assertEquals(tools,skills.filterTools(701,"质数",tools));
  }

  @Test void settingsLocationAndInterestsStayWithAuthenticatedOwner()throws Exception{
    var s=result(call("/api/v1/me/companion",HttpMethod.GET,null));assertFalse(s.path("dailyEnabled").asBoolean());assertFalse(s.path("worldNetworkEnabled").asBoolean());
    result(call("/api/v1/me/companion",HttpMethod.PUT,Map.of("timezone","Asia/Tokyo","userId",2)));
    assertEquals("Asia/Tokyo",companion.settings(1).path("timezone").asText());assertEquals("Asia/Shanghai",companion.settings(2).path("timezone").asText());
    assertEquals(400,call("/api/v1/me/companion",HttpMethod.PUT,Map.of("timezone","Invalid/Zone")).getStatusCode().value());
    assertEquals(400,call("/api/world-context/location",HttpMethod.POST,Map.of("latitude",999,"longitude",1)).getStatusCode().value());
    var world=result(call("/api/world-context/location",HttpMethod.POST,Map.of("latitude",30.123456,"longitude",120.123456,"timezone","Asia/Shanghai")));assertEquals(30.12,world.path("latitude").asDouble());verifyNoInteractions(content);
    result(call("/api/world-context/location",HttpMethod.PUT,Map.of("city","杭州","timezone","Asia/Shanghai")));assertEquals("杭州",companion.snapshot(1).path("location").asText());assertFalse(companion.settings(1).has("latitude"));
    result(call("/api/proactive-research/interests",HttpMethod.POST,Map.of("topic","摄影","userId",2)));assertEquals(1,companion.interests(1).size());assertEquals(0,companion.interests(2).size());
    companion.add(2,json.valueToTree(Map.of("topic","另一个人的喜好")),"manual");String other=(String)companion.interests(2).get(0).get("id");assertEquals(404,call("/api/proactive-research/interests/"+other,HttpMethod.DELETE,null).getStatusCode().value());
    result(call("/api/world-context/location",HttpMethod.DELETE,null));assertEquals("",companion.snapshot(1).path("location").asText());
  }

  @Test void automaticCollectionUsesCompanionUserStatementsAndHonorsMutedTopics()throws Exception{
    String story=chapter("story"),conv=chapter("companion");companion.collect(1,story,"我喜欢虚构魔法");assertEquals(0,companion.interests(1).size());
    companion.collect(1,conv,"我喜欢摄影。我讨厌香菜。我喜欢我的银行卡123456789");assertEquals(2,companion.interests(1).size());
    assertTrue(companion.context(1).contains("不喜欢：香菜"));String id=(String)companion.interests(1).stream().filter(i->i.get("topic").equals("摄影")).findFirst().orElseThrow().get("id");
    result(call("/api/proactive-research/interests/"+id,HttpMethod.PUT,Map.of("enabled",false)));companion.collect(1,conv,"我喜欢摄影");assertFalse(companion.context(1).contains("摄影"));
    companion.collect(1,conv,"假如我喜欢跑步呢");companion.collect(1,conv,"她说：“我喜欢电影”");assertEquals(2,companion.interests(1).size());
    result(call("/api/v1/me/companion",HttpMethod.PUT,Map.of("autoCollect",false)));companion.collect(1,conv,"我喜欢绘画");assertEquals(2,companion.interests(1).size());
  }

  @Test void chatActuallyReceivesCompanionContextWithoutChangingStoryMode()throws Exception{
    companion.manualLocation(1,json.valueToTree(Map.of("city","杭州","timezone","Asia/Shanghai")));
    int role=stories.db().queryForObject("SELECT MIN(id) FROM role_card",Integer.class);String conv=chapter("companion");
    var response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",conv,"roleId",role,"message","我喜欢摄影"));assertTrue(response.getBody().contains("event:done"),response.getBody());
    var capture=org.mockito.ArgumentCaptor.forClass(List.class);verify(models).stream(any(),capture.capture(),any(),any());String prompt=capture.getValue().toString();assertTrue(prompt.contains("杭州"));assertTrue(prompt.contains("摄影"));assertTrue(prompt.contains("Asia/Shanghai"));
    clearInvocations(models);response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",chapter("story"),"roleId",role,"message","继续故事"));assertTrue(response.getBody().contains("event:done"));verify(models).stream(any(),capture.capture(),any(),any());assertFalse(capture.getValue().toString().contains("喜欢：摄影"));
  }

  @Test void removedInterestMigrationEndpointDoesNotAffectNormalEditingOrExport()throws Exception{
    String conv=chapter("story");result(call("/api/proactive-research/interests",HttpMethod.POST,Map.of("topic","摄影","sentiment","dislike")));
    var payload=Map.of("proactive_interest",List.of(Map.of("user_id",99,"topic","开源模型","enabled",false)));
    assertEquals(404,call("/api/v1/me/interests/import",HttpMethod.POST,payload).getStatusCode().value());assertEquals(1,companion.interests(1).size());assertEquals(0,companion.interests(99).size());assertEquals("dislike",companion.interests(1).get(0).get("sentiment"));assertNotNull(stories.owned(1,conv));
    var exported=result(call("/api/v1/me/data",HttpMethod.GET,null));assertEquals(1,exported.path("companion_interests").size());assertTrue(exported.has("companion_settings"));
  }

  @Test void allNewEndpointsRequireAuthentication(){for(String endpoint:List.of("/api/v1/me/companion","/api/world-context","/api/proactive-research/interests","/api/v1/me/ai-daily"))assertEquals(401,client.getForEntity(endpoint,String.class).getStatusCode().value());}

  @Test void calendarSearchSkillsAndSourcePersistence()throws Exception{
    var world=result(call("/api/world-context",HttpMethod.GET,null));assertFalse(world.path("lunarDate").asText().isBlank());assertFalse(world.path("solarTerm").asText().isBlank());
    companion.save(1,json.valueToTree(Map.of("searchEnabled",true)));
    when(web.get(contains("/search?"),eq(true))).thenReturn("{\"results\":[{\"title\":\"摄影模型的官方说明\",\"url\":\"https://1.1.1.1/docs?topic=photo\",\"content\":\"摄影模型最新功能\"}]}");
    when(web.get(contains("/docs?"),eq(false))).thenReturn("<html><main>摄影模型的最新功能是本地运行和相机照片处理。</main></html>");
    var searched=result(call("/api/search",HttpMethod.POST,Map.of("query","摄影模型有什么功能")));assertEquals(1,searched.path("sources").size());assertTrue(searched.path("sources").get(0).path("pageRead").asBoolean());
    result(call("/api/skills",HttpMethod.POST,Map.of("name","photo-helper","enabled",true,"keywords",List.of("摄影"),"instructions","回答摄影问题时先询问相机类型。")));
    String conv=chapter("companion");int role=((Number)stories.owned(1,conv).get("role_id")).intValue();var reply=call("/api/chat",HttpMethod.POST,Map.of("conversationId",conv,"roleId",role,"message","摄影模型有什么功能","search",true));assertTrue(reply.getBody().contains("event:sources"));assertTrue(reply.getBody().contains("event:done"));
    var history=result(call("/api/conversation/"+conv+"/history",HttpMethod.GET,null));assertTrue(history.get(history.size()-1).path("context_json").asText().contains("1.1.1.1"));
    var capture=org.mockito.ArgumentCaptor.forClass(List.class);verify(models).stream(any(),capture.capture(),any(),any());assertTrue(capture.getValue().toString().contains("相机类型"));
  }

  @Test void searchKeepsTechnicalNamesInAnEnglishLocaleEvenForChineseResearchFollowups()throws Exception{
    companion.save(1,json.valueToTree(Map.of("searchEnabled",true)));when(web.get(contains("/search?"),eq(true))).thenReturn("{\"results\":[]}");
    result(call("/api/search",HttpMethod.POST,Map.of("query","Kokoro 最新进展")));verify(web).get(contains("language=en&q=Kokoro"),eq(true));
    assertEquals("en",SearchTerms.searchLanguage("Python official documentation"));assertEquals("en",SearchTerms.searchLanguage("AI news"));assertEquals("zh-CN",SearchTerms.searchLanguage("摄影 最新进展"));assertEquals("zh-CN",SearchTerms.searchLanguage("AI 日报"));
  }

  @Test void unavailableSearchServiceExplainsHowToRecover()throws Exception{
    companion.save(1,json.valueToTree(Map.of("searchEnabled",true)));
    when(web.get(contains("/search?"),eq(true))).thenThrow(new java.net.ConnectException("Connection refused"));
    var response=call("/api/search",HttpMethod.POST,Map.of("query","Python 官方文档"));assertEquals(503,response.getStatusCode().value());
    assertTrue(response.getBody().contains("启动本机 SearxNG"));assertTrue(response.getBody().contains("关闭“联网搜索”继续聊天"));
    when(web.get(contains("/search?"),eq(true))).thenReturn("<html>Search login required</html>");
    assertEquals(503,call("/api/search",HttpMethod.POST,Map.of("query","Python 官方文档")).getStatusCode().value());
  }

  @Test void blockedSearchEnginesStayDistinctFromNoMatchingResultsAndDoNotQueueResearch()throws Exception{
    companion.save(1,json.valueToTree(Map.of("searchEnabled",true,"researchEnabled",true)));companion.add(1,json.valueToTree(Map.of("topic","摄影")),"manual");
    when(web.get(contains("/search?"),eq(true))).thenReturn("{\"results\":[],\"unresponsive_engines\":[[\"brave\",\"Suspended: too many requests\"],[\"duckduckgo\",\"CAPTCHA\"]]}");
    var response=result(call("/api/search",HttpMethod.POST,Map.of("query","摄影 最新进展")));assertEquals("unavailable",response.path("status").asText());assertTrue(response.path("reason").asText().contains("请求限流"));assertTrue(response.path("reason").asText().contains("需要验证码"));
    String conv=chapter("companion");var researched=proactive.research(1,conv);assertEquals(false,researched.get("queued"));assertTrue(researched.get("reason").toString().contains("需要验证码"));assertTrue(proactive.candidates(1,conv).isEmpty());
    int role=((Number)stories.owned(1,conv).get("role_id")).intValue();var reply=call("/api/chat",HttpMethod.POST,Map.of("conversationId",conv,"roleId",role,"message","摄影 最新进展","search",true));
    assertTrue(reply.getBody().contains("event:warning"));assertTrue(reply.getBody().contains("需要验证码"));assertTrue(reply.getBody().contains("event:done"));
    var capture=org.mockito.ArgumentCaptor.forClass(List.class);verify(models).stream(any(),capture.capture(),any(),any());assertTrue(capture.getValue().get(0).toString().contains("不得编造事实或链接"));
  }

  @Test void genuinelyEmptySearchSuggestsMoreSpecificKeywords()throws Exception{
    companion.save(1,json.valueToTree(Map.of("searchEnabled",true)));when(web.get(contains("/search?"),eq(true))).thenReturn("{\"results\":[],\"unresponsive_engines\":[]}");
    var response=result(call("/api/search",HttpMethod.POST,Map.of("query","没有匹配的公开关键词")));assertEquals("empty",response.path("status").asText());assertTrue(response.path("reason").asText().contains("更具体的关键词"));assertFalse(response.path("reason").asText().contains("管理员"));
  }

  @Test void stdioMcpConnectsListsExecutesAndProtectsSecrets()throws Exception{
    String java=Path.of(System.getProperty("java.home"),"bin","java").toString(),id="fixture_"+UUID.randomUUID();
    result(call("/api/mcp/servers",HttpMethod.POST,Map.of("id",id,"name","fixture","transport","stdio","enabled",true,"command",List.of(java,"-cp",System.getProperty("java.class.path"),McpStdioFixture.class.getName()),"headers",Map.of("Authorization","Bearer isolated-fixture-secret"))));
    var tools=result(call("/api/mcp/servers/"+id+"/tools",HttpMethod.GET,null));assertEquals("add",tools.get(0).path("name").asText());
    var output=result(call("/api/mcp/servers/"+id+"/tools/add",HttpMethod.POST,Map.of("a",2,"b",3)));assertEquals("5",output.path("result").asText());
    assertFalse(call("/api/mcp/servers",HttpMethod.GET,null).getBody().contains("isolated-fixture-secret"));assertFalse(stories.db().queryForObject("SELECT config_json FROM companion_mcp WHERE id=?",String.class,id).contains("isolated-fixture-secret"));assertTrue(mcp.list(2).isEmpty());
    result(call("/api/mcp/servers/"+id,HttpMethod.DELETE,null));
  }

  @Test void proactiveSsePersistsRoleOnlyTurnAndFeedback()throws Exception{
    String conv=chapter("companion");companion.save(1,json.valueToTree(Map.of("proactiveEnabled",true,"proactiveIdleSeconds",10)));
    proactive.enqueue(1,conv,"摄影","相机新话题","可以聊聊摄影中的光线。",List.of(Map.of("title","资料","url","https://1.1.1.1/docs")),"test-topic");
    var http=java.net.http.HttpClient.newHttpClient();var req=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/chat/proactive/stream?conversationId="+conv)).header("Authorization","Bearer "+TOKEN).GET().build();
    var response=http.send(req,java.net.http.HttpResponse.BodyHandlers.ofInputStream());assertEquals(200,response.statusCode());
    try(var body=response.body()){
      var received=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{var reader=new java.io.BufferedReader(new java.io.InputStreamReader(body));String line;var data=new StringBuilder();boolean done=false;while((line=reader.readLine())!=null){data.append(line).append('\n');if(line.startsWith("event:done"))done=true;if(done&&line.isBlank())break;}return data.toString();}catch(Exception e){throw new RuntimeException(e);}});
      assertTrue(result(call("/api/chat/proactive/trigger",HttpMethod.POST,Map.of("conversationId",conv))).path("triggered").asBoolean());String stream=received.get(6,java.util.concurrent.TimeUnit.SECONDS);assertTrue(stream.contains("event:proactive"));assertTrue(stream.contains("event:done"));
      assertEquals(0,stories.db().queryForObject("SELECT COUNT(*) FROM history WHERE conversation_id=? AND sender='user'",Integer.class,conv));
      var candidate=proactive.candidates(1,conv).get(0);assertEquals("delivered",candidate.get("status"));result(call("/api/proactive-research/candidates/"+candidate.get("id")+"/feedback",HttpMethod.POST,Map.of("feedback","less_like")));assertEquals("less_like",proactive.candidates(1,conv).get(0).get("feedback"));
      var history=result(call("/api/conversation/"+conv+"/history",HttpMethod.GET,null));assertTrue(history.get(0).path("context_json").asText().contains("isProactive"));
    }finally{proactive.unregister(1,conv);}
  }

  @Test void interestResearchQueuesReliableTopicsAndNegativeFeedbackDisablesInterest()throws Exception{
    String conv=chapter("companion");companion.save(1,json.valueToTree(Map.of("searchEnabled",true,"researchEnabled",true)));companion.add(1,json.valueToTree(Map.of("topic","摄影")),"manual");
    when(web.get(contains("/search?"),eq(true))).thenReturn("{\"results\":[{\"title\":\"摄影的新进展\",\"url\":\"https://1.1.1.1/research\",\"content\":\"摄影的新进展说明\"}]}");when(web.get(contains("/research"),eq(false))).thenReturn("<main>摄影技术带来了新的相机功能。</main>");
    assertTrue((Boolean)proactive.research(1,conv).get("queued"));var candidate=proactive.candidates(1,conv).get(0);proactive.feedback(1,candidate.get("id").toString(),"less_like");assertFalse(companion.context(1).contains("喜欢：摄影"));
  }

  @Test void removedSkillMigrationEndpointsDoNotAffectNormalSkillsAndExport()throws Exception{
    for(String endpoint:List.of("/api/v1/me/capabilities/import","/api/skills/import"))assertTrue(Set.of(404,405).contains(call(endpoint,HttpMethod.POST,Map.of("markdown","---\nname: retired\n---\ntext")).getStatusCode().value()));
    var manifest=Map.of("name","normal-skill-fixture","enabled",true,"instructions","必须调用工具后再回答。","mcpTools",List.of("isPrime"),"keywords",List.of("质数"));
    result(call("/api/skills",HttpMethod.POST,manifest));assertTrue(call("/api/skills",HttpMethod.GET,null).getBody().contains("必须调用工具"));
    var exported=result(call("/api/v1/me/data",HttpMethod.GET,null));assertTrue(exported.path("companion_skill").toString().contains("normal-skill-fixture"));
  }

  @Test void manualInterestFailuresAreExplicitAndKeepDisabledTopics()throws Exception{
    companion.save(1,json.valueToTree(Map.of("semanticInterests",true)));
    companion.add(1,json.valueToTree(Map.of("topic","摄影","sentiment","like")),"conversation");
    companion.updateInterest(1,companion.interests(1).get(0).get("id").toString(),json.valueToTree(Map.of("enabled",false)));
    String conv=chapter("companion");long id=stories.message(conv,"assistant","收到");stories.finish(conv,id,"我长期在周末拍摄风景","收到","complete",List.of(),"");
    when(models.complete(any(),anyList(),anyInt())).thenThrow(new UpstreamHttpFailure(401,false));
    var failure=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->inference.refresh(1));assertTrue(failure.getReason().contains("认证失败"));assertFalse(companion.context(1).contains("喜欢：摄影"));
    doReturn(new OpenAiTransport.Completion("{\"interests\":{}}",new OpenAiTransport.Usage(1,1,false))).when(models).complete(any(),anyList(),anyInt());
    var malformed=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->inference.refresh(1));assertTrue(malformed.getReason().contains("格式不正确"));assertFalse(companion.context(1).contains("喜欢：摄影"));
    for(String raw:List.of("", "{broken JSON}", "{\"interests\":[")){
      when(models.complete(any(),anyList(),anyInt())).thenReturn(new OpenAiTransport.Completion(raw,new OpenAiTransport.Usage(1,1,false)));
      var invalid=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->inference.refresh(1));assertEquals(502,invalid.getStatusCode().value());assertFalse(companion.context(1).contains("喜欢：摄影"));
    }
  }
}
