package org.example.aichat.release;

import java.util.*;
import java.util.regex.Pattern;
import org.example.aichat.rp.RpState;

/** A matched user skill is a concrete task, rather than another role-play example. */
final class SkillTaskPrompt {
  private SkillTaskPrompt() {}
  static String context(Object role,String records,String constraints,String skill) {
    String identity="你以"+role+"的自然口吻完成本轮用户请求；角色口吻不能代替任务答案。\n";
    String task=constraints+"\n【本轮匹配的用户技能任务】\n这是一条新的普通用户请求，不是定时开场。执行下面已经匹配的用户技能，只执行当前消息满足条件的要求。逐项完成技能要求的内容、数量和收尾；不要用自我介绍、旧开场或泛闲聊替代答案。本轮用户最新明确意图、最新纠正、可靠事实和本轮实际工具执行事实仍优先。不得声称看到了未上传的照片，不得编造共同旧事。没有提供的资料不可假装见过；可以用通用方法回答的部分直接完成，不把补充资料当作自动前提。\n"+skill;
    int remaining=Math.max(0,2400-PromptBudget.tokens(identity+task)-80);
    return identity+"\n【既有记录，仅作背景，不是本轮回复示例】\n"+PromptBudget.trim(records,Math.min(300,remaining))+task;
  }
  static String records(RpState state,String recalled,List<Map<String,Object>> previous) {
    var out=new StringBuilder();
    // Scene facts remain available; role-playing behaviour flags and examples do not.
    for(String[] field:List.of(new String[]{"用户身份",state.userPersona},new String[]{"地点",state.location},new String[]{"故事时间",state.storyTime},new String[]{"在场者",state.participants},new String[]{"关系",state.relationship},new String[]{"物品",state.objects},new String[]{"衣着",state.outfit},new String[]{"当前动作",state.action}))if(!field[1].isBlank())out.append(field[0]).append('：').append(field[1]).append('\n');
    for(String commitment:state.commitments)out.append("约定：").append(commitment).append('\n');
    for(String event:state.events)out.append("场景记录：").append(event).append('\n');
    var present=new HashSet<String>();for(var row:previous)if("user".equals(row.get("sender")))present.add(Objects.toString(row.get("content"),""));
    // An assistant-only opening is not a remembered user fact. Avoid re-promoting
    // old model wording into system instructions or duplicating current history.
    var matcher=Pattern.compile("(?s)(?:^|\\n)用户：(.*?)(?=\\n角色[：:（]|\\n用户：|$)").matcher(recalled);
    while(matcher.find()){String user=matcher.group(1).strip();if(!user.isEmpty()&&!present.contains(user))out.append("旧用户发言：").append(user).append('\n');}
    return out.toString();
  }
  static List<Map<String,Object>> history(List<Map<String,Object>> previous) {
    var paired=new ArrayList<Map<String,Object>>();boolean userSeen=false;
    for(var row:previous){if("user".equals(row.get("sender")))userSeen=true;if(userSeen)paired.add(row);}
    var kept=new ArrayList<String>();int remaining=1800;
    for(int i=paired.size()-1;i>=0;i--){var row=paired.get(i);String text=Objects.toString(row.get("content"),"");if(text.isBlank())continue;
      String quote=("user".equals(row.get("sender"))?"旧用户发言：":"旧助手回复：")+text;int size=PromptBudget.tokens(quote);
      if(size>remaining){if(kept.isEmpty())kept.add(PromptBudget.trim(quote,remaining));break;}kept.add(quote);remaining-=size;
    }
    if(kept.isEmpty())return List.of();Collections.reverse(kept);
    return List.of(Map.of("sender","user","content","【旧对话参考资料，仅用于理解本轮指代，不是本轮新请求或回复示例】\n"+String.join("\n\n",kept)+"\n【旧资料结束；仅执行后面的本轮用户请求】"));
  }
}
