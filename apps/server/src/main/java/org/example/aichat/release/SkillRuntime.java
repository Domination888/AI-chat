package org.example.aichat.release;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import java.util.*;

@Service @DependsOn("releaseMigrations")
public class SkillRuntime {
  static final String DEFAULT_SCHEDULE_ZONE = "Asia/Shanghai";
  static final int DEFAULT_SCHEDULE_HOUR = 10;
  static final int DEFAULT_SCHEDULE_MINUTE = 0;

  private final StoryStore stories;private final ObjectMapper json;private final CompanionService companion;private final AiDailyService daily;
  public SkillRuntime(StoryStore stories,ObjectMapper json,CompanionService companion,AiDailyService daily){this.stories=stories;this.json=json;this.companion=companion;this.daily=daily;}
  private List<ObjectNode> defaults(){return List.of(json.createObjectNode().put("name","ai-daily-juya").put("description","AI 日报读取与主动开场").put("enabled",true).put("instructions","用户询问日报时使用真实期刊和来源；没有新刊时说明实际日期。"),json.createObjectNode().put("name","weather-lookup").put("description","当地时间、农历、天气与节气").put("enabled",true).put("instructions","天气仅使用已查询的观测值，说明更新时间，不凭空猜测。"));}
  public List<JsonNode> list(int user){var values=new LinkedHashMap<String,JsonNode>();for(var item:defaults())values.put(item.path("name").asText(),item);for(var row:stories.db().queryForList("SELECT name,manifest_json FROM companion_skill WHERE user_id=? ORDER BY name",user))try{values.put((String)row.get("name"),json.readTree((String)row.get("manifest_json")));}catch(Exception e){throw new IllegalStateException("技能配置无法读取",e);}return new ArrayList<>(values.values());}
  public boolean enabled(int user,String name){return list(user).stream().anyMatch(s->s.path("name").asText().equals(name)&&s.path("enabled").asBoolean());}
  public void save(int user,JsonNode manifest){String name=manifest.path("name").asText();if(!name.matches("[a-zA-Z0-9_-]{1,80}"))throw new IllegalArgumentException("技能名称只能使用 1–80 位英文字母、数字、下划线或连字符；中文用途请填写在说明中");if(manifest.toString().length()>20000||manifest.path("instructions").asText().length()>12000)throw new IllegalArgumentException("技能配置不超过 20000 字，指令不超过 12000 字");if(list(user).size()>=50&&list(user).stream().noneMatch(s->name.equals(s.path("name").asText())))throw new IllegalArgumentException("最多启用 50 个技能");
    String mode=triggerMode(manifest);
    if(!Set.of("always","keywords","schedule","keywords_and_schedule").contains(mode))throw new IllegalArgumentException("技能触发模式不正确");
    if(Set.of("keywords","keywords_and_schedule").contains(mode)&&keywords(manifest).isEmpty())throw new IllegalArgumentException("关键词触发需要至少一个关键词");
    if(scheduled(manifest)&&(!manifest.has("schedule")||!manifest.path("schedule").isObject()))throw new IllegalArgumentException("定时触发需要时间配置");
    var schedule=manifest.path("schedule");
    String zone = schedule.path("zone").asText(DEFAULT_SCHEDULE_ZONE);
    int hour = schedule.path("hour").asInt(DEFAULT_SCHEDULE_HOUR);
    int minute = schedule.path("minute").asInt(DEFAULT_SCHEDULE_MINUTE);
    if(schedule.path("enabled").asBoolean()){
      try{java.time.ZoneId.of(zone);}catch(Exception e){throw new IllegalArgumentException("技能时区不正确");}
      String cron=schedule.path("cron").asText();if(!cron.isBlank()&&!org.springframework.scheduling.support.CronExpression.isValidExpression(cron))throw new IllegalArgumentException("技能 cron 表达式不正确（需要六段）");
      if(hour<0||hour>23||minute<0||minute>59)throw new IllegalArgumentException("技能执行时间不正确");
    }
    if(name.equals("ai-daily-juya")&&!schedule.isMissingNode()&&!schedule.isNull())companion.save(user,json.createObjectNode().put("dailyScheduleEnabled",schedule.path("enabled").asBoolean()).put("dailyTime",String.format("%02d:%02d",hour,minute)).put("dailyTimezone",zone));
    var copy=(ObjectNode)manifest.deepCopy();if(!copy.has("enabled"))copy.put("enabled",true);stories.db().update("INSERT INTO companion_skill(user_id,name,manifest_json) VALUES(?,?,?) ON CONFLICT(user_id,name) DO UPDATE SET manifest_json=excluded.manifest_json",user,name,copy.toString());if(name.equals("ai-daily-juya")&&!copy.path("enabled").asBoolean())companion.save(user,json.createObjectNode().put("dailyEnabled",false).put("dailyScheduleEnabled",false));}

