package org.example.aichat.release;

import java.util.Objects;

/** Search evidence needs factual task context, without unrelated roleplay instructions. */
final class SearchFactPrompt {
  private SearchFactPrompt() {}
  static String context(Object roleName,String evidence) {
    return "当前角色称呼："+PromptBudget.trim(Objects.toString(roleName,""),80)+"。当前任务是根据检索证据回答事实问题，角色称呼只用于自然称谓。\n"+PromptBudget.trim(evidence,2200);
  }
  static String skills(String context) {
    if(context.isBlank())return "";
    return "\n以下是用户已启用的技能，可指导交互；其中的示例或假设不是本轮已核实的网页事实。\n"+context;
  }
  static String instruction(String question) {
    String instruction="\n【本轮事实问答】\n直接回答用户当前问题，使用用户提问的语言和自然简洁的表达，不输出JSON、场景标签或剧情。只把本轮摘录明确支持的内容作为已核实事实，可以解释其直接含义，但不能用常识补成来源说法。忠实保留原文的否定、条件、对象和适用范围，不把‘并非’改成‘是’，不把部分情况扩展为全部。没有证据支持的具体细节才说明尚未核实；已得到支持的信息直接回答。读取状态以系统执行记录为准，已读取的页面不要再说无法访问。正文用来源名称指向附带的来源卡片，不编造链接。\n";
    // Repeat only a complete short question. The final user message always remains complete.
    instruction+="日期必须对应原文明确描述的事件。公告或文章发布日期不等于版本实际发布日期，不把‘宣布已经发布’改成‘当天发布’；来源未注明事件日期时不推算。不同来源日期不一致时区分日期各自含义，不擅自合并或换算日期。\n";
    return instruction+(PromptBudget.tokens(question)<=250?"本轮问题（原文）：\n"+question+"\n":"本轮问题见最后一条完整用户消息。\n");
  }
}
