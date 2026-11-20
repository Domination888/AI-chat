package org.example.aichat.release;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@RestController
public class MemoryApi {
  private final StoryStore stories;public MemoryApi(StoryStore stories){this.stories=stories;}
  private String world(String conversation){stories.owned(SecurityConfiguration.current().id(),conversation);return stories.sessions().worldId(conversation);}
  @GetMapping("/api/memory") public Object list(@RequestParam String conversationId){
    String world=world(conversationId);
    // Review and corrections must remain available while the embedding model is
    // preparing or unavailable. The original complete turn is already durable.
    return stories.db().queryForList("SELECT COALESCE(m.id,t.history_id) AS id,t.history_id,COALESCE(m.content,'用户：' || t.user_text || ? || '角色：' || t.assistant_text) AS content,t.user_text,t.assistant_text,t.created_at,COALESCE(f.correction,'') AS correction,COALESCE(f.hidden,FALSE) AS hidden,COALESCE(q.status,'pending') AS index_status FROM rp_turn t LEFT JOIN memory_entry m ON m.history_id=t.history_id AND m.world_id=t.world_id LEFT JOIN memory_feedback f ON f.history_id=t.history_id AND f.world_id=t.world_id LEFT JOIN index_queue q ON q.history_id=t.history_id WHERE t.world_id=? AND t.status='complete' AND t.id=(SELECT MAX(x.id) FROM rp_turn x WHERE x.history_id=t.history_id AND x.world_id=t.world_id) ORDER BY t.history_id DESC LIMIT 100","\n",world);
  }
  @PutMapping("/api/memory/{history}") @Transactional public void correct(@PathVariable long history,@RequestBody Map<String,Object> body){
    String world=world(Objects.toString(body.get("conversationId"),""));if(stories.db().queryForObject("SELECT COUNT(*) FROM rp_turn WHERE history_id=? AND world_id=?",Integer.class,history,world)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记忆不存在");
    String correction=Objects.toString(body.get("correction"),"").strip();if(correction.length()>2000)throw new IllegalArgumentException("记忆纠正不超过 2000 字");boolean hidden=Boolean.TRUE.equals(body.get("hidden"));
    stories.db().update("INSERT INTO memory_feedback(history_id,world_id,correction,hidden) VALUES(?,?,?,?) ON CONFLICT(history_id) DO UPDATE SET correction=excluded.correction,hidden=excluded.hidden",history,world,correction,hidden);
    stories.db().update("INSERT INTO index_queue(history_id,available_at) VALUES(?,?) ON CONFLICT(history_id) DO UPDATE SET status='pending',attempts=0,available_at=excluded.available_at",history,Instant.now().toString());
  }
  @PostMapping("/api/memory/{history}/feedback") public void feedback(@PathVariable long history,@RequestBody Map<String,Object> body){correct(history,body);}
}
