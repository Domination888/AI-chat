package org.example.aichat.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.time.Instant;

/** Import only into a fresh local database; an HTTP handler is never present in the cloud jar. */
@RestController @ConditionalOnProperty(name="story.mode",havingValue="offline")
public class OfflineImport {
  private final StoryStore stories;private final Attachments attachments;private final ObjectMapper json;private final RuntimeMode mode;private final CompanionService companion;
  public OfflineImport(StoryStore stories,Attachments attachments,ObjectMapper json,RuntimeMode mode,CompanionService companion){this.stories=stories;this.attachments=attachments;this.json=json;this.mode=mode;this.companion=companion;}
  @PostMapping("/api/v1/offline/import") @Transactional public Object load(@RequestBody JsonNode input)throws Exception {
    if(!Set.of("legacy-ai-chat-v1","story-space-v1").contains(input.path("format").asText()))throw new IllegalArgumentException("导入文件格式不支持");
    if(stories.db().queryForObject("SELECT COUNT(*) FROM conversation",Integer.class)>0)throw new IllegalArgumentException("请在新的本地数据目录中导入，避免覆盖已有故事");
    var roleMap=new HashMap<Integer,Long>();var reusedPrivate=new HashSet<Long>();
    var importedProfiles=new HashMap<Integer,JsonNode>();
    for(var profile:input.path("character_profile")){
      String raw=profile.path("profile_json").asText();if(raw.length()>2*1024*1024)throw new IllegalArgumentException("角色资料过大");
      importedProfiles.put(profile.path("role_id").asInt(),json.readTree(raw));
    }
    // The fresh-data rule permits starter/private character packs but no existing stories.
    var existingPrivate=stories.db().queryForList("SELECT r.*,p.profile_json AS character_profile_json FROM role_card r LEFT JOIN character_profile p ON p.role_id=r.id WHERE r.owner_id=1 ORDER BY r.id");
    roles:for(var r:input.path("roles")){
      // Public starters stay read-only; identical private packs keep their local ownership.
      var builtin=r.path("owner_id").isNull() && "story-space-v1".equals(input.path("format").asText())
        ? stories.db().queryForList("SELECT id FROM role_card WHERE owner_id IS NULL AND name=? AND avatar=? AND profile=? AND background=? AND personality=? AND greeting=?",r.path("name").asText(),r.path("avatar").asText(),r.path("profile").asText(),r.path("background").asText(),r.path("personality").asText(),r.path("greeting").asText()) : List.<Map<String,Object>>of();
      if(!builtin.isEmpty()){roleMap.put(r.path("id").asInt(),((Number)builtin.get(0).get("id")).longValue());continue;}
      if("story-space-v1".equals(input.path("format").asText())&&!r.path("owner_id").isNull())for(var local:existingPrivate){
        long id=((Number)local.get("id")).longValue();
        if(!reusedPrivate.contains(id)&&samePrivateRole(local,r,importedProfiles.get(r.path("id").asInt()))){
          roleMap.put(r.path("id").asInt(),id);reusedPrivate.add(id);continue roles;
        }
      }
      stories.db().update("INSERT INTO role_card(owner_id,name,profile,background,personality,greeting,example_dialogue,voice_id,role_code,avatar) VALUES(1,?,?,?,?,?,?,?,?,?)",r.path("name").asText("导入角色"),r.path("profile").asText(""),r.path("background").asText(""),r.path("personality").asText(""),r.path("greeting").asText(""),r.path("example_dialogue").asText(""),r.path("voice_id").asText(""),r.path("role_code").asText("voyager"),r.path("avatar").asText().startsWith("/release-art/")?r.path("avatar").asText():"");
      roleMap.put(r.path("id").asInt(),stories.db().queryForObject("SELECT MAX(id) FROM role_card",Long.class));
    }
    var conversations=new HashSet<String>();
    for(var c:input.path("conversations")){
      String id=c.path("id").asText();if(id.isBlank()||id.length()>64)throw new IllegalArgumentException("导入会话标识不合法");Long role=roleMap.get(c.path("role_id").asInt());if(role==null)throw new IllegalArgumentException("导入文件缺少角色");
      stories.db().update("INSERT INTO conversation(id,user_id,role_id,title,created_at) VALUES(?,1,?,?,?)",id,role,c.path("title").asText("导入故事"),c.path("created_at").asText(Instant.now().toString()));conversations.add(id);
    }
    var worlds=new HashSet<String>();for(var w:input.path("rp_world")){
      Long role=roleMap.get(w.path("role_id").asInt());if(role==null)throw new IllegalArgumentException("世界缺少角色");String id=w.path("id").asText();stories.sessions().decode(w.path("state_json").asText());stories.sessions().decode(w.path("initial_json").asText());
      stories.db().update("INSERT INTO rp_world VALUES(?,1,?,?,?)",id,role,w.path("state_json").asText(),w.path("initial_json").asText());worlds.add(id);
    }
    for(var s:input.path("rp_session")){require(conversations,s.path("conversation_id").asText());require(worlds,s.path("world_id").asText());stories.db().update("INSERT INTO rp_session VALUES(?,?)",s.path("conversation_id").asText(),s.path("world_id").asText());}
    for(var h:input.path("history")){require(conversations,h.path("conversation_id").asText());stories.db().update("INSERT INTO history(id,conversation_id,sender,content,created_at) VALUES(?,?,?,?,?)",h.path("id").asLong(),h.path("conversation_id").asText(),h.path("sender").asText(),h.path("content").asText(),h.path("created_at").asText(Instant.now().toString()));}
    var exportedFiles=new HashMap<String,String>();for(var f:input.path("attachments"))exportedFiles.put("attachment:"+f.path("id").asText(),f.path("dataUrl").asText());
    var restoredFiles=new HashMap<String,String>();
    for(var a:input.path("rp_attachment")){String conv=a.path("conversation_id").asText();require(conversations,conv);var refs=restoreReferences(a.path("images_json").asText("[]"),exportedFiles,restoredFiles,conv);stories.db().update("INSERT INTO rp_attachment VALUES(?,?,?)",a.path("history_id").asLong(),conv,json.writeValueAsString(refs));}
    for(var t:input.path("rp_turn")){
      String conv=t.path("conversation_id").asText(),world=t.path("world_id").asText();require(conversations,conv);require(worlds,world);
      var refs=restoreReferences(t.path("images_json").asText("[]"),exportedFiles,restoredFiles,conv);
      stories.db().update("INSERT INTO rp_turn(id,world_id,conversation_id,history_id,state_json,user_text,assistant_text,status,images_json,bookmarked,created_at,before_state_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",t.path("id").asLong(),world,conv,t.path("history_id").asLong(),t.path("state_json").asText(),t.path("user_text").asText(),t.path("assistant_text").asText(),t.path("status").asText("complete"),json.writeValueAsString(refs),t.path("bookmarked").asBoolean(),t.path("created_at").asText(Instant.now().toString()),t.path("before_state_json").isTextual()?t.path("before_state_json").asText():null);
    }
    for(var a:input.path("rp_audio_sentence")){
      require(conversations,a.path("conversation_id").asText());
      stories.db().update("INSERT INTO rp_audio_sentence(stream_id,conversation_id,idx,text,heard,created_at) VALUES(?,?,?,?,?,?)",a.path("stream_id").asText(),a.path("conversation_id").asText(),a.path("idx").asInt(),a.path("text").asText(),a.path("heard").asBoolean(),a.path("created_at").asText(Instant.now().toString()));
    }

    for(var profile:input.path("character_profile")){Long role=roleMap.get(profile.path("role_id").asInt());if(role==null)throw new IllegalArgumentException("角色资料缺少对应角色");if(reusedPrivate.contains(role))continue;var raw=profile.path("profile_json").asText();json.readTree(raw);if(raw.length()>2*1024*1024)throw new IllegalArgumentException("角色资料过大");stories.db().update("INSERT INTO character_profile(role_id,profile_json) VALUES(?,?)",role,raw);}
    for(var context:input.path("message_context")){require(conversations,context.path("conversation_id").asText());long id=context.path("history_id").asLong();if(stories.db().queryForObject("SELECT COUNT(*) FROM history WHERE id=? AND conversation_id=?",Integer.class,id,context.path("conversation_id").asText())==0)throw new IllegalArgumentException("消息来源缺少对应历史");json.readTree(context.path("context_json").asText("{}"));stories.db().update("INSERT INTO message_context VALUES(?,?,?)",id,context.path("conversation_id").asText(),context.path("context_json").asText("{}"));}
    for(var f:input.path("memory_feedback")){require(worlds,f.path("world_id").asText());stories.db().update("INSERT INTO memory_feedback VALUES(?,?,?,?)",f.path("history_id").asLong(),f.path("world_id").asText(),f.path("correction").asText(),f.path("hidden").asBoolean());}
    for(var skill:input.path("companion_skill")){String raw=skill.path("manifest_json").asText();var manifest=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(raw);manifest.remove("schedule");stories.db().update("INSERT INTO companion_skill VALUES(1,?,?)",skill.path("name").asText(),manifest.toString());}
    for(var c:input.path("proactive_candidate")){require(conversations,c.path("conversation_id").asText());stories.db().update("INSERT INTO proactive_candidate(id,user_id,conversation_id,topic,title,summary,sources_json,fingerprint,status,feedback,history_id,created_at) VALUES(?,1,?,?,?,?,?,?,?,?,?,?)",c.path("id").asText(),c.path("conversation_id").asText(),c.path("topic").asText(),c.path("title").asText(),c.path("summary").asText(),c.path("sources_json").asText("[]"),c.path("fingerprint").asText(),c.path("status").asText().equals("delivered")?"delivered":"archived",c.path("feedback").asText(),c.path("history_id").isNumber()?c.path("history_id").asLong():null,c.path("created_at").asText());}
    for(String conv:conversations){
      stories.sessions().view(conv);
      {
        String userText="";List<String> refs=List.of();
        for(var row:stories.db().queryForList("SELECT h.id,h.sender,h.content,COALESCE(a.images_json,'[]') AS images_json FROM history h LEFT JOIN rp_attachment a ON a.history_id=h.id WHERE h.conversation_id=? ORDER BY h.id",conv)){
          if("user".equals(row.get("sender"))){userText=(String)row.get("content");refs=attachments.readRefs((String)row.get("images_json"));}
          else if("assistant".equals(row.get("sender")) && stories.db().queryForObject("SELECT COUNT(*) FROM rp_turn WHERE history_id=? AND conversation_id=?",Integer.class,row.get("id"),conv)==0)stories.sessions().record(conv,((Number)row.get("id")).longValue(),userText,(String)row.get("content"),"complete",refs,"");
        }
      }
      worlds.add(stories.sessions().worldId(conv));
    }
    for(String world:worlds)stories.queueWorld(world);
    if(input.has("companion_interests") || input.has("proactive_interest"))companion.restoreInterests(1,input);
    if(input.path("companion_settings").isArray() && !input.path("companion_settings").isEmpty()){
      var settings=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(input.path("companion_settings").get(0).path("settings_json").asText("{}"));
      // Imports preserve preferences without silently enabling device access or network jobs.
      settings.put("proactiveEnabled",false);settings.put("researchEnabled",false);settings.put("searchEnabled",false);settings.put("semanticInterests",false);settings.put("geolocationEnabled",false);settings.put("worldNetworkEnabled",false);settings.put("dailyEnabled",false);settings.put("dailyScheduleEnabled",false);
      companion.save(1,settings);
      if(settings.has("city"))companion.manualLocation(1,settings);
    }

    // Unscoped MemOS facts are preserved for review, never injected into an arbitrary world.
    if(input.has("legacy_memories"))java.nio.file.Files.writeString(mode.data().resolve("legacy-memories-review.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(input.get("legacy_memories")));
    return Map.of("conversations",conversations.size(),"roles",roleMap.size(),"worlds",worlds.size(),"embeddings","queued");
  }
  private boolean samePrivateRole(Map<String,Object> local,JsonNode incoming,JsonNode profile)throws Exception {
    for(String field:List.of("name","profile","background","personality","greeting","example_dialogue","voice_id","role_code","avatar")){
      String value=incoming.path(field).asText(field.equals("name")?"导入角色":field.equals("role_code")?"voyager":"");
      if(!Objects.toString(local.get(field),"").equals(value))return false;
    }
    String existing=(String)local.get("character_profile_json");
    return Objects.equals(existing==null?null:json.readTree(existing),profile);
  }
  private void require(Set<String> allowed,String value){if(!allowed.contains(value))throw new IllegalArgumentException("导入文件引用了不存在的记录");}
  private List<String> restoreReferences(String encoded,Map<String,String> files,Map<String,String> restored,String conversation)throws Exception {
    var source=attachments.readRefs(encoded);if(source.size()>5)throw new IllegalArgumentException("最多导入 5 张图片到一条消息");
    var result=new ArrayList<String>();
    for(String ref:source){
      String value=ref;
      if(ref.startsWith("attachment:")){
        value=restored.get(ref);
        if(value==null){value=files.get(ref);if(value==null)throw new IllegalArgumentException("备份缺少照片原始文件");}
      }
      // One source object can appear in user attachments, assistant turns, and
      // several branches. Reuse its new local reference and link every chapter.
      String local=attachments.store(1,conversation,List.of(value)).get(0);
      if(ref.startsWith("attachment:"))restored.put(ref,local);
      result.add(local);
    }
    return result;
  }
}
