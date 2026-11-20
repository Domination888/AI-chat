package org.example.aichat.release;

import org.example.aichat.rp.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@RestController
public class StoryApi {
  private final StoryStore stories;private final Streams streams;private final Attachments attachments;private final ModelConnections connections;private final OpenAiTransport models;private final RuntimeMode mode;private final VoiceGateway voice;
  public StoryApi(StoryStore stories,Streams streams,Attachments attachments,ModelConnections connections,OpenAiTransport models,RuntimeMode mode,VoiceGateway voice){this.stories=stories;this.streams=streams;this.attachments=attachments;this.connections=connections;this.models=models;this.mode=mode;this.voice=voice;}
  private int user(){return SecurityConfiguration.current().id();}
  private String world(String conversation){stories.owned(user(),conversation);return stories.sessions().worldId(conversation);}
  private Map<String,Object> camel(Map<String,Object> row){var out=new LinkedHashMap<String,Object>();row.forEach((k,v)->{String[] parts=k.split("_");var b=new StringBuilder(parts[0]);for(int i=1;i<parts.length;i++)b.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));out.put(b.toString(),v);});return out;}
  @GetMapping("/api/health") public Object health(){return Map.of("status","ready","mode",mode.cloud()?"cloud":"offline","schema",3,"billing",false);}
  @GetMapping("/api/v1/me") public Object me(){return Map.of("user",SecurityConfiguration.current(),"mode",mode.cloud()?"cloud":"offline","usage",stories.db().queryForList("SELECT * FROM daily_usage WHERE user_id=? ORDER BY day DESC LIMIT 7",user()));}
  @GetMapping("/api/roles") public Object roles(){return stories.db().queryForList("SELECT * FROM role_card WHERE owner_id=? OR owner_id IS NULL ORDER BY id",user()).stream().map(this::camel).toList();}
  public record Role(String name,String profile,String background,String personality,String greeting){}
  private void validateRole(Role r){if(r.name()==null || r.name().isBlank() || r.name().length()>40 || Objects.toString(r.background(),"").length()>4000 || Objects.toString(r.personality(),"").length()>2000 || Objects.toString(r.greeting(),"").length()>2000 || Objects.toString(r.profile(),"").length()>500)throw new IllegalArgumentException("角色内容超过限制或缺少名称");}
  @PostMapping("/api/roles") public void addRole(@RequestBody Role r){validateRole(r);stories.db().update("INSERT INTO role_card(owner_id,name,profile,background,personality,greeting,avatar,role_code) VALUES(?,?,?,?,?,?,?,?)",user(),r.name(),Objects.toString(r.profile(),""),Objects.toString(r.background(),""),Objects.toString(r.personality(),""),Objects.toString(r.greeting(),""),"/release-art/voyager-character.png","voyager");}
  @PutMapping("/api/roles/{id}") public void editRole(@PathVariable int id,@RequestBody Role r){validateRole(r);if(stories.db().update("UPDATE role_card SET name=?,profile=?,background=?,personality=?,greeting=?,updated_at=? WHERE id=? AND owner_id=?",r.name(),Objects.toString(r.profile(),""),Objects.toString(r.background(),""),Objects.toString(r.personality(),""),Objects.toString(r.greeting(),""),java.time.Instant.now().toString(),id,user())==0)throw new IllegalArgumentException("只能修改自己创建的角色");}
  @DeleteMapping("/api/roles/{id}") public void deleteRole(@PathVariable int id){stories.role(user(),id);if(stories.db().queryForObject("SELECT COUNT(*) FROM conversation WHERE role_id=? AND user_id=?",Integer.class,id,user())>0)throw new IllegalArgumentException("请先删除该角色的故事");if(stories.db().update("DELETE FROM role_card WHERE id=? AND owner_id=?",id,user())==0)throw new IllegalArgumentException("示例角色不能删除");}
  @GetMapping("/api/v1/conversations") public Object conversations(@RequestParam(required=false) Integer roleId){return roleId==null?stories.db().queryForList("SELECT * FROM conversation WHERE user_id=? ORDER BY created_at DESC",user()):stories.db().queryForList("SELECT * FROM conversation WHERE user_id=? AND role_id=? ORDER BY created_at DESC",user(),roleId);}
  @GetMapping("/api/conversation/{id}/history") public Object history(@PathVariable String id,@RequestParam(defaultValue="80") int limit,@RequestParam(required=false) Long before,@RequestParam(defaultValue="") String search){return stories.history(user(),id,limit,before,search);}
  @DeleteMapping("/api/conversation/{id}") public void delete(@PathVariable String id){int u=user();streams.mutate(world(id),()->{stories.delete(u,id);attachments.collectUnused(u);return null;});}
  public record NewSession(String conversationId,int roleId,String continueWorld,RpState state){}
  @PostMapping("/api/rp/sessions") public Object create(@RequestBody NewSession r){stories.create(user(),r.conversationId(),r.roleId(),r.continueWorld(),r.state());return stories.sessions().view(r.conversationId());}
  @GetMapping("/api/rp/{id}") public Object state(@PathVariable String id){world(id);return stories.sessions().view(id);}
  @PutMapping("/api/rp/{id}") public Object state(@PathVariable String id,@RequestBody RpState state){return streams.mutate(world(id),()->{state.legacyMemory=false;stories.sessions().editScene(id,state);return stories.sessions().view(id);});}
  @PostMapping("/api/rp/{id}/rewind/{message}") public void rewind(@PathVariable String id,@PathVariable long message){streams.mutate(world(id),()->{stories.sessions().rewind(id,message);stories.reconcile();attachments.refreshLinks(user(),stories.sessions().chapters(id));return null;});}
  @PostMapping("/api/rp/{id}/branch/{message}") public Object branch(@PathVariable String id,@PathVariable long message){return streams.mutate(world(id),()->Map.of("conversationId",stories.branch(id,message)));}
  @GetMapping("/api/rp/{id}/album") public Object album(@PathVariable String id){world(id);var rows=stories.sessions().album(id);for(var row:rows)row.put("images_json",attachments.display(user(),(String)row.get("images_json")));return rows;}
  @PutMapping("/api/rp/{id}/bookmark/{turn}") public void bookmark(@PathVariable String id,@PathVariable long turn,@RequestBody Map<String,Boolean> r){world(id);stories.sessions().bookmark(id,turn,Boolean.TRUE.equals(r.get("value")));}
  @PostMapping("/api/rp/{id}/greeting") public Object greeting(@PathVariable String id){int u=user();return streams.mutate(world(id),()->{
    if(stories.db().queryForObject("SELECT COUNT(*) FROM history WHERE conversation_id=?",Integer.class,id)>0)return Map.of("text","");
    var r=stories.role(u,((Number)stories.owned(u,id).get("role_id")).intValue());String text=Objects.toString(r.get("greeting"),"");if(text.isBlank())text="（对方抬起眼，等你开口。）";
    long mid=stories.message(id,"assistant",text);stories.finish(id,mid,"",text,"complete",List.of(),"");return Map.of("text",text);
  });}
  @GetMapping("/api/rp/{id}/memory-delivery") public Object delivery(@PathVariable String id){world(id);var result=new LinkedHashMap<String,Object>();result.put("enabled",true);for(var row:stories.db().queryForList("SELECT q.status,COUNT(*) AS count FROM index_queue q JOIN rp_turn t ON t.history_id=q.history_id WHERE t.conversation_id=? GROUP BY q.status",id))result.put((String)row.get("status"),row.get("count"));return result;}
  @PostMapping("/api/rp/{id}/memory-retry") public void retry(@PathVariable String id){world(id);stories.db().update("UPDATE index_queue SET status='pending',attempts=0,available_at=? WHERE history_id IN(SELECT history_id FROM rp_turn WHERE conversation_id=?) AND status='failed'",java.time.Instant.now().toString(),id);}
  @PostMapping("/api/rp/{id}/heard") public void heard(@PathVariable String id){world(id);}
  @GetMapping("/api/v1/me/model-connection") public Object model(){return connections.metadata(user());}
  public record Model(String baseUrl,String model,String apiKey,boolean allowNetwork){}
  @PutMapping("/api/v1/me/model-connection") public void model(@RequestBody Model r){connections.save(user(),r.baseUrl(),r.model(),r.apiKey(),r.allowNetwork());}
  @DeleteMapping("/api/v1/me/model-connection") public void resetModel(){connections.delete(user());}
  @PostMapping("/api/v1/me/model-connection/test") public Object testModel()throws Exception{models.test(connections.forUser(user()));return Map.of("success",true);}
  @GetMapping("/api/attachments/{id}/content") public ResponseEntity<byte[]> content(@PathVariable String id,@RequestParam int user,@RequestParam long expires,@RequestParam String signature)throws Exception{var r=attachments.content(user,id,expires,signature);return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)r.get("type"))).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body((byte[])r.get("bytes"));}
  @GetMapping("/api/v1/voice/avatars") public Object voices()throws Exception{return voice.status();}
  @PostMapping("/api/v1/voice/speech") public ResponseEntity<byte[]> speech(@RequestBody Map<String,String> r)throws Exception{try{return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(voice.speech(r.get("text"),r.containsKey("roleId")?Objects.toString(stories.role(user(),Integer.parseInt(r.get("roleId"))).get("voice_id"),"shu") :"shu"));}catch(java.io.IOException e){throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,mode.cloud()?"朗读服务暂不可用，请稍后重试或联系管理员；文字对话可继续使用":"朗读服务未就绪，请启动本机 AstraTTS；使用离线语音包时请检查语音服务。文字对话可继续使用",e);}}
}
