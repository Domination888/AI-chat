package org.example.aichat.release;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
class DailyLatestUnreadTest {
 @Test void latestUnreadIssueQueuesEvenWhenOlderThanLocalDay()throws Exception{
  var json=new ObjectMapper();var companion=mock(CompanionService.class);var daily=mock(AiDailyService.class);var skills=mock(SkillRuntime.class);
  when(skills.enabled(1,"ai-daily-juya")).thenReturn(true);when(skills.list(1)).thenReturn(List.of(json.createObjectNode().put("name","ai-daily-juya").put("enabled",true)));
  when(companion.settings(1)).thenReturn(json.createObjectNode().put("dailyEnabled",true).put("dailyScheduleEnabled",true).put("timezone","Asia/Shanghai"));
  var digest=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("{\"unread\":true,\"digest\":{\"date\":\"2026-10-30\",\"title\":\"AI daily\",\"items\":[]}}");when(daily.current(1)).thenReturn(digest);when(daily.zone(1)).thenReturn(java.time.ZoneId.of("Asia/Shanghai"));
  var service=spy(new ProactiveService(mock(StoryStore.class),companion,daily,skills,mock(ChatApi.class),mock(SearchRag.class),mock(Streams.class),json));
  doReturn(true).when(service).enqueue(eq(1),eq("c"),eq("AI 日报"),anyString(),contains("2026-10-30"),any(),eq("daily:2026-10-30"));
  Class<?> target=Class.forName(ProactiveService.class.getName()+"$Target");var ctor=target.getDeclaredConstructor(int.class,String.class);ctor.setAccessible(true);Object t=ctor.newInstance(1,"c");var queue=ProactiveService.class.getDeclaredMethod("queueDaily",target);queue.setAccessible(true);queue.invoke(service,t);
  verify(service).enqueue(eq(1),eq("c"),eq("AI 日报"),anyString(),contains("2026-10-30"),any(),eq("daily:2026-10-30"));
 }
}
