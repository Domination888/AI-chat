package org.example.aichat.release;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

record SearchPage(String title, String excerpt) {
  static SearchPage parse(String html,String url,String query){
    var doc=Jsoup.parse(html,url);doc.select("script,style,noscript,.headerlink").remove();
    Element body=doc.selectFirst("main,article,[role=main]");if(body==null)body=doc.body();
    StringBuilder out=new StringBuilder();boolean outline=query.matches("(?is).*(目录|章节|前.{0,3}[章节]|contents|chapters?|sections?|headings?).*");
    if(outline){
      Element toc=body.selectFirst("[role=doc-toc],#toc,.toc,.table-of-contents,[class*=toctree]");if(toc==null)toc=body.selectFirst("ol");
      if(toc!=null){
        out.append("页面目录/列表（原顺序与原编号）：\n");var entries=new ArrayList<Element>();
        for(var item:toc.select("li")){boolean nested=false;for(var parent=item.parent();parent!=null&&parent!=toc;parent=parent.parent())if(parent.tagName().equals("li")){nested=true;break;}if(!nested)entries.add(item);}
        for(var item:entries.stream().limit(outlineLimit(query)).toList()){
          var copy=item.clone();copy.select("ul,ol").remove();String title=copy.text();if(item.parent().tagName().equals("ol")&&!title.matches("^\\d+[.、)\\s].*")&&(item.parent().attr("type").isBlank()||item.parent().attr("type").equals("1")))title=orderedNumber(item)+". "+title;Element link=copy.selectFirst("a[href]");
          String href=link==null?"":link.absUrl("href");out.append("目录条目：").append(PromptBudget.trim(title,100));if(samePageOrigin(url,href))out.append(" → ").append(href);out.append('\n');
        }
        for(var item:entries.stream().limit(3).toList()){
          var nested=item.select("li").stream().filter(child->child!=item).map(child->{var own=child.clone();own.select("ul,ol").remove();return own.text();}).distinct().toList();if(!nested.isEmpty())out.append("部分子目录：").append(PromptBudget.trim(String.join("；",nested),150)).append('\n');
        }
      }
      for(var heading:body.select("h1,h2,h3,h4").stream().limit(12).toList())out.append("页面标题：").append(PromptBudget.trim(heading.text(),100)).append('\n');
    }
    body.select("nav,footer,aside,[role=navigation],.sphinxsidebar,.related,.toctree-wrapper").remove();
    // An article's own header can contain the only H1; deleting it makes the
    // adjacent release-date metadata invisible to the preservation pass below.
    // Site banners remain chrome, even when they happen to contain an H1.
    for(var header:body.select("header")){
      boolean contentTitle=header.selectFirst("h1")!=null&&!"banner".equalsIgnoreCase(header.attr("role"))
          &&(header.closest("article")!=null||body.is("main,[role=main]"));
      if(!contentTitle)header.remove();
    }
    // Preserve nearby metadata with its original event label before lexical
    // ranking. A release date and an announcement date are separate evidence.
    Element pageHeading=body.selectFirst("h1");
    if(!outline&&pageHeading!=null){
      int inspected=0,kept=0;StringBuilder metadata=new StringBuilder();
      for(var block:body.select("h1,h2,h3,p,li,pre,dl,time")){
        if(block==pageHeading){inspected=1;continue;}
        if(inspected==0)continue;
        if(inspected++>12||block.tagName().matches("h[123]"))break;
        if(block.parent()!=null&&block.parent().closest("p,li,dl,time")!=null)continue;
        String text=block.text();
        if(text.length()>260||!text.matches("(?is).*(?:date|released?|published|updated|version|日期|发布|更新|版本|\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?\\s+\\d|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}).*"))continue;
        if(block.tagName().equals("time")&&block.hasAttr("datetime"))text+=" [datetime="+block.attr("datetime")+"]";
        String line="标题附近原文（保留日期/版本所指事件）："+text+"\n";
        if(PromptBudget.tokens(metadata.toString()+line)>300)break;
        metadata.append(line);if(++kept>=3)break;
      }
      out.append(metadata);
    }
    boolean overview=query.matches("(?is).*(主要|概览|概述|总结|变化|亮点|overview|summary|highlights|what.s new|major changes).*");
    // URL tokens describe the location, not the information need. Otherwise long
    // URLs overwhelm short cross-language requests during lexical ranking.
    String contentQuery=SearchTerms.URL.matcher(query).replaceAll(" ");
    if(overview&&!outline){
      for(var heading:body.select("h1,h2,h3")){
        if(!heading.text().matches("(?is).*(overview|summary|highlights|概览|概述|主要变化|摘要).*"))continue;
        Element section=heading.parent();
        if(section!=null&&section.tagName().equals("section")){
          for(var block:section.select("p,li")){
            if(block.tagName().equals("li")&&block.parent()!=null&&block.parent().closest("li")!=null)continue;
            String text=block.text();if(!text.isBlank())out.append("页面概览正文：").append(PromptBudget.trim(text,220)).append('\n');
            if(PromptBudget.tokens(out.toString())>=1200)break;
          }
        }
        if(PromptBudget.tokens(out.toString())>=1200)break;
      }
    }
    // Date-bearing list entries often sit far below homepage introductions. Keep
    // the date with its own headline, without inferring what event it represents.
    if(!outline&&query.matches("(?is).*(日期|发布|公告|date|release|announce|publish).*")){
      var dated=new ArrayList<String>();
      for(var block:body.select("li,p")){
        String text=block.text();
        if(text.length()<=400&&text.matches("(?s).*\\b\\d{4}-\\d{2}-\\d{2}\\b.*"))dated.add(text);
      }
      dated.sort(Comparator.comparingDouble((String text)->SearchTerms.overlap(contentQuery,text)).reversed());
      for(String text:dated.stream().distinct().limit(4).toList())out.append("页面带日期条目（日期仅属于本条）：").append(text).append('\n');
    }
    var blocks=new ArrayList<String>();for(var block:body.select("h1,h2,h3,p,li,pre").stream().limit(2000).toList()){String text=block.text();if(!text.isBlank())blocks.add(PromptBudget.trim(text,200));}
    if(blocks.isEmpty()&&!body.text().isBlank())blocks.add(PromptBudget.trim(body.text(),200));
    blocks.sort(Comparator.comparingDouble((String text)->SearchTerms.overlap(contentQuery,text)).reversed());
    for(String text:blocks.stream().distinct().limit(outline?2:overview?8:5).toList())out.append("页面正文摘录：").append(text).append('\n');
    return new SearchPage(doc.title(),PromptBudget.trim(out.toString(),1800));
  }
  private static int orderedNumber(Element item){
    int number=1;try{if(item.parent().hasAttr("start"))number=Integer.parseInt(item.parent().attr("start"));}catch(NumberFormatException ignored){}
    for(var child:item.parent().children())if(child.tagName().equals("li")){try{if(child.hasAttr("value"))number=Integer.parseInt(child.attr("value"));}catch(NumberFormatException ignored){}if(child==item)return number;number++;}return number;
  }
  private static boolean samePageOrigin(String page,String link){try{URI base=URI.create(page),target=URI.create(link);return "https".equals(target.getScheme())&&target.getUserInfo()==null&&Objects.equals(base.getHost(),target.getHost())&&base.getPort()==target.getPort();}catch(Exception e){return false;}}
  private static int outlineLimit(String query){
    var match=Pattern.compile("(?i)(?:前|first\\s+)([1-9]|一|二|三|四|五|六|七|八|九|one|two|three|four|five)\\s*(?:个)?(?:章|节|chapters?|sections?)").matcher(query);
    if(!match.find())return 8;String value=match.group(1).toLowerCase(Locale.ROOT);int count=switch(value){case "一","one"->1;case "二","two"->2;case "三","three"->3;case "四","four"->4;case "五","five"->5;case "六"->6;case "七"->7;case "八"->8;case "九"->9;default->Integer.parseInt(value);};return Math.min(8,count);
  }
}