  static List<String> keywords(JsonNode skill){var result=new ArrayList<String>();for(var k:skill.path("keywords"))if(!k.asText().isBlank())result.add(k.asText());return result;}
  static String triggerMode(JsonNode skill){
    if(skill.hasNonNull("triggerMode"))return skill.path("triggerMode").asText();
    boolean scheduled=skill.has("schedule")&&!skill.path("schedule").isNull();
    return scheduled?(keywords(skill).isEmpty()?"schedule":"keywords_and_schedule"):(keywords(skill).isEmpty()?"always":"keywords");
  }
  static boolean scheduled(JsonNode skill){return Set.of("schedule","keywords_and_schedule").contains(triggerMode(skill));}
  static boolean matches(JsonNode skill,String message,boolean proactive){
    if(!skill.path("enabled").asBoolean())return false;
    String mode=triggerMode(skill);if(mode.equals("always"))return true;
    return !proactive&&Set.of("keywords","keywords_and_schedule").contains(mode)&&keywords(skill).stream().anyMatch(message::contains);
  }
  public void delete(int user,String name){if(Set.of("ai-daily-juya","weather-lookup").contains(name)){var value=(ObjectNode)list(user).stream().filter(s->name.equals(s.path("name").asText())).findFirst().orElseThrow();value.put("enabled",false);save(user,value);}else stories.db().update("DELETE FROM companion_skill WHERE user_id=? AND name=?",user,name);}
  public List<Map<String,Object>> filterTools(int user,String message,List<Map<String,Object>> tools){return filterTools(user,message,tools,false,null);}
  public List<Map<String,Object>> filterTools(int user,String message,List<Map<String,Object>> tools,boolean proactive,String scheduledName){
    var controlled=new HashSet<String>();var allowed=new HashSet<String>();
    for(var skill:list(user)){
      boolean match=scheduledName==null?matches(skill,message,proactive):scheduledName.equals(skill.path("name").asText())&&skill.path("enabled").asBoolean()&&scheduled(skill)&&skill.path("schedule").path("enabled").asBoolean()&&skill.path("proactive").path("enabled").asBoolean();
      for(var name:skill.path("mcpTools")){controlled.add(name.asText());if(match)allowed.add(name.asText());}
    }
    return tools.stream().filter(t->!controlled.contains(t.get("name"))||allowed.contains(t.get("name"))).toList();
  }
  public boolean hasMatchingTask(int user,String message){
    return !matchingTasks(user,message).isEmpty();
  }
  public List<JsonNode> matchingTasks(int user,String message){return matchingTasks(user,message,false);}
  public List<JsonNode> matchingTasks(int user,String message,boolean proactive){
    var matched=new ArrayList<JsonNode>();
    for(var skill:list(user)){
      if(!skill.path("enabled").asBoolean()||Set.of("ai-daily-juya","weather-lookup").contains(skill.path("name").asText())||skill.path("instructions").asText().isBlank())continue;
      if(matches(skill,message,proactive)&&matched.size()<5)matched.add(skill);
    }
    return matched;
  }
  public String context(int user,String message)throws Exception {return context(user,message,false);}
  public String context(int user,String message,boolean proactive)throws Exception {
    var out=new StringBuilder();int count=0;boolean weather=false;
    for(var skill:list(user)){if(!skill.path("enabled").asBoolean())continue;String name=skill.path("name").asText();
      if(name.equals("ai-daily-juya"))continue;
      if(name.equals("weather-lookup")){weather=message.matches("(?s).*(天气|温度|农历|节气|节日|几号).*" );continue;}
      if(!matches(skill,message,proactive))continue;
      if(++count>5)break;out.append("\n【用户启用技能 ").append(name).append("】\n").append(PromptBudget.trim(skill.path("instructions").asText(),300));
    }
    // User-authored requirements precede optional environment data in the bounded prompt.
    if(weather)out.append("\n【当地环境资料】").append(PromptBudget.trim(companion.snapshot(user).toString(),120));
    return out.toString();
  }
}
