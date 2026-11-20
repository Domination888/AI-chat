package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

class ConversationPromptTest {
  @Test void concreteCreativeDeliverablesDoNotRequireRealLifeMemoryOrPromoteRecallRequests() {
    for(String request:List.of("请写完整8000字旅行故事，分20章每章至少400字。","帮我创作一个三章故事。","续写这篇小说。","Please write a travel story."))
      assertFalse(ConversationPrompt.creativeTask(request).isBlank(),request);
    for(String request:List.of("我们上次去了哪里？","请复述你上次写的故事原文。","请写一篇我们上次旅行的真实经历，不要虚构。","请写我们上次旅行的故事。","解释写故事是什么意思。","我喜欢读小说。"))
      assertEquals("",ConversationPrompt.creativeTask(request),request);
  }
  @Test void savedSceneKeepsWholePlansAndDoesNotMistakeOmittedPartsForFacts() {
    var state=new org.example.aichat.rp.RpState();
    assertEquals("",ConversationPrompt.currentScene(state));
    state.location="松林石桥";state.storyTime="明晚";
    String tooLong="很长的约定".repeat(100)+"最终取消";
    state.commitments=List.of(tooLong,"明晚整理星图，待履行");
    String block=ConversationPrompt.currentScene(state);
    assertTrue(block.contains("当前地点：松林石桥"));assertTrue(block.contains("故事时间：明晚"));
    assertTrue(block.contains("已保存约定：明晚整理星图，待履行"));
    assertFalse(block.contains("很长的约定"));assertTrue(block.contains("未载入"));
    assertTrue(PromptBudget.tokens(block)<550);
    assertEquals(tooLong,state.commitments.get(0));
  }
  @Test void currentBackgroundDoesNotDependOnFirstExchangeAndDoesNotPromoteQuestions() {
    String facts="上次我们在河口修好了木钟，今天我想给它换蓝色钟针，你觉得怎么样？";
    String context=ConversationPrompt.currentBackground(facts);
    assertTrue(context.contains(facts));
    assertFalse(context.contains("这是本世界线第一条用户消息"));
    assertEquals("",ConversationPrompt.currentBackground("你还记得我们上次的约定是什么吗？"));
    assertEquals("",ConversationPrompt.currentBackground("请从上一条回复中断的地方继续，不重复已经写过的内容。"));
    String longFacts="我喜欢"+"很长的背景".repeat(120)+"。";
    assertFalse(ConversationPrompt.currentBackground(longFacts).contains(longFacts));
    assertTrue(ConversationPrompt.currentBackground(longFacts).contains("完整内容见本条用户消息"));
  }
  @Test void visibleReplyPolicySurvivesALargeSceneBudget(){
    var state=new org.example.aichat.rp.RpState();state.location="湖畔".repeat(1000);
    String compact=PromptBudget.trim(org.example.aichat.rp.RpPromptBuilder.build(state,"{\"location\":\""+state.location+"\"}","往事".repeat(1000)),400);
    assertTrue(compact.contains("必须先给用户非空的可见台词"));assertTrue(compact.contains("不能只输出场景 JSON"));
    assertTrue(ConversationPrompt.instruction().contains("不能只输出<scene>JSON</scene>"));
  }
  @Test void latestExplicitUserCorrectionIsAvailableOnTheFollowingTurnWithoutRewritingHistory() {
    var rows=List.<Map<String,Object>>of(Map.of("sender","user","content","更正一下，我喜欢热茶。"),Map.of("sender","assistant","content","你喜欢冰茶，我已纠正。"));
    String followup=ConversationPrompt.latestCorrection(rows,"你记得我喜欢什么吗？");
    assertTrue(followup.contains("更正一下，我喜欢热茶。"));assertFalse(followup.contains("你喜欢冰茶"));
    String latest=ConversationPrompt.latestCorrection(rows,"再更正，我喜欢温水。");
    assertTrue(latest.contains("再更正，我喜欢温水。"));assertFalse(latest.contains("热茶"));
    assertEquals("你喜欢冰茶，我已纠正。",rows.get(1).get("content"));
  }
  @Test void normalTurnsAndOversizedCorrectionsDoNotCreatePartialFacts() {
    assertEquals("",ConversationPrompt.latestCorrection(List.of(),"请继续写故事。"));
    assertEquals("",ConversationPrompt.latestCorrection(List.of(),"更正一下："+"事实".repeat(301)+"，不是之前那样。"));
  }
  @Test void questionsEditingRequestsAndQuotedCorrectionsAreNotPromotedToFacts() {
    for(String text:List.of("你记错了吗？","请纠正下面的错别字。","请纠正下面的文字：我喜欢冰茶。","他说“更正一下：我喜欢冰茶。”","“更正一下：我喜欢冰茶。”","'更正一下：我喜欢冰茶。'","请翻译：更正一下，我喜欢冰茶。","我喜欢什么，你记错了吗？"))
      assertEquals("",ConversationPrompt.latestCorrection(List.of(),text),text);
    for(String text:List.of("你记错了，我喜欢桂花茶，不是茉莉茶。","更正一下：我喜欢桂花茶。","我喜欢的是桂花茶，不是茉莉茶。请更正，再确认一句。","不是昨天，而是今天。"))
      assertTrue(ConversationPrompt.latestCorrection(List.of(),text).contains(text),text);
  }
  @Test void oldCorrectionsRemainVisibleWithoutAnUnconditionalOverrideInstruction() {
    String old="用户已纠正，以此为准：旧偏好。\n用户纠正的记忆：旧偏好。\n用户：本轮新更正。\n角色：过去说法。";
    String presented=ConversationPrompt.memory(old);
    assertEquals("旧更正记录：旧偏好。\n用户：本轮新更正。\n角色：过去说法。",presented);
    assertTrue(old.startsWith("用户已纠正，以此为准："));
  }
  @Test void catchphraseIsNotReinforcedAfterItHasAlreadyBeenSaid() {
    var history=List.<Map<String,Object>>of(Map.of("sender","assistant","content","我是测试角色。常看星空。"));
    String persona="身份：测试角色\n表达习惯：我是测试角色。常看星空。从现在开始保持身份。\nspeech：温和自然。\n";
    assertEquals("身份：测试角色\nspeech：温和自然。\n",ConversationPrompt.core(persona,history));
    assertEquals(persona,ConversationPrompt.core(persona,List.of()));
    assertEquals("我是测试角色。常看星空。",history.get(0).get("content"));
  }
  @Test void sceneKeepsOtherChapterEvidenceAndUserFactsWhileDroppingOnlyDuplicateAssistantLines() {
    var history=List.<Map<String,Object>>of(Map.of("sender","assistant","content","已经说过的回答。"));
    String scene="当前地点：海边\n用户：新更正。\n角色（complete）：已经说过的回答。\n角色（complete）：另一篇章里的约定。\n";
    assertEquals("当前地点：海边\n用户：新更正。\n角色（complete）：另一篇章里的约定。\n",ConversationPrompt.scene(scene,history));
    assertEquals(scene,ConversationPrompt.scene(scene,List.of()));
  }
  @Test void savedUiCorrectionReplacesOnlyItsOriginalTurnInModelHistory() {
    var original=Map.<String,Object>of("id",10,"sender","user","content","我喜欢旧茶，也收藏一本书。");
    var reply=Map.<String,Object>of("id",11,"sender","assistant","content","你喜欢旧茶。");
    var unrelated=Map.<String,Object>of("sender","user","content","明天去书店。");
    var feedback=List.<Map<String,Object>>of(Map.of("correction","现在喜欢新茶，不再喜欢旧茶。", "user_text",original.get("content"),"assistant_text",reply.get("content"),"user_history_id",10,"assistant_history_id",11));
    var projected=ConversationPrompt.correctedHistory(List.of(original,reply,unrelated),feedback);
    assertFalse(projected.get(0).get("content").toString().contains("旧茶"));
    assertFalse(projected.get(1).get("content").toString().contains("旧茶"));
    assertSame(unrelated,projected.get(2));
    var repeated=Map.<String,Object>of("id",99,"sender","user","content",original.get("content"));
    assertSame(repeated,ConversationPrompt.correctedHistory(List.of(repeated),feedback).get(0));assertEquals("我喜欢旧茶，也收藏一本书。",original.get("content"));
    assertTrue(ConversationPrompt.savedCorrections(feedback).contains("现在喜欢新茶，不再喜欢旧茶。"));
    assertFalse(ConversationPrompt.correctedScene("用户：我喜欢旧茶，也收藏一本书。\n地点：书店",feedback).contains("我喜欢旧茶"));
    assertTrue(ConversationPrompt.correctedScene("地点：书店",feedback).contains("地点：书店"));
  }
  @Test void savedCorrectionsStayWholeAndNeverSuppressHistoryWithoutAReplacement() {
    var large=Map.<String,Object>of("correction","长".repeat(410),"user_text","old","assistant_text","reply");
    var small=Map.<String,Object>of("correction","新的完整事实。","user_text","a","assistant_text","b");
    assertEquals(List.of(small),ConversationPrompt.boundedCorrections(List.of(large,small)));
    assertEquals("",ConversationPrompt.savedCorrections(List.of(large)));
    var history=List.<Map<String,Object>>of(Map.of("sender","user","content","old"));
    assertSame(history,ConversationPrompt.correctedHistory(history,ConversationPrompt.boundedCorrections(List.of(large))));
  }
}
