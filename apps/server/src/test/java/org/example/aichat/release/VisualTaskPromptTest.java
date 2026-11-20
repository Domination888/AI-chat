package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class VisualTaskPromptTest {
  @Test void largeRoleBackgroundCannotReplaceCurrentImageQuestion() {
    String system = VisualTaskPrompt.context("角色", "旧书店开场".repeat(1500), "用户的相册名是旅行", "", "只回答这次用户的问题。");
    var parts = List.of(Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,AQID")), Map.of("type", "text", "text", "图中窗户是什么形状？"));
    var messages = PromptBudget.assemble(system, SkillTaskPrompt.history(List.of(Map.of("sender", "assistant", "content", "走进旧书店，开始新的剧情。"))), "图中窗户是什么形状？", parts);
    assertEquals(parts, messages.get(messages.size()-1).get("content"));
    assertEquals(2, messages.size());
    assertTrue(PromptBudget.tokens((String) messages.get(0).get("content")) <= 2400);
    assertTrue(((String) messages.get(0).get("content")).contains("保持原问题的对象和要求"));
    assertTrue(system.contains("只回答这次用户的问题"));
  }

  @Test void imageConversationRetainsUserReferencesAndReliableSources() {
    String system = VisualTaskPrompt.context("角色", "温和", "用户称左图为甲图", "实际来源：展览图册第八页", "用户更正：第二张才是今天拍摄的。\n本轮请求优先于旧技能格式。");
    var previous = SkillTaskPrompt.history(List.of(Map.of("sender", "user", "content", "左图叫甲图"), Map.of("sender", "assistant", "content", "已记下这个称呼")));
    assertTrue(system.contains("实际来源：展览图册第八页"));
    assertTrue(system.contains("第二张才是今天拍摄的"));
    assertTrue(previous.get(0).get("content").toString().contains("左图叫甲图"));
    assertTrue(previous.get(0).get("content").toString().contains("不是本轮新请求或回复示例"));
    assertTrue(system.contains("区分画面事实与想象"));
  }
}
