package org.example.aichat.release;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/** Conservative, deterministic extraction of a user's explicit item-count contract. */
record SkillOutputContract(int count, Question question, boolean summary, Closing closing, String literal) {
  enum Closing { NONE, REQUIRED, LITERAL }
  SkillOutputContract(int count,Question question,boolean summary){this(count,question,summary,summary?Closing.REQUIRED:Closing.NONE,"");}
  private static final String QUOTE="(?:“[^”]*”|‘[^’]*’|\"[^\"]*\"|'[^']*'|「[^」]*」|『[^』]*』)";
  private static final Pattern QUOTED=Pattern.compile(QUOTE);
  private static final Pattern LITERAL=Pattern.compile("(?:(?:以|用)\\s*("+QUOTE+")\\s*(?:结尾|收尾|结束)|(?:最后一句|尾句|结尾|收尾)(?:话)?\\s*(?:为|是|改为|改成|换成|：|:)\\s*("+QUOTE+"))");
  private static final Pattern NO_SUMMARY=Pattern.compile("(?:不要|无需|不用|不必|别)\\s*总结");
  private static final Pattern NO_CLOSING=Pattern.compile("(?:不要|无需|不用|不必|别|取消|去掉|删掉|删除)(?:再|加|添加|保留)?\\s*(?:固定尾句|固定结尾|尾句|结尾|收尾|结语)");
  private record Ending(Closing mode,String literal,boolean explicit,boolean invalid) {}
  private static Ending ending(String text){
    var found=new LinkedHashSet<String>();var matcher=LITERAL.matcher(text);boolean invalid=false;
    while(matcher.find()){
      var quotes=QUOTED.matcher(text);while(quotes.find())if(quotes.start()<matcher.start()&&quotes.end()>matcher.start())invalid=true;
      String prefix=text.substring(0,matcher.start());int split=Math.max(Math.max(prefix.lastIndexOf('，'),prefix.lastIndexOf(',')),Math.max(prefix.lastIndexOf('。'),Math.max(prefix.lastIndexOf('；'),prefix.lastIndexOf(';'))));
      String clause=prefix.substring(split+1);
      // Conditions and quotations about instructions must not silently become output commands.
      int sentenceStart=Math.max(Math.max(prefix.lastIndexOf('。'),prefix.lastIndexOf('；')),Math.max(prefix.lastIndexOf(';'),prefix.lastIndexOf('\n')))+1;
      if(prefix.substring(sentenceStart).matches("(?s).*(?:如果|假如|若|否则).*")){invalid=true;continue;}
      if(clause.matches("(?s).*(?:引用|提到|原文|例如|说过|他说|写着|不要|不用|禁止).*")){invalid=true;continue;}
      if(!clause.matches("(?s)\\s*(?:(?:请|并|且|再|然后|最后|这次|本次|请将|将|把|结尾|尾句|最后一句|改为|改成|换成|的|回答|回复|输出)\\s*)*")&&!clause.matches("(?s)\\s*(?:请)?(?:给|列|提供|输出|生成|返回|回复|回答).*")){invalid=true;continue;}
      String quoted=matcher.group(1)!=null?matcher.group(1):matcher.group(2);String literal=quoted.substring(1,quoted.length()-1);
      try{if(literal.isBlank()||!literal.equals(text(com.fasterxml.jackson.databind.node.TextNode.valueOf(literal),600)))invalid=true;}catch(IllegalArgumentException e){invalid=true;}
      found.add(literal);
    }
    boolean cancel=NO_CLOSING.matcher(maskQuotes(text)).find();
    if(found.size()>1||cancel&&!found.isEmpty())invalid=true;
    return new Ending(found.isEmpty()?Closing.NONE:Closing.LITERAL,found.isEmpty()?"":found.iterator().next(),cancel||!found.isEmpty(),invalid);
  }
  private static String maskQuotes(String text){return QUOTED.matcher(text).replaceAll(" ");}
  enum Question { OPTIONAL, ONE, NONE }
  private static final Pattern NUMBER=Pattern.compile("(?<![0-9一二两三四五六七八九十])([0-9]{1,3}|[一二两三四五六七八九十]{1,3})\\s*(条|项|点|步|则|个|句)");
  static Optional<SkillOutputContract> from(List<JsonNode> skills,String message) {
    var base=extract(skills,message);if(base.isEmpty())return base;
    if(message.matches("(?s).*(不要按技能|不用技能|不按技能|不要编号|不分条|不要列表|(?:不要|别|不必)[^，,。；;\\n]{0,4}[0-9一二两三四五六七八九十]+(?:条|项|点|步)).*"))return Optional.empty();
    var latest=extract(List.of(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("instructions",message)),message);
    if(latest.isEmpty()&&message.matches("(?s).*(先?别(?:给)?建议|不要(?:给)?建议|不用(?:给)?建议|(?:只|仅|就)(?:要|需)?(?:回复|回答|说|输出|返回)).*"))return Optional.empty();
    // A current closing or question instruction need not repeat the saved body count.
    var axes=extract(List.of(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("instructions",message+"；给"+base.get().count+"条正文")),message);
    var selected=latest.orElse(axes.orElse(base.get()));
    Question question=selected.question==Question.OPTIONAL?base.get().question:selected.question;
    String current=maskQuotes(message);
    if(current.matches("(?s).*(?:不要|不许|禁止|无需|不需要|别|不)[^，,。；;\\n]{0,5}(?:提问|问我|问用户|问问题|问句|问题).*"))question=Question.NONE;
    boolean summary=selected.summary||base.get().summary;
    if(NO_SUMMARY.matcher(current).find())summary=false;
    Closing closing=summary?Closing.REQUIRED:Closing.NONE;String literal="";boolean cancelled=false;
    for(var skill:skills){var end=ending(skill.path("instructions").asText());if(end.invalid)return Optional.empty();cancelled|=end.explicit&&end.mode==Closing.NONE;if(end.mode==Closing.LITERAL){if(!literal.isEmpty()&&!literal.equals(end.literal))return Optional.empty();literal=end.literal;closing=Closing.LITERAL;}}
    if(cancelled&&!literal.isEmpty())return Optional.empty();
    var end=ending(message);if(end.invalid)return Optional.empty();
    if(end.explicit){closing=end.mode;literal=end.literal;if(closing==Closing.NONE)summary=false;}
    if(closing==Closing.LITERAL){
      if(summary)return Optional.empty(); // One field cannot satisfy both a free summary and an exact literal.
      if(question==Question.NONE&&literal.matches("(?s).*[?？].*"))return Optional.empty();
      String all=skills.stream().map(v->maskQuotes(v.path("instructions").asText())).reduce(current,(x,y)->x+"；"+y);
      if(question==Question.ONE&&all.matches("(?s).*(?:最后|末尾|结尾|收尾)[^，,。；;\\n]{0,10}(?:问|提问|问题|问句).*"))return Optional.empty();
    }
    return Optional.of(new SkillOutputContract(latest.map(SkillOutputContract::count).orElse(base.get().count),question,summary,closing,literal));
  }
  private static Optional<SkillOutputContract> extract(List<JsonNode> skills,String message) {
    var counts=new HashSet<Integer>();boolean ask=false,noAsk=false,summary=false;
    for(var skill:skills){
      String raw=skill.path("instructions").asText();
      var quotedCondition=Pattern.compile("(?:用户)?(?:说到|提到|谈到)(“[^”]*”|\"[^\"]*\"|「[^」]*」)时").matcher(raw);
      while(quotedCondition.find()){String term=quotedCondition.group(1);if(!message.contains(term.substring(1,term.length()-1)))return Optional.empty();}
      String instruction=NO_SUMMARY.matcher(maskQuotes(raw)).replaceAll(" ");
      var matcher=NUMBER.matcher(instruction);
      while(matcher.find()){
        String before=instruction.substring(Math.max(0,instruction.lastIndexOf('；',matcher.start())+1),matcher.start());
        int split=Math.max(Math.max(before.lastIndexOf('，'),before.lastIndexOf(',')),Math.max(before.lastIndexOf('。'),before.lastIndexOf(';')));before=before.substring(split+1);
        String after=instruction.substring(matcher.end(),Math.min(instruction.length(),matcher.end()+14));
        int value=number(matcher.group(1));
        // Input quantities and conditional subjects do not define output shape.
        if(after.matches("(?s)^[^，,。；;\\n]{0,12}(?:时|后|之后|的时候).*"))continue;
        if(after.matches("(?s)^(?:简短|简洁|开放式|小|短|追问的|收尾的){0,3}(?:问题|问句|疑问).*")||before.matches("(?s).*问\\s*$")){
          if(!before.matches("(?s).*(?:问|提问|最后|结尾|末尾|收尾|再|然后|并|以)\\s*$"))continue;
          if(value!=1)return Optional.empty();ask=true;continue;
        }
        if(matcher.group(2).equals("句")&&after.matches("(?s)^(?:简短|简洁)?总结.*")){summary=true;continue;}
        if(before.matches("(?s).*(?:不要|别|不必|禁止)(?:再|先)?(?:给出|给|列出|输出|生成|写出|返回)\\s*$"))return Optional.empty();
        if(before.matches("(?s).*(?:使用|根据|参考|用户提供|用户给出|用户输入|带着|带来|拿着|拿来)\\s*$"))continue;
        if(!before.matches("(?s).*(?:给(?:出|我|用户)?|提供|列(?:出|为)?|输出|生成|整理成|整理为|分成|分为|写(?:出)?|返回|回复|回答|用|以)\\s*(?:恰好|正好|仅|只|共|严格)?\\s*$"))continue;
        if(value<1||value>12||before.matches("(?s).*(至少|至多|最多|最少|不少于|不超过|大约|约莫|如果|若|否则|或者|\\d+\\s*(?:到|至|[-–~]))\\s*.*"))return Optional.empty();
        var condition=Pattern.compile("(?:用户)?(?:说到|提到|谈到)(.{1,40}?)时").matcher(before);
        if(condition.find()&&!message.contains(condition.group(1).replaceAll("[“”\"「」]","")))return Optional.empty();
        if(before.contains("定时")||before.contains("主动开场"))return Optional.empty();
        counts.add(value);
      }
      boolean negative=instruction.matches("(?s).*(不要|不许|禁止|无需|不需要|别|不)[^，,。；;\\n]{0,5}(?:提问|问问题|问句|问题).*" );
      noAsk|=negative;
      String positive=instruction.replaceAll("(?:不要|不许|禁止|无需|不需要|别|不)[^，,。；;\\n]{0,5}(?:提问|问问题|问句|问题)","");
      ask|=positive.matches("(?s).*(?:最后|末尾|结尾|收尾|然后|再|并)[^，,。；;\\n]{0,10}(?:问|提问|问题|问句).*" );
      summary|=instruction.matches("(?s).*(?:最后|末尾|结尾|收尾|然后|再|并)[^，,。；;\\n]{0,12}总结.*" );
    }
    if(counts.size()!=1||ask&&noAsk)return Optional.empty();
    return Optional.of(new SkillOutputContract(counts.iterator().next(),noAsk?Question.NONE:ask?Question.ONE:Question.OPTIONAL,summary));
  }
  private static int number(String text){
    if(text.matches("[0-9]+"))return Integer.parseInt(text);
    String digits="零一二三四五六七八九";if(text.equals("两"))return 2;if(text.equals("十"))return 10;
    if(text.length()==1)return digits.indexOf(text);
    if(text.length()==2&&text.charAt(0)=='十')return 10+digits.indexOf(text.charAt(1));
    return -1;
  }
  Map<String,Object> responseFormat(){
    var properties=new LinkedHashMap<String,Object>();properties.put("items",Map.of("type","array","items",Map.of("type","string","minLength",1),"minItems",count,"maxItems",count));
    properties.put("closing",closing==Closing.LITERAL?Map.of("type","string","enum",List.of(literal)):closing==Closing.REQUIRED?Map.of("type","string","minLength",1,"maxLength",600):Map.of("type","string","enum",List.of("")));
    properties.put("question",(question==Question.NONE||closing==Closing.LITERAL&&question==Question.OPTIONAL)?Map.of("type","string","enum",List.of("")):question==Question.ONE?Map.of("type","string","minLength",1):Map.of("type","string"));
    return Map.of("type","json_schema","json_schema",Map.of("name","user_skill_output","strict",true,"schema",Map.of("type","object","properties",properties,"required",List.of("items","closing","question"),"additionalProperties",false)));
  }
  String instruction(boolean hasImages){return "\n【本轮结构化输出】\n按返回的 JSON schema 作答。items 恰好包含 "+count+" 项不同且有实际内容的正文，不在字符串内重复编号；不要用空项、占位词或重复内容凑数。closing "+(closing==Closing.LITERAL?"必须逐字等于指定尾句；输出顺序为正文、显式要求的问题、尾句，尾句必须是最后内容":closing==Closing.REQUIRED?"必须写技能要求的总结":"必须留空")+"。question "+(question==Question.ONE?(closing==Closing.LITERAL?"只写一个简短问题并以问号结尾，问题位于固定尾句之前":"只写最后的一个简短问题并以问号结尾"):question==Question.NONE?"必须留空，所有内容均不得提问":closing==Closing.LITERAL?"必须留空，不附加追问":"可留空，或写最后的一个问题")+"。只使用普通文本，不输出场景或情绪标记。"+(hasImages?"":"本次模型输入没有可供查看的图片；只能依据用户文字描述提供方法，不得声称看到了具体照片、位置、光线或画面细节。")+"\n";}
  String render(JsonNode result){
    if(result==null||!result.isObject()||result.size()!=3||!result.has("items")||!result.has("closing")||!result.has("question")||!result.path("items").isArray()||result.path("items").size()!=count)throw invalid();
    var items=new ArrayList<String>();var unique=new HashSet<String>();for(var item:result.path("items")){String text=text(item,1600);if(text.isBlank()||!unique.add(text.replaceAll("\\s+","").toLowerCase(Locale.ROOT)))throw invalid();items.add(text);}
    String closing=text(result.path("closing"),600),ending=text(result.path("question"),300);
    if(this.closing==Closing.REQUIRED&&closing.isBlank()||this.closing==Closing.NONE&&!closing.isEmpty())throw invalid();
    if(this.closing==Closing.LITERAL&&(!result.path("closing").asText().equals(literal)||!closing.equals(literal)))throw invalid();
    if(this.closing==Closing.LITERAL&&question==Question.OPTIONAL&&!ending.isEmpty())throw invalid();
    long marks=ending.chars().filter(c->c=='?'||c=='？').count();
    if(question==Question.ONE&&(marks!=1||!ending.matches("(?s).+[?？]$"))||question==Question.NONE&&!ending.isEmpty()||!ending.isEmpty()&&(marks!=1||!ending.matches("(?s).+[?？]$")))throw invalid();
    if(question!=Question.OPTIONAL&&(items.stream().anyMatch(t->t.matches("(?s).*[?？].*"))||closing.matches("(?s).*[?？].*")))throw invalid();
    var out=new StringBuilder();for(int i=0;i<items.size();i++){if(i>0)out.append('\n');out.append(i+1).append(". ").append(items.get(i));}
    if(this.closing==Closing.LITERAL){if(!ending.isEmpty())out.append("\n\n").append(ending);if(!closing.isEmpty())out.append("\n\n").append(closing);}
    else {if(!closing.isEmpty())out.append("\n\n").append(closing);if(!ending.isEmpty())out.append("\n\n").append(ending);}return out.toString();
  }
  private static String text(JsonNode node,int max){
    if(!node.isTextual())throw invalid();String value=node.asText().strip();if(value.length()>max||value.matches("(?is).*<\\s*/?\\s*scene\\b.*"))throw invalid();
    var clean=new EmotionStream(e->{});String text=(clean.append(value)+clean.finish()).strip();if(text.matches("(?i)(?:\\.{2,}|…+|\\[待补充\\]|<placeholder>|TBD)"))throw invalid();return text;
  }
  static IllegalArgumentException invalid(){return new IllegalArgumentException("模型未完成技能要求的条数或收尾格式，本次没有生成合格答复。请检查技能要求，或换用支持严格结构化输出的模型后重试。");}
}
