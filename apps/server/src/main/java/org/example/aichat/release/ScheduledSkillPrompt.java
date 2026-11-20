package org.example.aichat.release;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Only a user-scoped persisted skill occurrence supplies executable scheduled instructions. */
final class ScheduledSkillPrompt {
  private ScheduledSkillPrompt() {}
  record Task(String name,String instructions) {
    String request(){return "请执行本次已启用的定时技能“"+name+"”。以下是用户配置的本次任务要求：\n"+instructions;}
  }
  static Optional<Task> fromCandidate(Map<String,Object> candidate){
    var name=candidateName(candidate);if(name.isEmpty())return Optional.empty();
    String instructions=Objects.toString(candidate.get("summary"),"").strip();
    return instructions.isBlank()?Optional.empty():Optional.of(new Task(name.get(),instructions));
  }
  static Optional<String> candidateName(Map<String,Object> candidate){
    String topic=Objects.toString(candidate.get("topic"),"");if(!topic.startsWith("技能："))return Optional.empty();
    String name=topic.substring("技能：".length());
    return name.matches("[a-zA-Z0-9_-]{1,80}")&&Objects.toString(candidate.get("fingerprint"),"").startsWith("skill:"+name+":")?Optional.of(name):Optional.empty();
  }
  static boolean enabled(String name,List<JsonNode> manifests){
    return manifests.stream().anyMatch(s->name.equals(s.path("name").asText())&&s.path("enabled").asBoolean()&&SkillRuntime.scheduled(s)&&s.path("schedule").path("enabled").asBoolean()&&s.path("proactive").path("enabled").asBoolean());
  }
  static String context(Object roleName,String evidence,String skills){
    return "角色称呼："+PromptBudget.trim(Objects.toString(roleName,""),80)+"。当前正在执行用户配置的定时技能。\n"+PromptBudget.trim(evidence,650)+(skills.isBlank()?"":"\n其他匹配技能仅作辅助，本次定时任务的明确要求优先，不能用其他提醒替换：\n"+skills);
  }
  static String instruction(){
    return "\n【本次定时技能任务】\n本轮是定时触发，没有新的用户发言。任务描述中出现关键词，不代表用户现在说出了关键词；“用户说到某事时”等问答条件并未因此成立，不要套用这类条件下的问答格式。直接完成本次定时要求，逐项包含明确指定的内容、提醒对象和动作；保留指定原句，不把它扩写成场景或比喻。简短自然地对用户说，角色口吻只能修饰表达，不能删改任务内容。不要接续旧话题、添加无关剧情或转为开放式闲聊提问。仅通过当前已授权工具执行需要的操作；没有成功的工具结果，不声称操作已完成。直接把提醒对象称为“你”，输出最终提醒正文，不复述“提醒用户”等任务描述，也不要把否定约束或自我声明念给用户。只输出给用户看的内容，不输出任务分析或场景标签。\n";
  }
}
