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

class SkillInstructionsContextTest extends ToolAndSkillContextFixture {

  @Test void keywordSkillRequirementsSurviveWeatherAndTheFinalPromptBudget()throws Exception{
    String instruction="用户说到挑照片时，给出恰好三条简短的选片建议，每条以序号开头，最后问一个问题；不要替用户决定具体照片。"+"建议使用友好的短句，并尊重用户自己的选择。".repeat(5)+"最后的问题必须询问选片进度。";
    assertTrue(PromptBudget.tokens(instruction)>120);assertTrue(PromptBudget.tokens(instruction)<300);
    skills.save(1,json.valueToTree(Map.of("name","photo-helper","enabled",true,"keywords",List.of("挑照片"),"instructions",instruction)));
    ask(chapter(),"今天几号？我准备挑照片了，可以给我一点建议吗？");var messages=captured.get(0);String system=messages.get(0).get("content").toString();assertTrue(system.contains(instruction));assertTrue(system.indexOf(instruction)<system.indexOf("【当地环境资料】"));assertFalse(system.contains(ConversationPrompt.instruction()));assertTrue(system.contains("【本轮匹配的用户技能任务】"));assertTrue(system.contains("最新明确意图、最新纠正、可靠事实和本轮实际工具执行事实仍优先"));assertTrue(system.contains("不得声称看到了未上传的照片"));assertTrue(PromptBudget.tokens(system)<=2400);capture("ordinary-skill",messages);
    ask(chapter(),"今天几号？聊聊晚饭。");String unmatched=captured.get(1).get(0).get("content").toString();assertFalse(unmatched.contains(instruction));assertFalse(unmatched.contains("【本轮匹配的用户技能任务】"));assertTrue(unmatched.contains("【当地环境资料】"));
  }

  @Test void ordinarySkillWithPriorGreetingKeepsTheConcreteCurrentRequest()throws Exception{
    String c=chapter(),question="我正在挑照片，帮我决定从哪里开始。还没有传照片。";
    String snapshot=System.getProperty("skillreplay.snapshot");
    if(snapshot!=null){
      var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:file:"+snapshot+"?mode=ro");var original=new org.springframework.jdbc.core.JdbcTemplate(source);
      var role=original.queryForMap("SELECT * FROM role_card WHERE id=4");
      stories.db().update("INSERT INTO role_card(owner_id,name,profile,background,personality,greeting,avatar,role_code) VALUES(?,?,?,?,?,?,?,?)",1,role.get("name"),role.get("profile"),role.get("background"),role.get("personality"),role.get("greeting"),role.get("avatar"),"skill-replay");
      int id=stories.db().queryForObject("SELECT MAX(id) FROM role_card",Integer.class);
      stories.db().update("INSERT INTO character_profile(role_id,profile_json) VALUES(?,?)",id,original.queryForObject("SELECT profile_json FROM character_profile WHERE role_id=4",String.class));
      var state=stories.sessions().decode(original.queryForObject("SELECT initial_json FROM rp_world LIMIT 1",String.class));c="skill_replay_"+UUID.randomUUID();stories.create(1,c,id,null,state);
      var turns=original.queryForList("SELECT * FROM rp_turn WHERE history_id<6 ORDER BY id");
      for(var turn:turns){String user=turn.get("user_text").toString(),assistant=turn.get("assistant_text").toString();if(!user.isEmpty())stories.message(c,"user",user);long history=stories.message(c,"assistant",assistant);stories.finish(c,history,user,assistant,"complete",List.of(),"");}
      for(var row:original.queryForList("SELECT name,manifest_json FROM companion_skill"))skills.save(1,json.readTree(row.get("manifest_json").toString()));
      String recall=json.readTree(Path.of(System.getProperty("skillreplay.recall")).toFile()).path("recall").asText();when(memory.recall(anyString(),anyString())).thenReturn(recall);
    }else{
      long greeting=stories.message(c,"assistant","让我看看，你手里其实已经有答案了。需要的话，陪你慢慢说。");stories.finish(c,greeting,"","让我看看，你手里其实已经有答案了。需要的话，陪你慢慢说。","complete",List.of(),"");
      skills.save(1,json.valueToTree(Map.of("name","photo-pick","enabled",true,"keywords",List.of("挑照片"),"instructions","用户说到挑照片时恰好给3条选片建议，再问一个简短问题；定时开场时提醒休息眼睛。")));
    }
    String events=ask(c,question);assertTrue(events.contains("event:done"),events);assertFalse(events.contains("event:error"),events);String reply=stories.db().queryForObject("SELECT assistant_text FROM rp_turn WHERE conversation_id=? ORDER BY id DESC LIMIT 1",String.class,c);assertEquals(3,reply.lines().filter(l->l.matches("^[1-9][.] .+")).count());assertEquals(1,reply.chars().filter(ch->ch=='?'||ch=='？').count());var messages=captured.get(0);String system=messages.get(0).get("content").toString();assertTrue(system.contains("本轮匹配的用户技能任务"));assertTrue(system.contains("恰好给3条选片建议"));assertTrue(system.contains("既有记录，仅作背景，不是本轮回复示例"));assertFalse(system.contains("【角色核心人设】"));assertFalse(system.contains("【有出处的最近经历】"));assertFalse(system.contains("从此刻起，我就是"));assertFalse(system.contains("\"style\""));assertFalse(system.contains(ConversationPrompt.instruction()));assertFalse(messages.toString().contains("手里其实已经有答案"));assertTrue(messages.stream().noneMatch(m->"assistant".equals(m.get("role"))));assertTrue(stories.history(1,c,80,null,"").toString().contains("手里其实已经有答案"));assertEquals(question,latestUser(messages));assertTrue(messages.get(messages.size()-1).get("content").toString().contains("本次模型输入没有可供查看的图片"));assertTrue(PromptBudget.tokens(system)<=2400);capture("ordinary-skill-replay",messages);
  }

