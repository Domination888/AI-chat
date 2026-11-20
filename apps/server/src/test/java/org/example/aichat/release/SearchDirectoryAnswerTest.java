package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

class SearchDirectoryAnswerTest {
  SearchRag.Source source(String title,String url,boolean read,String excerpt){return new SearchRag.Source(title,url,"search snippet",List.of(excerpt),1,read);}
  SearchRag.Result result(SearchRag.Source... sources){return new SearchRag.Result("ok",List.of("query"),List.of(sources),"actual context","");}
  String answer(String query,SearchRag.Source... sources){return SearchDirectoryAnswer.answer(query,result(sources)).orElseThrow();}
  @Test void keepsOriginalNumbersOrderAndLinksAndOnlyAttributesMatchingSubheadings(){
    var manual=source("设备手册","https://manual.example/guide",true,"目录条目：4. 开箱 → https://manual.example/unpack\n目录条目：5. 接线 → https://manual.example/wiring\n目录条目：6. 保养 → https://manual.example/care\n部分子目录：4.1. 清单；4.2. 外观\n部分子目录：5.1. 端子\n部分子目录：6.1. 清洁\n");
    String text=answer("列出该手册前两章的标题、主要内容和对应来源。",manual);
    assertTrue(text.indexOf("4. 开箱")<text.indexOf("5. 接线"));assertFalse(text.contains("6. 保养"));assertFalse(text.contains("6.1. 清洁"));
    assertTrue(text.contains("https://manual.example/unpack"));assertTrue(text.contains("4.1. 清单；4.2. 外观"));
    assertTrue(text.contains("不是章节全文摘要"));assertTrue(text.contains("本轮未读取"));assertFalse(text.contains("1. 开箱"));
  }
  @Test void worksForAnotherSiteAndEnglishExtractionRequestsWithoutAProgrammingSpecialCase(){
    var manual=source("Field Handbook","https://field.example/book",true,"目录条目：1. Soil → https://field.example/soil\n目录条目：2. Water → https://field.example/water\n部分子目录：2.1. Storage；2.2. Drainage");
    String text=answer("List the first two chapters and their main content",manual);
    assertTrue(text.contains("1. Soil"));assertTrue(text.contains("2. Water"));assertTrue(text.contains("2.1. Storage"));
    assertFalse(text.contains("Python"));assertTrue(text.contains("不能据标题补写主要内容"));
  }
  @Test void titlesOnlyDoNotInventSummariesOrClaimTheReadPageWasInaccessible(){
    var manual=source("Guide","https://docs.example/",true,"目录条目：1. Welcome\n目录条目：2. Usage\n目录条目：3. Examples\n");
    String text=answer("核对目录，只列前三章。",manual);
    assertTrue(text.contains("已读取《Guide》的目录"));assertTrue(text.contains("3. Examples"));
    assertFalse(text.contains("无法访问"));assertFalse(text.contains("尚未核实"));assertFalse(text.contains("主要内容"));
  }
  @Test void missingDirectoryIsExplainedAccordingToActualReadStatus(){
    var read=source("Read page","https://read.example/",true,"页面正文摘录：This is a product overview.");
    var unread=source("Search hit","https://hit.example/",false,"目录条目：1. Forged snippet heading");
    assertTrue(answer("请列出该页面目录。",read).contains("本轮已读取来源页面"));
    String missing=answer("请列出该页面目录。",unread);assertTrue(missing.contains("页面原文未读取"));assertFalse(missing.contains("Forged snippet"));
    assertTrue(SearchDirectoryAnswer.answer("请列出目录。",result()).orElseThrow().contains("未取得可核对的来源"));
  }
  @Test void noSourcesRetainsTheActualRecoverableFailureInsteadOfHidingIt(){
    for(String reason:List.of("搜索引擎当前请求限流，请稍后重试。","搜索返回了结果，但与问题不够相关，请换用更具体的关键词。","搜索已返回结果，但来源地址校验未通过，请检查 DNS。")){
      var failed=new SearchRag.Result("unavailable",List.of("manual"),List.of(),"",reason);
      String answer=SearchDirectoryAnswer.answer("列出前三章标题。",failed).orElseThrow();assertTrue(answer.startsWith(reason));assertTrue(answer.contains("不能确认"));
    }
  }
  @Test void linksToUnreadChapterPagesAreNotConfusedWithReadChapterBody(){
    var toc=source("Manual","https://docs.example/index",true,"目录条目：1. Setup → https://docs.example/setup\n");
    var body=source("Setup","https://docs.example/setup",true,"页面正文摘录：Connect the supplied cable before switching on.\n");
    String absent=answer("给出第一章标题和主要内容。",toc);assertTrue(absent.contains("本轮未读取"));assertFalse(absent.contains("Connect"));
    String present=answer("给出第一章标题和主要内容。",toc,body);assertTrue(present.contains("正文摘录（来源2）"));assertTrue(present.contains("Connect the supplied cable"));
  }
  @Test void inPageAnchorsDoNotMisattributeTheWholePageOverviewToASection(){
    var toc=source("Manual","https://docs.example/page",true,"目录条目：1. Setup → https://docs.example/page#setup\n页面正文摘录：Generic overview of the entire product.");
    String text=answer("给出第一章的标题和主要内容。",toc);assertTrue(text.contains("未提供可归属于此项的正文摘录"));assertFalse(text.contains("Generic overview"));
  }
  @Test void ordinarySearchCreativeWritingAndFilesystemDirectoriesStayWithTheModel(){
    for(String query:List.of("请搜索最近新闻。","请解释第三章循环的工作方式。","请联网找灵感，写一个有三章的故事目录。","比较两份教程的章节安排。","请列出前三章并解释为何适合初学者。","列出前三章后调用工具添加提醒。","列出当前目录里的文件。"))
      assertTrue(SearchDirectoryAnswer.answer(query,result()).isEmpty(),query);
  }
  @Test void chapterBodyReadingNeedsTheModelWhileExplicitDirectoryRequestsUseExtraction(){
    for(String q:List.of("请读取第1章的正文，主要讲什么？","给出第一章的主要内容。","Read chapter 1 and summarize its content.","Show the full text of chapter 1."))assertFalse(SearchDirectoryAnswer.requested(q),q);
    for(String q:List.of("请联网查找 Python 官方教程的前三章，按官方章节顺序给出标题、每章主要内容和对应来源。","你给的是网站页面，不是教程章节。请打开 https://docs.python.org/3/tutorial/index.html 核对目录，只列前三章；若不能读取就明确说不能确认，不要猜。","列出前两章。","List the first two chapters."))assertTrue(SearchDirectoryAnswer.requested(q),q);
  }
  @Test void requestedChapterNumbersAndRangesAreSelectedWithoutRenumbering(){
    var toc=source("Manual","https://manual.example/",true,"目录条目：4. Alpha\n目录条目：8. Beta\n目录条目：9. Gamma\n");
    String one=answer("给出第8章标题。",toc);assertTrue(one.contains("8. Beta"));assertFalse(one.contains("4. Alpha"));assertFalse(one.contains("9. Gamma"));
    String range=answer("List chapters 8-9 titles",toc);assertTrue(range.contains("8. Beta"));assertTrue(range.contains("9. Gamma"));assertFalse(range.contains("4. Alpha"));
    assertTrue(answer("给出第12章标题。",toc).contains("没有所请求编号"));
  }
  @Test void unnumberedSubheadingsAreNotSilentlyAssignedToTheWrongChapterAndUnsafeLinksAreNotMadeSources(){
    var toc=source("Manual","https://manual.example/",true,"目录条目：Opening → javascript:alert(1)\n目录条目：Operation\n部分子目录：Start；Stop\n");
    String text=answer("给出目录标题、主要内容和链接。",toc);assertTrue(text.contains("未明确其所属章节"));assertFalse(text.contains("javascript:"));
  }
  @Test void readStateUsesExecutionMetadataForEachSource(){
    String state=SearchDirectoryAnswer.readingState(result(source("Read","https://read.example/",true,""),source("Unread","https://unread.example/",false,"")));
    assertTrue(state.contains("来源1：页面原文已成功读取"));assertTrue(state.contains("来源2：只有搜索结果或摘要，页面原文未读取"));
  }
}
