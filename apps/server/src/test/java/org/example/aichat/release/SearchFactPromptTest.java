package org.example.aichat.release;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class SearchFactPromptTest {
 @Test void retainsEvidenceScopeAndCurrentQuestionWithoutAddingStoryInstructions(){
  String question="这份手册面向谁？", evidence="Only licensed operators, not first-time users.";
  String system=SearchFactPrompt.context("林间",evidence)+SearchFactPrompt.instruction(question);
  assertTrue(system.contains(evidence));assertTrue(system.contains(question));assertTrue(system.contains("林间"));assertTrue(system.contains("否定、条件、对象和适用范围"));assertFalse(system.contains("剧情发展"));
 }
 @Test void matchingSkillsKeepInteractionInstructionsWithoutBecomingWebEvidence(){
  assertEquals("",SearchFactPrompt.skills(""));
  String context=SearchFactPrompt.skills("【用户启用技能 photo-helper】回答前询问相机类型。");
  assertTrue(context.contains("相机类型"));assertTrue(context.contains("不是本轮已核实的网页事实"));
 }
 @Test void largeQuestionsAreNotPartiallyRepeatedOrTruncated(){
  String question="先前条件。".repeat(100)+"最后不能改变适用范围。";
  String instruction=SearchFactPrompt.instruction(question);assertFalse(instruction.contains("先前条件"));assertTrue(instruction.contains("完整用户消息"));
  var messages=PromptBudget.assemble(SearchFactPrompt.context("角色","来源".repeat(2000))+instruction,java.util.List.of(),question,question);
  assertEquals(question,messages.get(1).get("content"));assertTrue(PromptBudget.tokens(messages.get(0).get("content").toString())<=2400);
 }
}