  @Test void matchedTaskPreservesCorrectionsAndOtherInstructionShapesWithoutAListTemplate()throws Exception{
    String instruction="用户说到出门检查时，给两条出门前检查项，最后用一句总结，不要问问题。";
    skills.save(1,json.valueToTree(Map.of("name","leave-home","enabled",true,"keywords",List.of("出门检查"),"instructions",instruction)));
    String c=chapter();var state=stories.sessions().state(c);state.location="青石门廊";stories.sessions().editScene(c,state);
    stories.message(c,"user","更正一下：我的钥匙放在蓝色背包。");stories.message(c,"assistant","钥匙在红包里。");
    ask(c,"我想做出门检查，不需要你帮我决定行程。");var messages=captured.get(0);String system=messages.get(0).get("content").toString();assertTrue(system.contains(instruction));assertTrue(system.contains("青石门廊"));assertTrue(system.contains("我的钥匙放在蓝色背包"));assertFalse(system.contains("恰好给3条"));assertFalse(system.contains("【角色核心人设】"));assertTrue(system.indexOf(instruction)>system.indexOf("用户最近的明确更正"));capture("ordinary-skill-other-format",messages);
  }

  @Test void matchedSkillCanTranslateThePreviousReplyAsReferenceData()throws Exception{
    String instruction="用户说到翻译成英文时，将所指原文译成英文，只给译文，不要解释，不要问问题。",original="请先保存这份草稿，再休息两分钟。",question="把刚才那段翻译成英文，只给译文。";
    skills.save(1,json.valueToTree(Map.of("name","translate-current","enabled",true,"keywords",List.of("翻译成英文"),"instructions",instruction)));
    String c=chapter();stories.message(c,"user","帮我写一个简短的休息提醒。");long id=stories.message(c,"assistant",original);stories.finish(c,id,"帮我写一个简短的休息提醒。",original,"complete",List.of(),"");
    ask(c,question);var messages=captured.get(0);assertEquals(3,messages.size());assertEquals("user",messages.get(1).get("role"));assertTrue(messages.get(1).get("content").toString().contains("旧对话参考资料"));assertTrue(messages.get(1).get("content").toString().contains(original));assertTrue(messages.get(0).get("content").toString().contains(instruction));assertEquals(question,messages.get(2).get("content"));assertTrue(messages.stream().noneMatch(m->"assistant".equals(m.get("role"))));assertEquals(original,stories.db().queryForObject("SELECT content FROM history WHERE id=?",String.class,id));capture("ordinary-skill-history-transform",messages);
  }

  @Test void structuredFourItemOutputIsValidatedRenderedPersistedAndChargedOnce()throws Exception{
    countedSkill("整理桌面",4);String c=chapter();String events=ask(c,"桌面文件很多，我想整理桌面，从哪里开始？");assertTrue(events.contains("4. "));assertTrue(events.contains("event:done"),events);assertFalse(events.contains("\\\"items\\\""));assertFalse(events.contains("event:error"),events);
    assertEquals(4,json.valueToTree(lastResponseFormat).at("/json_schema/schema/properties/items/maxItems").asInt());verify(models).stream(any(),anyList(),any(),any(),anyMap());verify(models,never()).stream(any(),anyList(),any(),any());verify(quota).record(eq(1),eq(false),eq(structuredUsage));
    String stored=stories.db().queryForObject("SELECT assistant_text FROM rp_turn WHERE conversation_id=? ORDER BY id DESC LIMIT 1",String.class,c);assertTrue(stored.startsWith("1. "));assertTrue(stored.contains("4. "));assertTrue(stored.endsWith("？"));capture("ordinary-skill-four-items",captured.get(0));
  }

