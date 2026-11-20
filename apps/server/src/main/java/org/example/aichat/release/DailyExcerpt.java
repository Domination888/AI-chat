package org.example.aichat.release;

import java.util.*;
import java.util.regex.Pattern;

final class DailyExcerpt {
  private DailyExcerpt() {}

  static String focusedDetail(String query,String body){
    // Titles identify the item; they should not outweigh the requested detail.
    // Keep quoted text when it is the entire question (it may be a useful term).
    String unquoted=query.replaceAll("[“\"「『][^”\"」』]{2,400}[”\"」』]", " ").strip();
    String focus=unquoted;
    if(focus.replaceAll("\\s+", "").length()<2)focus=query;
    var emphasis=Pattern.compile("(?i)(?:尤其(?:是)?|重点(?:是|说明)?|特别(?:是|说明)?|具体(?:说说|说明)?|specifically|especially|in particular)[：:,，\\s]*(.+)").matcher(focus);
    if(emphasis.find())focus=emphasis.group(1)+"？"+focus.substring(0,emphasis.start());
    final String detailFocus=focus;
    var parts=Arrays.stream(body.split("(?<=[。！？.!?])\\s*|\\n+")).filter(v->!v.isBlank()).distinct().toList();
    var questions=Arrays.stream(detailFocus.split("[？?；;，,：:。!！]" )).map(DailyExcerpt::normalizeDetailQuestion).filter(v->v.length()>2).toList();
    var ranked=new LinkedHashSet<String>();
    // One best complete sentence per subquestion before spending space on extras.
    // This protects a later question from a long cluster of first-topic sentences.
    for(String question:questions)parts.stream().filter(v->SearchTerms.overlap(question,v)>0).max(Comparator.comparingDouble(v->SearchTerms.overlap(question,v))).ifPresent(ranked::add);
    parts.stream().sorted(Comparator.comparingDouble((String v)->SearchTerms.overlap(normalizeDetailQuestion(detailFocus),v)).reversed()).forEach(ranked::add);
    return String.join("\n",ranked);
  }
  static String completeDetailExcerpt(String rankedBody,int budget){
    var excerpt=new StringBuilder();
    for(String sentence:rankedBody.split("\\n+")){
      String candidate=excerpt.isEmpty()?sentence:excerpt+"\n"+sentence;
      if(PromptBudget.tokens(candidate)<=budget){if(!excerpt.isEmpty())excerpt.append('\n');excerpt.append(sentence);}
    }
    return excerpt.toString();
  }
  private static String normalizeDetailQuestion(String query){
    // Remove whole request phrases; stripping 请 also damaged 申请 in factual questions.
    return query.replaceAll("^\\s*请(?:问|按|附)?", " ").replaceAll("日报|新闻|这一条|这条|原文|详细|说明|介绍|告诉我|什么|一下|依据|只按|内容", " ")
      .replaceAll("[\\p{Punct}，。？、：；]", " ").strip();
  }
}
