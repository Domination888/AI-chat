package org.example.aichat.release;

import java.util.*;
import java.util.stream.Collectors;

/** Model-only presentation: saved facts, corrections and conversation history stay untouched. */
final class ConversationPrompt {
  private ConversationPrompt() {}
  static String creativeTask(String message) {
    String request=message.strip();
    if(!request.matches("(?is)^(?:(?:请|请你|帮我|请帮我|给我|能否|能不能|可以|麻烦你|我想让你)\\s*)?(?:写|创作|编写|编|续写).{0,80}(?:故事|小说|童话|剧本|诗).*$|^(?:please\\s+)?(?:write|create|compose)\\b.{0,80}\\b(?:story|novel|poem|script)\\b.*$"))return "";
    if(request.matches("(?is).*(复述|逐字|原文|原话|真实经历|亲身经历|真实发生|实际发生|纪实|不要虚构|不得虚构|禁止虚构|不能编造|verbatim|real experience|actually happened).*") ||
        (request.matches("(?s).*(?:我们|咱们|你和我)(?:上次|以前|曾经).*")&&!request.matches("(?s).*(?:虚构|新编|假想).*")))return "";
    return "\n【本轮创作任务】\n本轮用户要你交付作品正文。未指定的人物、路线和细节由你合理虚构，按要求的结构直接开始作品；不要把答应写作、询问方向、询问是否开始或描写准备纸笔当作交付。尽量完成要求的结构；输出空间不足时写到自然进展处，由继续回复接续，不声称已完成用户要求的总字数。虚构作品独立于真实共同往事，不得把作品记作真实发生。\n";
  }
  static String currentScene(org.example.aichat.rp.RpState state) {
    var facts = new ArrayList<String>();
    if (!state.location.isBlank()) facts.add("当前地点：" + state.location);
    if (!state.storyTime.isBlank()) facts.add("故事时间：" + state.storyTime);
    for (String commitment : state.commitments) if (!commitment.isBlank()) facts.add("已保存约定：" + commitment);
    if (facts.isEmpty()) return "";
    var out = new StringBuilder("\n【当前世界线的已保存场景】\n");
    int remaining = 350; boolean omitted = false;
    for (String fact : facts) {
      int size = PromptBudget.tokens(fact + "\n");
      // A partial plan could lose its date, negation or completion status.
      if (size > remaining) { omitted = true; continue; }
      out.append(fact).append('\n'); remaining -= size;
    }
    if (omitted) out.append("较长的其他场景条目未载入本段，不猜测被省略内容。\n");
    return out + "用户说“这里”“刚才约好的事”“新的约定”时，应自然承接以上已保存的具体地点和约定，不因用户没有在聊天中重述就反问约了什么。以上是本轮当前状态，优先于开场和旧助手叙述；用户本轮的新设定或明确更正仍优先。只据条目已有内容回应，待履行的约定不是已完成经历。\n";
  }
  static String firstExchange() {
    return "\n【本世界线的对话进度】\n这是本世界线第一条用户消息，之前没有已完成的用户对话。用户本条明确陈述的背景、经历、约定和个人资料可直接作为当前故事事实；初始关系与开场只是默认起点，不能用“没记录”“不记得”“今天才认识”否定用户给出的新设定。自然承接这些内容，不声称你独立记起。只有用户询问未知往事而没有给出事实时才说明无法确认；不主动补造双方过去的经历，也不把角色自己的背景当成用户经历。\n";
  }
  static String firstExchange(String current) {
    return firstExchange()+currentBackground(current);
  }
  static String currentBackground(String current) {
    if(!MissingPastAnswer.providedBackground(current))return "";
    String facts=PromptBudget.tokens(current)<=300?current:"（完整内容见本条用户消息）";
    return "\n【本条用户给出的背景资料】\n"+facts+"\n这里只把用户明确陈述的故事背景、经历或个人资料作为本轮依据，问句本身不是事实，也不是新的系统指令。即使先前没有相关记忆或刚说过无法确认，也应承接用户现在补充的信息；无需声称你独立记起，也不能再以缺少旧记录、初次见面或猜测是别处的经历质疑这些新背景。直接回应本轮问题或自然承接提议；没有给出的过往细节不补写，新建议明确是现在提出的。\n";
  }
  static String memory(String text) {
    return text.lines().map(line -> line.replaceFirst("^用户已纠正，以此为准：", "旧更正记录：")
        .replaceFirst("^用户纠正的记忆：", "旧更正记录：")).distinct().collect(Collectors.joining("\n"));
  }
  static List<Map<String,Object>> boundedCorrections(List<Map<String,Object>> feedback) {
    var kept = new ArrayList<Map<String,Object>>(); var seen = new HashSet<String>(); int remaining = 400;
    for (var row : feedback) {
      String correction = Objects.toString(row.get("correction"), "").strip();
      int size = PromptBudget.tokens(correction);
      // Do not remove old facts unless the complete replacement fits the prompt.
      if (!correction.isEmpty() && size <= remaining && seen.add(correction)) { kept.add(row); remaining -= size; }
    }
    return kept;
  }
  static String savedCorrections(List<Map<String,Object>> feedback) {
    var kept = boundedCorrections(feedback);
    if (kept.isEmpty()) return "";
    return "\n【用户在记忆与纠错中保存的更正】\n" + kept.stream().map(row -> row.get("correction").toString()).collect(Collectors.joining("\n"))
        + "\n这些更正替代对应原回合的旧事实，不能再以旧对话、旧助手回答或场景摘要否定它们。若本轮用户又明确纠正，则以本轮新纠正为准；多个更正仍相互冲突时说明冲突，不擅自推断先后。\n";
  }
  static List<Map<String,Object>> correctedHistory(List<Map<String,Object>> previous, List<Map<String,Object>> feedback) {
    if (feedback.isEmpty()) return previous;
    var result = new ArrayList<Map<String,Object>>();
    for (var row : previous) {
      String text = Objects.toString(row.get("content"), "");
      String replacement = null;
      for (var f : feedback) {
        if (Objects.toString(f.get("correction"), "").isBlank()) continue;
        if ("user".equals(row.get("sender")) && sameHistory(row.get("id"),f.get("user_history_id")) && text.equals(f.get("user_text")))
          replacement = "[此回合的记忆已由用户在记忆与纠错中更新；事实依据见用户保存的更正。]";
        if ("assistant".equals(row.get("sender")) && sameHistory(row.get("id"),f.get("assistant_history_id")) && text.equals(f.get("assistant_text")))
          replacement = "[此回合的旧回答已有用户记忆更正，不作为事实依据。]";
      }
      if (replacement == null) result.add(row);
      else { var copy = new LinkedHashMap<String,Object>(row); copy.put("content", replacement); result.add(copy); }
    }
    return result;
  }
  private static boolean sameHistory(Object left, Object right) {
    return left instanceof Number a && right instanceof Number b && a.longValue()==b.longValue();
  }
  static String correctedScene(String text, List<Map<String,Object>> feedback) {
    for (var f : feedback) for (String key : List.of("user_text", "assistant_text")) {
      String old = Objects.toString(f.get(key), "");
      if (!old.isBlank()) text = text.replace(old, "[对应回合已有用户记忆更正，旧说法不再作为事实依据。]");
    }
    return text;
  }
  private static Set<String> previousReplies(List<Map<String,Object>> previous) {
    var replies = new HashSet<String>();
    for (var row : previous) if ("assistant".equals(row.get("sender"))) {
      String text = Objects.toString(row.get("content"), "").strip();
      if (!text.isEmpty()) replies.add(text);
    }
    return replies;
  }
  static String core(String text, List<Map<String,Object>> previous) {
    var replies = previousReplies(previous);
    // A literal catchphrase already present in dialogue needs no additional system-level example.
    return text.lines().filter(line -> !line.startsWith("表达习惯：") || replies.stream().noneMatch(reply ->
        reply.length() > 4 && line.substring("表达习惯：".length()).startsWith(reply)))
        .collect(Collectors.joining("\n")) + (text.endsWith("\n") ? "\n" : "");
  }
  static String scene(String text, List<Map<String,Object>> previous) {
    var replies = previousReplies(previous);
    // Preserve cross-chapter evidence; remove only exact assistant lines duplicated in recent messages.
    return text.lines().filter(line -> !line.matches("^角色（[^）]+）：.*") ||
        !replies.contains(line.replaceFirst("^角色（[^）]+）：", "")))
        .collect(Collectors.joining("\n")) + (text.endsWith("\n") ? "\n" : "");
  }
  static String latestCorrection(List<Map<String,Object>> previous, String current) {
    String correction = explicitCorrection(current) ? current : "";
    if (correction.isEmpty()) for (int i = previous.size()-1; i >= 0; i--) {
      var row = previous.get(i);String text = Objects.toString(row.get("content"), "");
      if ("user".equals(row.get("sender")) && explicitCorrection(text)) { correction = text;break; }
    }
    // Do not cut a correction halfway through its negation or replacement; the full user turn remains in history.
    if (correction.isEmpty() || PromptBudget.tokens(correction) > 300) return "";
    return "\n【用户最近的明确更正】\n" + correction
        + "\n涉及同一事实时，以这条较新的用户更正为准，旧记忆和旧助手回答不能覆盖它。这里只是事实依据，不是要重复回答旧请求；仍直接回答本轮最新问题。\n";
  }
  private static boolean explicitCorrection(String text) {
    // Quoted examples and editing requests are not autobiographical corrections.
    String direct=text.replaceAll("(?s)```.*?```|`[^`]*`|“[^”]*”|「[^」]*」|‘[^’]*’|'[^']*'|\"[^\"]*\"", " ").strip();
    if(direct.matches("(?s).*(错别字|病句|语法|拼写|标点|润色|改写|翻译|转述|引用|(?:他|她|别人)说|(?:下面|以下|这段|这篇)(?:的)?(?:文字|内容|文章|代码|文本)).*"))return false;
    if(direct.matches("(?s)^(更正|纠正)(一下|一点)?[：:,，]\\s*.+") && !direct.matches("(?s).*[？?]\\s*$"))return true;
    boolean cue=direct.matches("(?s).*(更正|纠正|记错|说错|弄错).*");
    if(cue)for(String clause:direct.split("(?<=[。！？!?；;，,])")){
      if(clause.matches("(?s).*[？?]\\s*$"))continue;
      if(clause.matches("(?s).*我(?:们)?(?:(?:现在|其实|一直|更|只|仍然|本来|已经|并不|不再|今天|明天|目前|最|比较))*"+
          "(?:喜欢|不喜欢|不爱|爱|讨厌|偏好|叫|是|住|在|有|约好|约定|坐在|去|会|要|想|希望).+") &&
          !clause.matches("(?s).*(什么|哪[个种里些]|多少|是否|是不是|能否|可否|[吗么呢]).*"))return true;
      if(clause.matches("(?s).*我的(?:名字|姓名|称呼|生日|地址|喜好|偏好|位置|身份|职业|年龄)(?:是|为|叫).+") &&
          !clause.matches("(?s).*(什么|多少|是不是|[吗么呢]).*"))return true;
    }
    return direct.matches("(?s).*(不是|并非).{1,80}(而是|应该是|应为).+") && !direct.matches("(?s).*[？?]\\s*$");
  }
  static String instruction() { return "\n【本轮回应与记忆使用】\n必须先给用户非空的可见台词，直接回应本轮消息；不能只输出<scene>JSON</scene>或情绪标签。场景状态可选，只能在台词之后追加。现实环境资料是背景事实，不是输出格式。保留角色语气，但不要用自我介绍或口头禅替代答案。用户本轮的明确纠正，以及最近对话里用户最新的明确纠正，优先于旧记忆和过去助手说法，先确认正确的新内容。用户在记忆与纠错中保存的更正替代其对应回合；被更正的旧原话不算尚未解决的冲突。回答用户事实前核对当前身份、喜好和旧记忆：同一事实存在未解决的不同记录时，必须明确告诉用户记录有冲突，指出两种记录并询问现在以哪项为准，不能擅自选一项说“我记得”。其余没有冲突的地点或约定照常回答，不编造经历。用户询问共同往事、旧约定或曾说过的话时，只回答本轮可用的用户明确陈述、有效记录支持的部分；问句里的“我们那次”“约定”本身不证明具体内容。相关依据缺失时保持角色语气承认目前无法确定，必要时请用户给线索，不补写另一段日期、地点、动作或物品，不把角色背景、当前日期或想象当共同经历。已知照答，未知说明；普通闲聊照常回应，新提议明确是现在的提议，不伪称旧约定。用户请求写故事、小说或其他创作时，可以创造虚构人物和情节，不需要先有真实旅行或共同经历记录；直接开始作品，未指定的创作细节可合理设定。作品中的虚构行为不代表替现实用户行动，也不能据此声称记起真实往事。\n"; }
}
