package org.example.aichat.release;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

@Service @DependsOn("releaseMigrations")
public class CompanionService {
  private final StoryStore stories;private final ObjectMapper json;private final CompanionWorld world;
  // Only explicit first-person preferences are collected. RP narration and inferred traits are not facts.
  private static final Pattern PREFERENCE=Pattern.compile("(?:^|[，。！？；,!?;\\n])\\s*我(?:一直|平时|特别|非常|很|最)?(不喜欢|不爱|讨厌|喜欢|爱好|爱)([^，。！？；,!?;\\n]{1,48})");
  private static final Pattern PRIVATE=Pattern.compile("[\\w.+-]+@[\\w.-]+|\\d{6,}|1[3-9]\\d{9}|地址|身份证|银行卡|病|药|诊断|收入|工资|存款|密码|账号|住在|电话");
  public CompanionService(StoryStore stories,ObjectMapper json,PublicContent content,RuntimeMode mode){this.stories=stories;this.json=json;this.world=new CompanionWorld(this,json,content,mode);}
  public ObjectNode settings(int user){
    var out=json.createObjectNode();out.put("timezone","Asia/Shanghai");out.put("worldEnabled",true);out.put("geolocationEnabled",false);out.put("worldNetworkEnabled",false);out.put("city","");out.put("locationSource","unset");out.put("weather","");out.put("autoCollect",true);out.put("dailyEnabled",false);out.put("dailyScheduleEnabled",false);out.put("dailyTime","10:00");out.put("dailyTimezone","");out.put("proactiveEnabled",true);out.put("proactiveIdleSeconds",3600);out.put("researchEnabled",false);out.put("searchEnabled",false);out.put("semanticInterests",true);out.put("proactivePrompt","自然延续上次话题，尊重当前场景，不要替用户行动。");
    var rows=stories.db().queryForList("SELECT settings_json FROM companion_setting WHERE user_id=?",user);
    if(!rows.isEmpty())try{out.setAll((ObjectNode)json.readTree((String)rows.get(0).get("settings_json")));}catch(Exception e){throw new IllegalStateException("陪伴设置无法读取",e);}
    return out;
  }
  public ObjectNode save(int user,JsonNode input){
    ObjectNode value=settings(user);
    for(String key:List.of("worldEnabled","geolocationEnabled","worldNetworkEnabled","autoCollect","dailyEnabled","dailyScheduleEnabled","proactiveEnabled","researchEnabled","searchEnabled","semanticInterests"))if(input.has(key)){
      if(!input.get(key).isBoolean())throw new IllegalArgumentException("设置开关格式不正确");value.put(key,input.get(key).asBoolean());
    }
    if(input.has("timezone")){String zone=input.path("timezone").asText();try{ZoneId.of(zone);}catch(Exception e){throw new IllegalArgumentException("时区不正确");}value.put("timezone",zone);}
    if(input.has("dailyTimezone")){String zone=input.path("dailyTimezone").asText();try{if(!zone.isBlank())ZoneId.of(zone);}catch(Exception e){throw new IllegalArgumentException("日报时区不正确");}value.put("dailyTimezone",zone);}
    if(input.has("city")){String city=input.path("city").asText().strip();if(city.length()>80)throw new IllegalArgumentException("城市名称不超过 80 字");value.put("city",city);}
    if(input.has("dailyTime")){String time=input.path("dailyTime").asText();if(!time.matches("(?:[01]\\d|2[0-3]):[0-5]\\d"))throw new IllegalArgumentException("日报时间格式应为 HH:mm");value.put("dailyTime",time);}
    if(input.has("proactiveIdleSeconds")){int seconds=input.path("proactiveIdleSeconds").asInt();if(seconds<10||seconds>86400)throw new IllegalArgumentException("主动搭话间隔为 10–86400 秒");value.put("proactiveIdleSeconds",seconds);}
    if(input.has("proactivePrompt")){String prompt=input.path("proactivePrompt").asText();if(prompt.length()>1000)throw new IllegalArgumentException("主动搭话指导不超过 1000 字");value.put("proactivePrompt",prompt);}
    persist(user,value);return value;
  }
  void persist(int user,ObjectNode value){stories.db().update("INSERT INTO companion_setting(user_id,settings_json) VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET settings_json=excluded.settings_json",user,value.toString());}
  public record WorldAnswer(String context,List<Map<String,Object>> sources){}
  public ObjectNode snapshot(int user) { return world.snapshot(user); }
  public ObjectNode locate(int user, JsonNode input, boolean ip) throws Exception { return world.locate(user, input, ip); }
  public ObjectNode manualLocation(int user, JsonNode input) { return world.manualLocation(user, input); }
  public void clearLocation(int user) { world.clearLocation(user); }
  public ObjectNode refreshWeather(int user) { return world.refreshWeather(user); }
  public String worldQuestionContext(int user, String message) { return worldAnswer(user, message).context(); }
  public WorldAnswer worldAnswer(int user, String message) { return world.worldAnswer(user, message); }
  public List<Map<String,Object>> interests(int user){return stories.db().queryForList("SELECT * FROM companion_interest WHERE user_id=? ORDER BY created_at DESC,id",user);}
  public Object add(int user,JsonNode input,String source){
    String topic=input.path("topic").asText().strip(),sentiment=input.path("sentiment").asText("like");
    if(topic.isBlank()||topic.length()>64)throw new IllegalArgumentException("喜好主题为 1–64 字");
    if(!Set.of("like","dislike").contains(sentiment))throw new IllegalArgumentException("喜好类型不正确");
    String id=UUID.randomUUID().toString();
    if("conversation".equals(source)){
      if(PRIVATE.matcher(topic).find())return null;
      // Manual edits and disabled topics always take precedence over automatic collection.
      stories.db().update("INSERT INTO companion_interest(id,user_id,topic,sentiment,source,created_at) VALUES(?,?,?,?,?,?) ON CONFLICT(user_id,topic) DO NOTHING",id,user,topic,sentiment,source,Instant.now().toString());
    }else{
      if(interests(user).size()>=200 && stories.db().queryForObject("SELECT COUNT(*) FROM companion_interest WHERE user_id=? AND topic=?",Integer.class,user,topic)==0)throw new IllegalArgumentException("最多保留 200 个喜好主题");
      stories.db().update("INSERT INTO companion_interest(id,user_id,topic,sentiment,source,created_at) VALUES(?,?,?,?,?,?) ON CONFLICT(user_id,topic) DO UPDATE SET sentiment=excluded.sentiment,enabled=TRUE,source=excluded.source",id,user,topic,sentiment,source,Instant.now().toString());
    }
    return interests(user);
  }
  public void updateInterest(int user,String id,JsonNode input){
    if(!input.path("enabled").isBoolean())throw new IllegalArgumentException("喜好开关格式不正确");
    if(stories.db().update("UPDATE companion_interest SET enabled=? WHERE user_id=? AND id=?",input.path("enabled").asBoolean(),user,id)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"喜好不存在");
  }
  public void deleteInterest(int user,String id){if(stories.db().update("DELETE FROM companion_interest WHERE user_id=? AND id=?",user,id)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"喜好不存在");}
  @org.springframework.transaction.annotation.Transactional
  public int restoreInterests(int user,JsonNode input){
    JsonNode rows=input.isArray()?input:input.has("companion_interests")?input.path("companion_interests"):input.path("proactive_interest");
    if(!rows.isArray() || rows.size()>200)throw new IllegalArgumentException("故事备份中的喜好列表格式不正确（最多 200 项）");
    int added=0;
    for(var row:rows){
      String topic=row.path("topic").asText().strip();if(topic.isBlank()||topic.length()>128)throw new IllegalArgumentException("导入的喜好主题为 1–128 字");
      String sentiment=row.path("sentiment").asText("like");if(!Set.of("like","dislike").contains(sentiment))throw new IllegalArgumentException("导入的喜好类型不正确");
      if(interests(user).size()>=200)break;
      added+=stories.db().update("INSERT INTO companion_interest(id,user_id,topic,sentiment,source,enabled,created_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(user_id,topic) DO NOTHING",UUID.randomUUID().toString(),user,topic,sentiment,"legacy",row.path("enabled").asBoolean(true),Instant.now().toString());
    }
    return added;
  }
  public void collect(int user,String conversation,String message){
    stories.owned(user,conversation);if(!settings(user).path("autoCollect").asBoolean() || !"companion".equals(stories.sessions().state(conversation).mode))return;
    if(message.contains("\"")||message.contains("“")||message.contains("「")||message.contains("假如")||message.contains("假设")||message.contains("扮演"))return;
    var match=PREFERENCE.matcher(message);
    while(match.find() && interests(user).size()<200){String topic=match.group(2).strip().replaceFirst("[啊呀呢吧]$","");if(topic.contains("但是")||topic.contains("而不是")||topic.matches(".*(什么|是否|吗|么|不一定|的话|才怪|开玩笑).*" )||topic.startsWith("的")||topic.startsWith("过"))continue;
      var item=json.createObjectNode().put("topic",topic).put("sentiment",Set.of("讨厌","不喜欢","不爱").contains(match.group(1))?"dislike":"like");add(user,item,"conversation");
    }
  }
  public int refreshInterests(int user){
    int before=interests(user).size();var rows=stories.db().queryForList("SELECT t.conversation_id,t.user_text,t.state_json FROM rp_turn t JOIN conversation c ON c.id=t.conversation_id WHERE c.user_id=? AND t.status='complete' ORDER BY t.id DESC LIMIT 100",user);
    for(var row:rows)if("companion".equals(stories.sessions().decode((String)row.get("state_json")).mode))collect(user,(String)row.get("conversation_id"),(String)row.get("user_text"));return interests(user).size()-before;
  }
  public String context(int user){
    var s=settings(user);var out=new StringBuilder();
    if(s.path("worldEnabled").asBoolean()){
      var world=snapshot(user);
      out.append("\n【现实环境：当前时钟与已保存天气】\n设备时钟是当前时间，不是缓存记录或天气观测时间。这里只提供记录时的天气观测，没有天气预报。不可编造未来下雨、预报或声称刚刚联网查询；位置和时间仅在相关时使用。\n")
        .append("城市：").append(world.path("location").asText()).append("\n当前设备时钟的当地时间：").append(world.path("localDateTime").asText()).append("（").append(world.path("timezone").asText()).append("）")
        .append("\n天气观测：").append(world.path("weather").asText().isBlank()?"未取得":world.path("weather").asText())
        .append("\n农历：").append(world.path("lunarDate").asText()).append("\n节气：").append(world.path("solarTerm").asText())
        .append("\n节日：").append(java.util.stream.StreamSupport.stream(world.path("festivals").spliterator(),false).map(JsonNode::asText).collect(java.util.stream.Collectors.joining("、")))
        .append("\n本段未联网刷新；本轮是否更新见查询行为。\n");
    }
    var enabled=interests(user).stream().filter(i->Boolean.TRUE.equals(i.get("enabled"))||Integer.valueOf(1).equals(i.get("enabled"))).limit(30).toList();
    if(!enabled.isEmpty()){out.append("\n【用户确认或明确表达的喜好，仅作背景资料】\n");for(var item:enabled)out.append("dislike".equals(item.get("sentiment"))?"不喜欢：":"喜欢：").append(item.get("topic")).append('\n');}
    return out.toString();
  }
}
