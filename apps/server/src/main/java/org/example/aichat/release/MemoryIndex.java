package org.example.aichat.release;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import java.nio.*;
import java.time.Instant;
import java.util.*;

@Service @DependsOn("releaseMigrations")
public class MemoryIndex {
  private final JdbcTemplate db;private final RuntimeMode mode;private final OpenAiTransport models;
  public MemoryIndex(JdbcTemplate db,RuntimeMode mode,OpenAiTransport models){this.db=db;this.mode=mode;this.models=models;}
  public static double cosine(float[] a,float[] b){if(a.length!=b.length)return -1;double dot=0,x=0,y=0;for(int i=0;i<a.length;i++){dot+=a[i]*b[i];x+=a[i]*a[i];y+=b[i]*b[i];}return x==0||y==0?0:dot/Math.sqrt(x*y);}
  static byte[] encode(float[] v){var b=ByteBuffer.allocate(v.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float f:v)b.putFloat(f);return b.array();}
  static float[] decode(byte[] v){var b=ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);float[] out=new float[v.length/4];for(int i=0;i<out.length;i++)out[i]=b.getFloat();return out;}
  static String vector(float[] v){return Arrays.toString(v);}
  @Scheduled(fixedDelay=3000) public void drain(){
    for(var task:db.queryForList("SELECT history_id,attempts FROM index_queue WHERE status='pending' AND available_at<=? ORDER BY history_id LIMIT 4",Instant.now().toString())){
      long id=((Number)task.get("history_id")).longValue();int attempts=((Number)task.get("attempts")).intValue();
      try {
        var rows=db.queryForList("SELECT world_id,user_text,assistant_text FROM rp_turn WHERE history_id=? AND status='complete' ORDER BY id DESC LIMIT 1",id);
        if(rows.isEmpty()){db.update("DELETE FROM index_queue WHERE history_id=?",id);continue;}
        var row=rows.get(0);String world=row.get("world_id").toString();
        if(Objects.toString(row.get("user_text"),"").isBlank()||ScheduledNoticeContext.ids(db,world).contains(id)||MissingPastAnswer.unavailableIds(db,world).contains(id)){db.update("UPDATE index_queue SET status='saved' WHERE history_id=?",id);continue;}String text="用户："+row.get("user_text")+"\n角色："+row.get("assistant_text");
        var feedback=db.queryForList("SELECT correction,hidden FROM memory_feedback WHERE history_id=? AND world_id=?",id,row.get("world_id"));
        if(!feedback.isEmpty()){var f=feedback.get(0);if(Boolean.TRUE.equals(f.get("hidden"))||Integer.valueOf(1).equals(f.get("hidden"))){db.update("UPDATE index_queue SET status='saved' WHERE history_id=?",id);continue;}if(!Objects.toString(f.get("correction"),"").isBlank())text="用户纠正的记忆："+f.get("correction");}
        text=text.substring(0,Math.min(2400,text.length()));float[] embedding=models.embedding(text);
        // A rewind can occur during the remote call: re-check the source before committing.
        if(db.queryForObject("SELECT COUNT(*) FROM rp_turn WHERE history_id=?",Integer.class,id)==0){db.update("DELETE FROM index_queue WHERE history_id=?",id);continue;}
        if(mode.cloud())db.update("INSERT INTO memory_entry(world_id,history_id,content,embedding,model) VALUES(?,?,?,?::vector,?) ON CONFLICT(history_id) DO UPDATE SET content=excluded.content,embedding=excluded.embedding,model=excluded.model",row.get("world_id"),id,text,vector(embedding),mode.value("embedding-model",""));
        else db.update("INSERT INTO memory_entry(world_id,history_id,content,embedding,model) VALUES(?,?,?,?,?) ON CONFLICT(history_id) DO UPDATE SET content=excluded.content,embedding=excluded.embedding,model=excluded.model",row.get("world_id"),id,text,encode(embedding),mode.value("embedding-model",""));
        db.update("UPDATE index_queue SET status='saved' WHERE history_id=?",id);
      }catch(Exception e){int next=attempts+1;db.update("UPDATE index_queue SET attempts=?,available_at=?,status=? WHERE history_id=?",next,Instant.now().plusSeconds(Math.min(3600,15L*(1L<<Math.min(next,8)))).toString(),next>=8?"failed":"pending",id);}
    }
  }
  public String recall(String world,String query){
    var picked=new LinkedHashSet<String>();
    var scheduledNotices=ScheduledNoticeContext.ids(db,world);
    scheduledNotices.addAll(MissingPastAnswer.unavailableIds(db,world));
    try {
      float[] v=models.embedding(query);
      if(mode.cloud())for(var r:db.queryForList("SELECT content FROM memory_entry WHERE world_id=? AND NOT EXISTS(SELECT 1 FROM memory_feedback f WHERE f.history_id=memory_entry.history_id AND f.hidden=TRUE) AND model=? AND EXISTS(SELECT 1 FROM rp_turn t WHERE t.history_id=memory_entry.history_id AND t.world_id=memory_entry.world_id AND t.user_text<>'') "+ScheduledNoticeContext.excluding("memory_entry.history_id",scheduledNotices)+" ORDER BY embedding <=> ?::vector LIMIT 3",world,mode.value("embedding-model",""),vector(v)))picked.add((String)r.get("content"));
      else {
        record Match(String text,double score){}
        var scores=new ArrayList<Match>();
        for(var r:db.queryForList("SELECT content,embedding FROM memory_entry WHERE world_id=? AND NOT EXISTS(SELECT 1 FROM memory_feedback f WHERE f.history_id=memory_entry.history_id AND f.hidden=TRUE) AND model=? AND EXISTS(SELECT 1 FROM rp_turn t WHERE t.history_id=memory_entry.history_id AND t.world_id=memory_entry.world_id AND t.user_text<>'') "+ScheduledNoticeContext.excluding("memory_entry.history_id",scheduledNotices)+" ORDER BY history_id DESC LIMIT 1000",world,mode.value("embedding-model",""))){double score=cosine(v,decode((byte[])r.get("embedding")));if(score>.45)scores.add(new Match((String)r.get("content"),score));}
        scores.stream().sorted(Comparator.comparingDouble(Match::score).reversed()).limit(3).forEach(m->picked.add(m.text()));
      }
    }catch(Exception ignored){/* original turns and keyword recall remain available */}
    if(!mode.cloud() && query.length()>=3){String phrase="\""+query.substring(0,Math.min(40,query.length())).replace("\"","\"\"")+"\"";
      try{for(var r:db.queryForList("SELECT e.content FROM memory_fts JOIN memory_entry e ON e.id=memory_fts.rowid WHERE memory_fts MATCH ? AND e.world_id=? AND NOT EXISTS(SELECT 1 FROM memory_feedback f WHERE f.history_id=e.history_id AND f.hidden=TRUE) AND EXISTS(SELECT 1 FROM rp_turn t WHERE t.history_id=e.history_id AND t.world_id=e.world_id AND t.user_text<>'') "+ScheduledNoticeContext.excluding("e.history_id",scheduledNotices)+" ORDER BY rank LIMIT 3",phrase,world))picked.add((String)r.get("content"));}catch(Exception ignored){}
    }
    var corrections=db.queryForList("SELECT correction FROM memory_feedback WHERE world_id=? AND hidden=FALSE AND correction<>'' AND EXISTS(SELECT 1 FROM rp_turn t WHERE t.history_id=memory_feedback.history_id AND t.world_id=memory_feedback.world_id) ORDER BY history_id DESC LIMIT 4",world);
    var authoritative=new ArrayList<String>();for(var row:corrections)authoritative.add("用户已纠正，以此为准："+row.get("correction"));authoritative.addAll(picked.stream().limit(4).toList());
    return String.join("\n",authoritative);
  }
}
