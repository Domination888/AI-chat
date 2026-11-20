package org.example.aichat.release;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
class SkillTriggerModeTest {
 final ObjectMapper json=new ObjectMapper();
 JsonNode node(String value)throws Exception{return json.readTree(value);}
 @Test void legacyScheduleRemainsIsolatedEvenWhenPaused()throws Exception{
  for(boolean enabled:new boolean[]{true,false}){
   var skill=node("{\"enabled\":true,\"schedule\":{\"enabled\":"+enabled+"}}");
   assertEquals("schedule",SkillRuntime.triggerMode(skill));assertFalse(SkillRuntime.matches(skill,"天气",false));assertFalse(SkillRuntime.matches(skill,"提醒",true));
  }
 }
 @Test void combinedRequiresAnActualUserKeyword()throws Exception{
  var skill=node("{\"enabled\":true,\"schedule\":{},\"keywords\":[\"纸鹤\"]}");
  assertEquals("keywords_and_schedule",SkillRuntime.triggerMode(skill));assertTrue(SkillRuntime.matches(skill,"纸鹤",false));assertFalse(SkillRuntime.matches(skill,"纸鹤",true));assertFalse(SkillRuntime.matches(skill,"天气",false));
 }
 @Test void explicitModesAndLegacyAlways()throws Exception{
  var skill=node("{\"enabled\":true}");assertEquals("always",SkillRuntime.triggerMode(skill));assertTrue(SkillRuntime.matches(skill,"天气",true));
  skill=node("{\"enabled\":true,\"triggerMode\":\"schedule\",\"keywords\":[\"纸鹤\"],\"schedule\":{}}");assertFalse(SkillRuntime.matches(skill,"纸鹤",false));
  skill=node("{\"enabled\":false,\"triggerMode\":\"always\"}");assertFalse(SkillRuntime.matches(skill,"纸鹤",false));
 }
}
