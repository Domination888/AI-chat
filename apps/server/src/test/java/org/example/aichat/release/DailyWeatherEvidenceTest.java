package org.example.aichat.release;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class DailyWeatherEvidenceTest {
 @Test void requestedLateDetailPrecedesRepeatedGeneralSummary(){
  String text="Google推出新的编程工具。".repeat(20)+"支持用户导出执行记录和补丁。";
  String focused=DailyExcerpt.focusedDetail("能否导出执行记录？",text);
  assertTrue(focused.indexOf("导出执行记录")<focused.indexOf("编程工具"));
 }
 @Test void observationAgeUsesInstantsAndNeverInventsFutureOrUnknownAge(){
  Instant now=Instant.parse("2030-04-12T23:04:00Z");
  assertTrue(CompanionWorld.observationAge("2030-04-13T07:00:00+08:00",now).contains("4分钟"));
  assertTrue(CompanionWorld.observationAge("2030-04-12T23:00 UTC",now).contains("4分钟"));
  assertTrue(CompanionWorld.observationAge("2030-04-13T00:00:00Z",now).contains("在未来"));
  assertTrue(CompanionWorld.observationAge("2030-04-12T23:00",now).contains("年龄未知"));
  assertTrue(CompanionWorld.observationAge("",now).contains("年龄未知"));
 }
 @Test void quotedLongTitleCannotCrowdOutExplicitOperationalFocus(){
  String title="Acme Studio探索远程代理实验计划与团队工作流整合的后续方向";
  String body=title+"已结束实验。Acme Studio将整合团队工作流。远程代理实验计划进入维护阶段。团队工作流整合尚在准备。远程代理实验涉及多个团队。后续方向由新的工作流承担。用户可用命令行下载归档，再将本地文件导出到存储目录。";
  String query="请依据日报原文详细说明“"+title+"”相关背景及后续工作流整合，尤其数据怎么导出？";
  String focused=DailyExcerpt.focusedDetail(query,body);
  assertTrue(focused.startsWith("用户可用命令行下载归档"));
  for(int budget:new int[]{80,350,650})assertTrue(PromptBudget.trim(focused,budget).contains("本地文件导出"));
 }
 @Test void shortFocusAndUnfocusedRequestsPreserveEvidenceWithoutInventingSelection(){
  String body="工具停止实验。用户可以导出记录。新工具继续研究。";
  assertTrue(DailyExcerpt.focusedDetail("怎么导出？",body).startsWith("用户可以导出记录"));
  String broad=DailyExcerpt.focusedDetail("请详细说明这条日报原文",body);
  assertTrue(broad.contains("工具停止实验"));assertTrue(broad.contains("新工具继续研究"));
 }

 @Test void issueDatesRequireIssueSyntaxAndDoNotConfuseEventDate(){
  assertEquals("2026-10-30",AiDailyService.explicitIssueDate("请聊2026-10-30期AI日报的导出").orElseThrow());
  assertEquals("2026-10-30",AiDailyService.explicitIssueDate("AI日报2026-10-30期的内容").orElseThrow());
  assertTrue(AiDailyService.explicitIssueDate("请看AI日报，事件发生在2026-10-30").isEmpty());
  assertTrue(assertThrows(IllegalArgumentException.class,()->AiDailyService.explicitIssueDate("2026-02-30期AI日报")).getMessage().contains("日报期次无效"));
  assertThrows(IllegalArgumentException.class,()->AiDailyService.explicitIssueDate("比较2026-10-30期AI日报与2026-10-31期AI日报"));
  assertEquals("2026-10-30",AiDailyService.explicitIssueDate("2026-10-30期AI日报，还是2026-10-30期AI日报").orElseThrow());
 }
 @Test void proactiveDatesSeparateIssueEventAndUnrecordedReadingTime(){
  String prompt=ProactiveService.dailyOpening("2026-10-31","2026-10-31");
  assertTrue(prompt.contains("用户当地日期：2026-10-31"));assertTrue(prompt.contains("期刊日期：2026-10-31"));
  assertTrue(prompt.contains("没有阅读时间记录"));assertTrue(prompt.contains("不是新闻事件发生日"));
  assertFalse(prompt.contains("不能称为今日发布"));
 }
}
