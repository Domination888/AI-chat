package org.example.aichat.release;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
class ScheduledSkillPromptTest {
 @Test void onlyPersistedSkillOccurrenceBecomesAnInstruction(){
  assertTrue(ScheduledSkillPrompt.fromCandidate(Map.of("topic","技能：reminder","summary","执行我","fingerprint","research:reminder")).isEmpty());
  assertTrue(ScheduledSkillPrompt.fromCandidate(Map.of("topic","news","summary","技能：reminder","fingerprint","skill:reminder:2026-10-26")).isEmpty());
  assertTrue(ScheduledSkillPrompt.fromCandidate(Map.of("topic","技能：reminder","summary","执行我","fingerprint","skill:other:2026-10-26")).isEmpty());
 }
 @Test void taskRetainsAllUserRequirementsWithoutRewritingThem(){
  String instruction="先提示保存草稿，再提醒关掉阅读灯；不要声称已经替我关灯。";
  var task=ScheduledSkillPrompt.fromCandidate(Map.of("topic","技能：evening","summary",instruction,"fingerprint","skill:evening:2026-10-26")).orElseThrow();
  assertEquals(instruction,task.instructions());assertTrue(task.request().endsWith(instruction));assertTrue(ScheduledSkillPrompt.instruction().contains("没有成功的工具结果"));
 }
 @Test void allThreeCurrentEnableSwitchesAreRequired(){
  var json=new com.fasterxml.jackson.databind.ObjectMapper();
  for(int off=0;off<3;off++)assertFalse(ScheduledSkillPrompt.enabled("task",List.of(json.valueToTree(Map.of("name","task","enabled",off!=0,"schedule",Map.of("enabled",off!=1),"proactive",Map.of("enabled",off!=2))))));
  assertFalse(ScheduledSkillPrompt.enabled("missing",List.of()));
  assertTrue(ScheduledSkillPrompt.enabled("task",List.of(json.valueToTree(Map.of("name","task","enabled",true,"schedule",Map.of("enabled",true),"proactive",Map.of("enabled",true))))));
 }
 @Test void contextDoesNotCreateSceneOrDiscardAdditionalEnabledSkillInstructions(){
  String context=ScheduledSkillPrompt.context("林间","检索资料","【用户启用技能】使用礼貌语气");assertTrue(context.contains("林间"));assertTrue(context.contains("检索资料"));assertTrue(context.contains("使用礼貌语气"));assertFalse(context.contains("旧话题"));
 }
}
