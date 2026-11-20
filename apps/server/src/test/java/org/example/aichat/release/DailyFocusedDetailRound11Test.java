package org.example.aichat.release;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DailyFocusedDetailRound11Test {
  private static final String QUOTA="Ultra的周使用额度保持不变，是Pro的10倍。";
  private static final String VERIFY="Claude订阅产品负责人补充称，账户验证自动进行，目前尚无主动申请方式，团队正在探索。";
  private static final String BODY="订阅方案更名并逐步取消部分账户的五小时限制。\n"+QUOTA+"\n"+java.util.stream.IntStream.range(0,16).mapToObj(i->"第"+i+"类新账户仍适用临时限制，适用范围需查阅后续说明。").collect(java.util.stream.Collectors.joining())+"\n"+VERIFY;
  @Test void exactQuestionKeepsVerificationAmongFirstCompleteRelevantSentences(){
    String query="请按2026-10-31期《Anthropic 更名 Claude 订阅方案，逐步取消已验证 Ultra 账户五小时限制》回答：Ultra的周使用额度是否改变，是Pro的多少倍？账户验证能否主动申请？请附来源，没写的别补。";
    var lines=DailyExcerpt.focusedDetail(query,BODY).lines().toList();
    assertTrue(lines.indexOf(QUOTA)<3);assertTrue(lines.indexOf(VERIFY)<3,lines.toString());
    String excerpt=DailyExcerpt.completeDetailExcerpt(String.join("\n",lines),179);
    assertTrue(excerpt.contains(QUOTA));assertTrue(excerpt.contains(VERIFY));assertTrue(PromptBudget.tokens(excerpt)<=179);
  }
  @Test void punctuationIsSplitBeforeNormalizationAndApplicationWordSurvives(){
    for(String punctuation:List.of("？","?","；",";","，",",","：")){
      String ranked=DailyExcerpt.focusedDetail("周使用额度是否改变"+punctuation+"账户验证能否主动申请？",BODY);
      assertEquals(QUOTA,ranked.lines().findFirst().orElseThrow());assertEquals(VERIFY,ranked.lines().skip(1).findFirst().orElseThrow());
    }
  }
  @Test void capturedRound11ArticleKeepsAllRequestedFactsAt650BodyAllowance(){
    String body="Anthropic 宣布将 Claude 的两个 Max 订阅方案分别更名为 Max 和 Ultra，价格不变，并逐步取消已验证 Ultra 账户的五小时使用限制。Ultra 的周额度保持不变，未验证账户仍有临时限制。\nAnthropic 宣布将 Claude Max 5x 更名为 Max、Max 20x 更名为 Ultra，月费分别维持 100 美元和 200 美元。\n\nAnthropic 正逐步取消已验证 Ultra 账户的 5 小时使用限制，首先覆盖大多数现有订阅用户；新账户或尚未验证的账户仍有临时限制。Ultra 的周使用额度不变，仍为 Pro 的 10 倍。\n\nClaude 订阅产品负责人 Mike Overell 补充称，验证自动进行，目前尚无主动申请方式，团队正在探索。\n\n \n\n ";
    String query="请按2026-10-31期《 》回答：Ultra的周使用额度是否改变，是Pro的多少倍？账户验证能否主动申请？请附来源，没写的别补。";
    String ranked=DailyExcerpt.focusedDetail(query,body);
    String excerpt=DailyExcerpt.completeDetailExcerpt(ranked,479);
    assertTrue(excerpt.contains("周使用额度不变，仍为 Pro 的 10 倍。"));
    assertTrue(excerpt.contains("验证自动进行，目前尚无主动申请方式，团队正在探索。"));
    assertTrue(PromptBudget.tokens(excerpt)<=479);assertTrue(excerpt.endsWith("。"));
  }
  @Test void budgetNeverLeavesHalfSentenceAndCanSkipAnOversizeSentence(){
    String huge="超长内容".repeat(100)+"。";String shortSentence="申请自动验证。";
    assertEquals(shortSentence,DailyExcerpt.completeDetailExcerpt(huge+"\n"+shortSentence,30));
    assertEquals("",DailyExcerpt.completeDetailExcerpt(huge,30));
    var excerpt=DailyExcerpt.completeDetailExcerpt(DailyExcerpt.focusedDetail("Ultra额度；主动申请？",BODY),650);
    assertTrue(excerpt.endsWith("。"));assertTrue(PromptBudget.tokens(excerpt)<=650);
  }
}
