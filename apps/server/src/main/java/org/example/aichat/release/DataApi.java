package org.example.aichat.release;

import org.springframework.web.bind.annotation.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@RestController @RequestMapping("/api/v1/me/data")
public class DataApi {
  private final StoryStore stories;private final Attachments attachments;private final ObjectStore objects;private final Streams streams;private final RuntimeMode mode;private final TransactionTemplate tx;private final McpRegistry mcp;private final ProactiveService proactive;
  public DataApi(StoryStore stories,Attachments attachments,ObjectStore objects,Streams streams,RuntimeMode mode,PlatformTransactionManager manager,McpRegistry mcp,ProactiveService proactive){this.stories=stories;this.attachments=attachments;this.objects=objects;this.streams=streams;this.mode=mode;tx=new TransactionTemplate(manager);this.mcp=mcp;this.proactive=proactive;}
  @GetMapping public Object export()throws Exception {
    int user=SecurityConfiguration.current().id();var db=stories.db();var result=new LinkedHashMap<String,Object>();result.put("format","story-space-v1");result.put("exportedAt",java.time.Instant.now().toString());
    result.put("roles",db.queryForList("SELECT * FROM role_card WHERE owner_id=? OR id IN(SELECT role_id FROM conversation WHERE user_id=?)",user,user));
    result.put("conversations",db.queryForList("SELECT * FROM conversation WHERE user_id=?",user));
    for(String table:List.of("history","rp_session","rp_turn","rp_attachment","rp_audio_sentence","message_context"))result.put(table,db.queryForList("SELECT * FROM "+table+" WHERE conversation_id IN(SELECT id FROM conversation WHERE user_id=?)",user));
    result.put("rp_world",db.queryForList("SELECT * FROM rp_world WHERE user_id=?",user));
    result.put("character_profile",db.queryForList("SELECT * FROM character_profile WHERE role_id IN(SELECT id FROM role_card WHERE owner_id=? OR id IN(SELECT role_id FROM conversation WHERE user_id=?))",user,user));
    result.put("memory_feedback",db.queryForList("SELECT * FROM memory_feedback WHERE world_id IN(SELECT id FROM rp_world WHERE user_id=?)",user));
    result.put("companion_skill",db.queryForList("SELECT * FROM companion_skill WHERE user_id=?",user));
    result.put("proactive_candidate",db.queryForList("SELECT * FROM proactive_candidate WHERE user_id=?",user));
    result.put("companion_interests",db.queryForList("SELECT * FROM companion_interest WHERE user_id=?",user));
    result.put("companion_settings",db.queryForList("SELECT settings_json FROM companion_setting WHERE user_id=?",user));
    var files=new ArrayList<Map<String,Object>>();for(var row:db.queryForList("SELECT * FROM attachment WHERE user_id=?",user)){String id=(String)row.get("id");files.add(Map.of("id",id,"dataUrl","data:"+row.get("media_type")+";base64,"+Base64.getEncoder().encodeToString(objects.get(user+"/"+id))));}result.put("attachments",files);
    return result;
  }
  @DeleteMapping public void delete()throws Exception {
    var who=SecurityConfiguration.current();if(who.admin())throw new IllegalArgumentException("管理员账号不能在此删除");int user=who.id();streams.stopUser(user,CancelCause.ACCOUNT_DELETED);proactive.stopUser(user);mcp.stopUser(user);
    for(var row:stories.db().queryForList("SELECT id FROM conversation WHERE user_id=?",user)){String conv=(String)row.get("id");streams.mutate(stories.sessions().worldId(conv),()->{stories.delete(user,conv);return null;});}
    // Objects are removed first; a failed storage operation leaves a retryable account.
    attachments.deleteUser(user);
    tx.execute(s->{for(String table:List.of("proactive_candidate","companion_skill","companion_mcp","companion_interest","companion_daily","companion_setting"))stories.db().update("DELETE FROM "+table+" WHERE user_id=?",user);stories.db().update("DELETE FROM model_connection WHERE user_id=?",user);stories.db().update("DELETE FROM daily_usage WHERE user_id=?",user);stories.db().update("DELETE FROM character_profile WHERE role_id IN(SELECT id FROM role_card WHERE owner_id=?)",user);stories.db().update("DELETE FROM role_card WHERE owner_id=?",user);
      if(mode.cloud()){stories.db().update("DELETE FROM fault WHERE user_id=?",user);stories.db().update("DELETE FROM auth_session WHERE user_id=?",user);stories.db().update("DELETE FROM account WHERE id=?",user);}return null;});
  }
}