  @Test void malformedOrIncompleteStructuredResultsNeverPublishOrPersistSuccessfulText()throws Exception{
    countedSkill("整理桌面",2);
    for(String raw:List.of("not-json","{\"items\":[\"只有一条\"],\"closing\":\"\",\"question\":\"好吗？\"}","{\"items\":[\"甲\",\"乙\"],\"closing\":\"\",\"question\":\"好吗？\"} trailing")){
      clearInvocations(models,quota);doAnswer(call->{((java.util.function.Consumer<String>)call.getArgument(3)).accept(raw);return new OpenAiTransport.Usage(7,8,false);}).when(models).stream(any(),anyList(),any(),any(),anyMap());String c=chapter(),events=ask(c,"帮我整理桌面。");assertTrue(events.contains("event:error"));assertTrue(events.contains("没有生成合格答复"));assertFalse(events.contains("event:text"));assertEquals("failed",stories.db().queryForObject("SELECT status FROM rp_turn WHERE conversation_id=? ORDER BY id DESC LIMIT 1",String.class,c));assertEquals("",stories.db().queryForObject("SELECT assistant_text FROM rp_turn WHERE conversation_id=? ORDER BY id DESC LIMIT 1",String.class,c));verify(quota).record(eq(1),eq(false),eq(new OpenAiTransport.Usage(7,8,false)));verify(models,times(1)).stream(any(),anyList(),any(),any(),anyMap());
    }
  }

  @Test void cancellationDiscardsBufferedJsonAndReleasesTheConversation()throws Exception{
    countedSkill("整理桌面",2);var started=new CountDownLatch(1);var release=new CountDownLatch(1);String c=chapter();
    doAnswer(call->{((java.util.function.Consumer<String>)call.getArgument(3)).accept("{\"items\":[\"partial");started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return new OpenAiTransport.Usage(9,10,true);}).when(models).stream(any(),anyList(),any(),any(),anyMap());
    var pending=CompletableFuture.supplyAsync(()->ask(c,"帮我整理桌面。"));assertTrue(started.await(5,TimeUnit.SECONDS));streams.stop(c);release.countDown();String events=pending.get(5,TimeUnit.SECONDS);assertFalse(events.contains("event:text"));assertFalse(events.contains("event:error"));assertTrue(events.contains("interrupted"));assertEquals("interrupted",stories.db().queryForObject("SELECT status FROM rp_turn WHERE conversation_id=? ORDER BY id DESC LIMIT 1",String.class,c));verify(quota).record(eq(1),eq(false),eq(new OpenAiTransport.Usage(9,10,true)));var next=streams.start(1,c,stories.sessions().worldId(c));streams.finish(c,next);
  }

  @Test void unmatchingDisabledAndSearchTasksKeepTheExistingUnstructuredPath()throws Exception{
    countedSkill("整理桌面",2);ask(chapter(),"聊聊今天的晚饭。");skills.save(1,json.valueToTree(Map.of("name","counted-task","enabled",false,"keywords",List.of("整理桌面"),"instructions","请给2条建议。")));ask(chapter(),"我想整理桌面。");
    countedSkill("整理桌面",2);when(search.search(anyInt(),anyString(),anyList(),any())).thenReturn(new SearchRag.Result("empty",List.of(),List.of(),"本轮没有找到可靠资料。",""));String c=chapter();var headers=new HttpHeaders();headers.setBearerAuth(TOKEN);headers.setContentType(MediaType.APPLICATION_JSON);int role=((Number)stories.owned(1,c).get("role_id")).intValue();assertEquals(200,client.exchange("/api/chat",HttpMethod.POST,new HttpEntity<>(Map.of("conversationId",c,"roleId",role,"message","联网看看整理桌面的办法","search",true),headers),String.class).getStatusCode().value());verify(models,never()).stream(any(),anyList(),any(),any(),anyMap());verify(models,times(3)).stream(any(),anyList(),any(),any());
  }

  @Test void scheduledDescriptionsDoNotPretendToBeNewKeywordUtterances()throws Exception{
    String instruction="用户说到挑照片时，给出恰好三条简短的选片建议，每条以序号开头，最后问一个问题；不要替用户决定具体照片。定时开场时温和提醒休息眼睛。",template="提醒我休息眼睛，并简短询问选片进度。",name="photo-helper";
    skills.save(1,json.valueToTree(Map.of("name",name,"enabled",true,"keywords",List.of("挑照片"),"instructions",instruction,"schedule",Map.of("enabled",true),"proactive",Map.of("enabled",true,"promptTemplate",template))));
    String c=chapter(),id=UUID.randomUUID().toString();stories.db().update("INSERT INTO proactive_candidate(id,user_id,conversation_id,topic,title,summary,sources_json,fingerprint,created_at) VALUES(?,1,?,?,?,?,?,?,?)",id,c,"技能："+name,"提醒",template+"\n"+instruction,"[]","skill:"+name+":"+Instant.now(),Instant.now().toString());
    var complete=new CountDownLatch(1);chat.proactive(1,c,"旧的主动话题",Map.of("candidateId",id),new ChatApi.EventSink(){public void send(String e,Object value){}public void complete(){complete.countDown();}});assertTrue(complete.await(10,TimeUnit.SECONDS));
    var messages=captured.get(0);String system=messages.get(0).get("content").toString();assertFalse(system.contains("【用户启用技能 photo-helper】"));assertFalse(system.contains("【本轮匹配的用户技能任务】"));assertTrue(system.contains("没有新的用户发言"));assertTrue(system.contains("条件并未因此成立"));assertTrue(messages.get(1).get("content").toString().contains(template));capture("scheduled-skill",messages);
  }
}
