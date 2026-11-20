package org.example.aichat.release;

import org.example.aichat.rp.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service @DependsOn("releaseMigrations")
public class StoryStore {
  final JdbcTemplate db;final RpSessionService sessions;private final Attachments attachments;private final ObjectMapper json;
  public StoryStore(JdbcTemplate db,RpSessionService sessions,Attachments attachments,ObjectMapper json){this.db=db;this.sessions=sessions;this.attachments=attachments;this.json=json;}
  public JdbcTemplate db(){return db;}
  public RpSessionService sessions(){return sessions;}
  public Map<String,Object> owned(int user,String conversation){
    var rows=db.queryForList("SELECT * FROM conversation WHERE id=? AND user_id=?",conversation,user);
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"故事不存在");return rows.get(0);
  }
  public Map<String,Object> role(int user,int role){
    var rows=db.queryForList("SELECT * FROM role_card WHERE id=? AND (owner_id=? OR owner_id IS NULL)",role,user);
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"角色不存在");return rows.get(0);
  }
  @Transactional public void create(int user,String conversation,int role,String world,RpState state){
    if(conversation==null || !conversation.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException("会话标识不合法");role(user,role);
    if(db.queryForObject("SELECT COUNT(*) FROM conversation WHERE id=?",Integer.class,conversation)>0){owned(user,conversation);return;}
    if(state==null){state=new RpState();state.relationship="初次相遇；关系由故事决定";}state.legacyMemory=false;sessions.create(conversation,user,role,world,state);
  }
  public long message(String conv,String sender,String text){
    var holder=new GeneratedKeyHolder();db.update(c->{var p=c.prepareStatement("INSERT INTO history(conversation_id,sender,content) VALUES(?,?,?)",new String[]{"id"});p.setString(1,conv);p.setString(2,sender);p.setString(3,text);return p;},holder);
    return Objects.requireNonNull(holder.getKey()).longValue();
  }
  public List<Map<String,Object>> history(int user,String conversation,int limit,Long before,String search){
    owned(user,conversation);var rows=db.queryForList("SELECT h.*,t.id AS turn_id,t.status,t.bookmarked,COALESCE(a.images_json,'[]') AS images_json,COALESCE(mc.context_json,'{}') AS context_json FROM history h LEFT JOIN rp_turn t ON t.history_id=h.id AND t.conversation_id=h.conversation_id AND t.id=(SELECT MAX(snapshot.id) FROM rp_turn snapshot WHERE snapshot.history_id=h.id AND snapshot.conversation_id=h.conversation_id) LEFT JOIN rp_attachment a ON a.history_id=h.id LEFT JOIN message_context mc ON mc.history_id=h.id WHERE h.conversation_id=? AND h.id<? AND h.content LIKE ? ORDER BY h.id DESC LIMIT ?",conversation,before==null?Long.MAX_VALUE:before,"%"+Objects.toString(search,"")+"%",Math.max(1,Math.min(limit,200)));
    Collections.reverse(rows);for(var row:rows){row.put("createdAt",row.get("created_at"));row.put("images_json",attachments.display(user,(String)row.get("images_json")));}return rows;
  }
  @Transactional public void reconcile(){
    db.update("DELETE FROM memory_entry WHERE NOT EXISTS(SELECT 1 FROM history h WHERE h.id=memory_entry.history_id)");
    db.update("DELETE FROM message_context WHERE NOT EXISTS(SELECT 1 FROM history h WHERE h.id=message_context.history_id)");
    db.update("DELETE FROM memory_feedback WHERE NOT EXISTS(SELECT 1 FROM history h WHERE h.id=memory_feedback.history_id)");
    db.update("DELETE FROM index_queue WHERE NOT EXISTS(SELECT 1 FROM history h WHERE h.id=index_queue.history_id)");
  }
  @Transactional public String branch(String source,long through){String target=sessions.branch(source,through);copyBranchContext(source,target,through);db.update("INSERT INTO attachment_link SELECT ?,attachment_id FROM attachment_link WHERE conversation_id=? ON CONFLICT DO NOTHING",target,source);queueWorld(sessions.worldId(target));return target;}
  @Transactional public void copyBranchContext(String source,String target,long through){
    var original=db.queryForList("SELECT h.id,mc.context_json,f.correction,f.hidden FROM history h LEFT JOIN message_context mc ON mc.history_id=h.id LEFT JOIN memory_feedback f ON f.history_id=h.id WHERE h.conversation_id=? AND h.id<=? ORDER BY h.id",source,through);
    var copied=db.queryForList("SELECT id FROM history WHERE conversation_id=? ORDER BY id",target);if(original.size()!=copied.size())throw new IllegalStateException("分支历史数量不一致");
    var historyMap=new HashMap<Long,Long>();for(int i=0;i<original.size();i++)historyMap.put(((Number)original.get(i).get("id")).longValue(),((Number)copied.get(i).get("id")).longValue());
    for(int i=0;i<original.size();i++){var row=original.get(i);long id=((Number)copied.get(i).get("id")).longValue();
      if(row.get("context_json")!=null)try{var context=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree((String)row.get("context_json"));context.remove("candidateId");if(context.has("continuationHistoryId")){Long mapped=historyMap.get(context.path("continuationHistoryId").asLong());if(mapped==null)context.remove("continuationHistoryId");else context.put("continuationHistoryId",mapped);}db.update("INSERT INTO message_context VALUES(?,?,?)",id,target,context.toString());}catch(java.io.IOException e){throw new IllegalStateException("分支消息来源无法读取",e);}
      if(row.get("correction")!=null)db.update("INSERT INTO memory_feedback VALUES(?,?,?,?)",id,sessions.worldId(target),row.get("correction"),row.get("hidden"));
    }
  }
  @Transactional public void delete(int user,String conversation){
    owned(user,conversation);String world=sessions.worldId(conversation);
    for(String t:List.of("proactive_candidate","message_context","rp_attachment","rp_audio_sentence","rp_turn","memory_outbox","history","rp_session","attachment_link"))db.update("DELETE FROM "+t+" WHERE conversation_id=?",conversation);
    db.update("DELETE FROM conversation WHERE id=? AND user_id=?",conversation,user);
    if(world!=null)db.update("DELETE FROM rp_world WHERE id=? AND NOT EXISTS(SELECT 1 FROM rp_session WHERE world_id=?)",world,world);
    reconcile();
  }
  @Transactional public void finish(String conv,long assistantId,String user,String text,String status,List<String> refs,String state){
    db.update("UPDATE history SET content=? WHERE id=? AND conversation_id=?",text,assistantId,conv);
    sessions.record(conv,assistantId,user,text,status,refs,state);
    db.update("INSERT INTO index_queue(history_id,available_at) VALUES(?,?) ON CONFLICT DO NOTHING",assistantId,Instant.now().toString());
  }
  @Transactional public void queueWorld(String world){
    db.update("INSERT INTO index_queue(history_id,available_at) SELECT history_id,? FROM rp_turn WHERE world_id=? ON CONFLICT DO NOTHING",Instant.now().toString(),world);
  }
}
