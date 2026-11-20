package org.example.aichat.release;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Extractive answers for explicit directory requests. No generated chapter facts or renumbering. */
final class SearchDirectoryAnswer {
  private SearchDirectoryAnswer() {}
  private static final String NUM="[0-9]{1,3}|[一二两三四五六七八九十]{1,3}|one|two|three|four|five|six|seven|eight|nine|ten";
  private static final Pattern FIRST=Pattern.compile("(?i)(?:前|first\\s+)("+NUM+")\\s*(?:个)?(?:章|节|项|chapters?|sections?|entries)");
  private static final Pattern NUMBERED=Pattern.compile("^([0-9]+(?:\\.[0-9]+)*|[IVXLCDM]+)[.、)\\s]",Pattern.CASE_INSENSITIVE);
  private static final Pattern CHINESE=Pattern.compile("^第("+NUM+")(?:章|节)",Pattern.CASE_INSENSITIVE);
  record Entry(String title,String link,String key) {}
  record Outline(List<Entry> entries,List<String> topics) {}

  static boolean requested(String query) {
    String q=Objects.toString(query,"");
    if(q.matches("(?is).*(?:创作|拟定|起草|编写|撰写|设计|写一个|写一份|比较|对比|解释|分析|评价|推荐|如何|为什么|制定|规划|安排|调用|执行|compare|explain|analy[sz]e|recommend|execute|draft|compose|write\\s+(?:a|an|the)|design).*"))return false;
    boolean chapter=q.matches("(?is).*(?:章节|第[0-9一二三四五六七八九十]+[章节]|前[0-9一二两三四五六七八九十]+[章节]|chapters?|numbered sections?).*");
    if(!chapter&&q.matches("(?is).*(文件系统|文件夹|工作目录|当前目录|根目录|目录权限|目录(?:中|里|下).*文件|file system|directory permissions).*"))return false;
    boolean action=q.matches("(?is).*(?:列|给出|提供|核对|查找|查看|读取|打开|查阅|摘录|查询|展示|告诉|有哪些|list|show|give|read|open|verify|find).*");
    boolean directory=q.matches("(?is).*(?:目录|table of contents|chapter list).*");
    boolean titles=chapter&&q.matches("(?is).*(?:标题|名称|顺序|列表|titles?|headings?|names?|order).*");
    // Reading a numbered chapter is ordinary evidence-based Q&A. Require positive enumeration intent.
    boolean enumerate=q.matches("(?is).*(?:列出|只列|列举|列一下|list|enumerate)\\s*(?:the\\s+)?(?:前(?:"+NUM+")\\s*[章节]|first\\s+(?:"+NUM+")\\s+chapters?|chapters?\\s+[0-9]+).*");
    boolean bodyOnly=q.matches("(?is).*(?:正文|原文|full text|chapter content).*")&&!directory&&!titles;
    return action&&(directory||titles||enumerate)&&!bodyOnly;
  }
  static String readingState(SearchRag.Result result) {
    if(result==null)return "";
    var out=new StringBuilder("\n【本轮读取状态（系统执行记录）】\n");
    if(result.sources().isEmpty())out.append("本轮未取得可核对的来源。\n");
    for(int i=0;i<result.sources().size();i++)out.append("来源").append(i+1).append(result.sources().get(i).pageRead()?"：页面原文已成功读取。\n":"：只有搜索结果或摘要，页面原文未读取。\n");
    return out.append("页面中列出的链接，不代表链接目标的正文也已读取。\n").toString();
  }
  static Optional<String> answer(String query,SearchRag.Result result) {
    if(result==null||!requested(query))return Optional.empty();
    boolean details=query.matches("(?is).*(主要内容|内容概述|简介|概括|摘要|总结|讲什么|讲了什么|summary|summari[sz]e|covers?|main content).*");
    boolean links=query.matches("(?is).*(来源|链接|网址|\\blinks?\\b|\\bsources?\\b|\\burls?\\b).*");
    int count=requestedCount(query);Set<String> wanted=requestedNumbers(query);
    var out=new StringBuilder();boolean foundOutline=false,foundEntry=false;
    for(int sourceIndex=0;sourceIndex<result.sources().size();sourceIndex++){
      var source=result.sources().get(sourceIndex);if(!source.pageRead())continue;
      var outline=outline(source);if(outline.entries().isEmpty())continue;foundOutline=true;
      var selected=outline.entries().stream().filter(e->wanted.isEmpty()||wanted.contains(e.key())).limit(count>0?count:8).toList();
      if(selected.isEmpty())continue;foundEntry=true;
      if(!out.isEmpty())out.append('\n');out.append("已读取《").append(source.title()).append("》的目录，以下保留原顺序与原编号：\n");
      for(var entry:selected){
        out.append('\n').append(entry.title()).append('\n');
        if(links&&!entry.link().isBlank())out.append("目录中的链接：").append(entry.link()).append('\n');
        if(details){
          var topics=outline.topics().stream().filter(t->childOf(t,entry.key())).toList();
          if(!topics.isEmpty())out.append("目录列出的主题（不是章节全文摘要）：").append(String.join("；",topics)).append('\n');
          else out.append("本轮目录未列出可归属于此项的子目录，不能据标题补写主要内容。\n");
          var bodySource=result.sources().stream().filter(s->s.pageRead()&&!entry.link().isBlank()&&!entry.link().contains("#")&&!s.url().equals(source.url())&&s.url().equals(entry.link())).findFirst();
          var paragraphs=bodySource.map(SearchDirectoryAnswer::body).orElse(List.of());
          if(!paragraphs.isEmpty())out.append("已取得的章节正文摘录（来源").append(result.sources().indexOf(bodySource.get())+1).append("）：").append(String.join("\n",paragraphs)).append('\n');
          else if(bodySource.isPresent()||result.sources().stream().anyMatch(s->s.pageRead()&&!entry.link().isBlank()&&samePage(s.url(),entry.link())))out.append("该页面已读取，但本轮未提供可归属于此项的正文摘录。\n");
          else out.append("该目录项的正文：本轮未读取。\n");
        }
      }
      if(details){
        var unattributed=outline.topics().stream().filter(t->outline.entries().stream().noneMatch(e->childOf(t,e.key()))).toList();
        if(!unattributed.isEmpty())out.append("\n页面还列出以下子目录，但本轮资料未明确其所属章节：").append(String.join("；",unattributed)).append('\n');
      }
      if(count>0&&selected.size()<count)out.append("\n本轮只取得 ").append(selected.size()).append(" 项匹配目录，不能据此补出其余条目。\n");
      if(!wanted.isEmpty()&&!selected.stream().map(Entry::key).collect(java.util.stream.Collectors.toSet()).containsAll(wanted))out.append("\n本轮目录未覆盖所有请求的编号，缺失项尚不能确认。\n");
      out.append("\n来源：本条回复附带的来源").append(sourceIndex+1).append("《").append(source.title()).append("》。\n");
    }
    if(!foundEntry){
      if(result.sources().isEmpty()&&!result.reason().isBlank())out.append(result.reason().strip()).append("\n\n");
      if(foundOutline)out.append("已读取页面目录，但本轮摘录没有所请求编号的条目，不能确认这些章节。\n");
      else if(result.sources().stream().anyMatch(SearchRag.Source::pageRead))out.append("本轮已读取来源页面，但取得的摘录没有可核对的目录条目，不能确认章节标题和顺序。\n");
      else if(!result.sources().isEmpty())out.append("本轮只有搜索结果或摘要，页面原文未读取，也没有可核对的目录条目，不能确认章节标题和顺序。\n");
      else out.append("本轮未取得可核对的来源，不能确认目录或章节。\n");
    }
    return Optional.of(out.toString().strip());
  }
  static Outline outline(SearchRag.Source source) {
    var entries=new LinkedHashMap<String,Entry>();var topics=new LinkedHashSet<String>();
    for(String excerpt:source.excerpts())for(String line:excerpt.split("\\R")){
      if(line.startsWith("目录条目：")){
        String text=line.substring("目录条目：".length()).strip();int arrow=text.lastIndexOf(" → ");
        String title=arrow<0?text:text.substring(0,arrow).strip(),link=arrow<0?"":safeLink(text.substring(arrow+3).strip());
        if(!title.isBlank()&&entries.size()<32)entries.putIfAbsent(title+"\n"+link,new Entry(title,link,key(title)));
      }else if(line.startsWith("部分子目录："))for(String topic:line.substring("部分子目录：".length()).split("；"))if(!topic.isBlank()&&topics.size()<64)topics.add(topic.strip());
    }
    return new Outline(List.copyOf(entries.values()),List.copyOf(topics));
  }
  private static List<String> body(SearchRag.Source source){
    return source.excerpts().stream().flatMap(s->s.lines()).filter(s->s.startsWith("页面正文摘录：")).map(s->s.substring("页面正文摘录：".length())).filter(s->!s.isBlank()).limit(2).toList();
  }
  private static String key(String title){var n=NUMBERED.matcher(title);if(n.find())return n.group(1).toUpperCase(Locale.ROOT);var c=CHINESE.matcher(title);return c.find()?Integer.toString(number(c.group(1))):"";}
  private static boolean childOf(String topic,String key){return !key.isBlank()&&Pattern.compile("^"+Pattern.quote(key)+"\\.[0-9]+(?:[.、)\\s]|$)",Pattern.CASE_INSENSITIVE).matcher(topic).find();}
  private static boolean samePage(String left,String right){return left.replaceFirst("#.*$","").equals(right.replaceFirst("#.*$",""));}
  private static String safeLink(String link){try{URI u=URI.create(link);return Set.of("https","http").contains(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null?link:"";}catch(Exception e){return "";}}
  private static int requestedCount(String query){var match=FIRST.matcher(query);return match.find()?number(match.group(1)):0;}
  private static Set<String> requestedNumbers(String query){
    var numbers=new LinkedHashSet<String>();var match=Pattern.compile("第("+NUM+")(?:章|节)",Pattern.CASE_INSENSITIVE).matcher(query);
    while(match.find())numbers.add(Integer.toString(number(match.group(1))));
    var range=Pattern.compile("第("+NUM+")(?:到|至|-)(?:第)?("+NUM+")(?:章|节)",Pattern.CASE_INSENSITIVE).matcher(query);
    while(range.find())for(int n=number(range.group(1)),end=number(range.group(2));n<=end;n++)numbers.add(Integer.toString(n));
    var english=Pattern.compile("(?i)\\bchapters?\\s+([0-9]{1,3})(?:\\s*(and|,|to|-)\\s*([0-9]{1,3}))?").matcher(query);
    while(english.find()){
      int first=Integer.parseInt(english.group(1));numbers.add(Integer.toString(first));
      if(english.group(3)!=null){int last=Integer.parseInt(english.group(3));if(Set.of("to","-").contains(english.group(2).toLowerCase(Locale.ROOT)))for(int n=first;n<=last;n++)numbers.add(Integer.toString(n));else numbers.add(Integer.toString(last));}
    }
    return numbers;
  }
  private static int number(String token){
    String value=token.toLowerCase(Locale.ROOT);if(value.matches("[0-9]+"))return Integer.parseInt(value);
    int word=List.of("one","two","three","four","five","six","seven","eight","nine","ten").indexOf(value);if(word>=0)return word+1;
    value=value.replace('两','二');int ten=value.indexOf('十');if(ten>=0){int high=ten==0?1:"一二三四五六七八九".indexOf(value.charAt(0))+1;int low=ten==value.length()-1?0:"一二三四五六七八九".indexOf(value.charAt(ten+1))+1;return high*10+low;}
    return "一二三四五六七八九".indexOf(value)+1;
  }
}
