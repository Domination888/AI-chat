package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.example.aichat.rp.RpState;
import org.junit.jupiter.api.Test;

class SkillTaskPromptTest {
  @Test void factsExcludeAssistantOnlyOpeningsAndBehaviourMetadata() {
    var state=new RpState();state.mode="companion";state.style="story";state.location="蓝桥";state.directorNote="模仿旧开场";state.commitments=List.of("周五取书");
    String facts=SkillTaskPrompt.records(state,"用户：\n角色：重复的开场。\n用户：我把钥匙放在蓝色包里。\n角色：我看见你拿着红色包。",List.of());
    assertTrue(facts.contains("蓝桥"));assertTrue(facts.contains("周五取书"));assertTrue(facts.contains("我把钥匙放在蓝色包里"));assertFalse(facts.contains("重复的开场"));assertFalse(facts.contains("红色包"));assertFalse(facts.contains("companion"));assertFalse(facts.contains("模仿旧开场"));
  }
  @Test void recalledUserStatementsAlreadyInHistoryAreNotPromotedAgain() {
    String user="我把钥匙放在蓝色包里。";
    assertFalse(SkillTaskPrompt.records(new RpState(),"用户："+user+"\n角色：收到。",List.of(Map.of("sender","user","content",user))).contains(user));
  }
  @Test void referencedHistoryPreservesRecentReplyTextButExcludesUnpairedGreeting() {
    String reply="先整理资料，再保存草稿，最后休息。";
    var previous=List.<Map<String,Object>>of(Map.of("sender","assistant","content","固定开场"),Map.of("sender","user","content","给我一个工作安排"),Map.of("sender","assistant","content",reply));
    var projected=SkillTaskPrompt.history(previous);assertEquals(1,projected.size());assertEquals("user",projected.get(0).get("sender"));assertTrue(projected.get(0).get("content").toString().contains(reply));assertFalse(projected.get(0).get("content").toString().contains("固定开场"));assertEquals(3,previous.size());
  }
}
