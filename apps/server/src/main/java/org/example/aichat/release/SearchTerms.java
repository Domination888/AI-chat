package org.example.aichat.release;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

final class SearchTerms {
  private SearchTerms() {}

  static final Pattern URL=Pattern.compile("https?://[^\\s<>\\\"“”'，。；、！？）)\\]]+",Pattern.CASE_INSENSITIVE);
  private static final Set<String> QUERY_WORDS=Set.of("the","and","for","with","from","this","that","please","find","search","read","open","show","give","what","which","how","first","last","latest","official","tutorial","tutorials","documentation","docs","guide","guides","chapter","chapters","section","sections","title","titles","contents","source","sources","about","difference","differences","between","versus","vs","is","to","of","in","on","it","me","an","as","at","or","site","www","org","com","net");
  private static final Pattern LATIN_WORD=Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{1,}(?:\\.[a-zA-Z][a-zA-Z0-9_-]{1,})*");
  private static final Pattern PUNCTUATED_BRAND=Pattern.compile("(?i)(?<![a-z0-9])([a-z][a-z0-9_-]{1,}(?:\\.[a-z][a-z0-9_-]{1,})+)(?![a-z0-9])");
  static List<String> explicitUrls(String query){
    var urls=new LinkedHashSet<String>();var match=URL.matcher(query);while(match.find()&&urls.size()<3)urls.add(match.group().replaceFirst("[.,;!]+$",""));return new ArrayList<>(urls);
  }
  static double relevance(String query,String title,String url,String snippet){
    String q=query.toLowerCase(Locale.ROOT),text=(title+" "+url+" "+snippet).toLowerCase(Locale.ROOT),lowerTitle=title.toLowerCase(Locale.ROOT),lowerUrl=url.toLowerCase(Locale.ROOT);
    var anchors=anchors(q);long hits=anchors.stream().filter(word->Pattern.compile("(?<![a-z0-9_])"+Pattern.quote(word)+"(?![a-z0-9_])").matcher(text).find()).count();
    if(!anchors.isEmpty()&&hits<Math.min(2,anchors.size()))return 0;
    if(anchors.isEmpty()&&q.codePoints().anyMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN)&&!chineseSubjectMatches(q,text))return 0;
    double score=overlap(q,text)*2+(anchors.isEmpty()?0:2.0*hits/anchors.size());
    var concepts=new ArrayList<List<String>>();
    if(q.matches("(?s).*(教程|入门|tutorial|guide).*"))concepts.add(List.of("tutorial","guide","教程","入门"));
    if(q.matches("(?s).*(文档|documentation|docs).*"))concepts.add(List.of("documentation","docs","文档"));
    if(q.matches("(?s).*(天气|weather).*"))concepts.add(List.of("weather","天气"));
    for(var concept:concepts){if(concept.stream().anyMatch(lowerTitle::contains))score+=3;if(concept.stream().anyMatch(lowerUrl::contains))score+=2;if(concept.stream().anyMatch(snippet.toLowerCase(Locale.ROOT)::contains))score+=1;}
    if(brandDomainAffinity(query,title,url))score+=8;
    return score;
  }
  // Question wording is shared by unrelated pages. Require independent subject
  // evidence before ranking Chinese-only searches; do not turn format bonuses into evidence.
  private static boolean chineseSubjectMatches(String query,String text){
    String subject=query.replaceAll("请|现在|联网|查询|搜索|查找|给出|实际|来源|今天|今日|如何|怎么|有什么|是什么|什么|是否|为什么|附上|提供|告诉我|说明|一下", " ");
    var terms=new LinkedHashSet<String>();
    var runs=Pattern.compile("[\\p{IsHan}]{2,}").matcher(subject);
    while(runs.find()){
      String run=runs.group();
      for(int i=0;i<run.length()-1;i++){
        String term=run.substring(i,i+2);
        if(term.indexOf('的')<0)terms.add(term);
      }
    }
    if(terms.isEmpty())return false;
    long hits=terms.stream().filter(text::contains).count();
    return hits>=Math.min(2,terms.size())&&hits*2>=terms.size();
  }
  static String searchLanguage(String query){return Pattern.compile("[A-Za-z][A-Za-z0-9_-]{3,}").matcher(query).find()||query.codePoints().noneMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN)?"en":"zh-CN";}
  static String searchQuery(String query){
    String text=query.strip().replaceFirst("^(?:请帮我|请|麻烦|帮我|能否|可以)?\\s*(?:联网)?(?:搜索|查找|查询|查看|查阅|读取|搜一下|查一下|查一查|看一下|看看|查)(?:一下)?\\s*","");
    text=text.replaceFirst("(?i)[，,。；;！？?!\\n]\\s*(?:给我|请给|并|附|说明|如果|提供|告诉我|带上|按|只列|列出|不要|若|please\\b|give\\b|provide\\b|include\\b).*$","");
    text=text.replace('：',' ').replaceAll("有什么区别|有何区别|区别是什么","区别");
    text=text.replaceAll("([A-Za-z0-9_])\\s*(?:和|与|以及|及)\\s*(?=[A-Za-z])","$1 ");
    text=text.replaceFirst("[。！？?!，,；;]+$","").replaceAll("\\s+"," ").strip();
    return text.isBlank()?query.strip():text;
  }
  private static LinkedHashSet<String> anchors(String query){
    var found=new LinkedHashSet<String>();var words=LATIN_WORD.matcher(query.toLowerCase(Locale.ROOT).replaceAll("\\b(?:site|inurl|intitle):\\S+"," "));
    while(words.find())if(!QUERY_WORDS.contains(words.group()))found.add(words.group());return found;
  }
  static String focusedQuery(String query){
    if(query.codePoints().noneMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN)||query.matches("(?is).*(?:\\b(?:site|inurl|intitle):|![a-z]).*"))return "";
    var terms=new LinkedHashSet<String>();var words=LATIN_WORD.matcher(query);
    while(words.find())if(!QUERY_WORDS.contains(words.group().toLowerCase(Locale.ROOT)))terms.add(words.group());
    if(terms.isEmpty()||(terms.size()<2&&!requestsOfficial(query)))return "";
    String remaining=LATIN_WORD.matcher(query).replaceAll(" ");
    if(requestsOfficial(query))terms.add("official");
    if(query.matches("(?is).*(官方文档|文档|官网|documentation|\\bdocs\\b).*"))terms.add("documentation");
    if(query.matches("(?is).*(教程|入门|tutorial).*"))terms.add("tutorial");
    if(query.contains("区别")||query.contains("差异"))terms.add("difference");
    if(query.contains("最新"))terms.add("latest");
    // Translate only generic search format; retain other Chinese subject qualifiers.
    remaining=remaining.replaceAll("官方文档|官方资料|文档|官网|官方|教程|入门|区别|差异|最新|有什么|有何|是什么|以及", " ")
      .replaceAll("(^|\\s)(?:和|与|的)(?=\\s|$)"," ")
      .replaceAll("[\\p{Punct}，。；：！？]"," ").replaceAll("\\s+"," ").strip();
    String focused=(String.join(" ",terms)+(remaining.isBlank()?"":" "+remaining)).strip();return focused.equals(query)?"":focused;
  }
  static boolean requestsOfficial(String query){return Pattern.compile("(?i)官方|官网|\\bofficial\\b").matcher(query).find();}
  static boolean brandDomainAffinity(String query,String title,String url){
    if(!requestsOfficial(query))return false;
    var subjects=anchors(query);if(subjects.isEmpty())return false;String primary=subjects.iterator().next();
    try{
      String host=URI.create(url).getHost();if(host==null)return false;
      var labels=new HashSet<>(Arrays.asList(host.toLowerCase(Locale.ROOT).split("\\.")));
      if(labels.contains(primary))return true;
      var brands=PUNCTUATED_BRAND.matcher(title);
      while(brands.find()){
        String brand=brands.group(1).toLowerCase(Locale.ROOT),first=brand.substring(0,brand.indexOf('.'));
        if((first.equals(primary)||brand.equals(primary))&&labels.contains(brand.replace(".","")))return true;
      }
    }catch(IllegalArgumentException ignored){}
    return false;
  }
  static double overlap(String query,String text){String q=query.toLowerCase(Locale.ROOT),t=text.toLowerCase(Locale.ROOT);int hit=0,total=0;for(int i=0;i<q.length()-1;i++){String part=q.substring(i,i+2);if(part.isBlank())continue;total++;if(t.contains(part))hit++;}return total==0?0:(double)hit/total;}
}
