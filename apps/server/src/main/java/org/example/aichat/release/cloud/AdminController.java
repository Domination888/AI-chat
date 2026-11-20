package org.example.aichat.release.cloud;

import org.example.aichat.release.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/api/v1/admin")
@ConditionalOnProperty(name="story.mode",havingValue="cloud")
public class AdminController {
  private final JdbcTemplate db;private final Streams streams;public AdminController(JdbcTemplate db,Streams streams){this.db=db;this.streams=streams;}
  @GetMapping("/users") public Object users(){return db.queryForList("SELECT id,email,username,status,admin,created_at FROM account ORDER BY id DESC LIMIT 1000");}
  @GetMapping("/usage") public Object usage(){return db.queryForList("SELECT u.*,a.email FROM daily_usage u JOIN account a ON a.id=u.user_id ORDER BY day DESC,user_id LIMIT 1000");}
  @GetMapping("/audit") public Object audit(){return db.queryForList("SELECT * FROM audit_event ORDER BY id DESC LIMIT 200");}
  @GetMapping("/faults") public Object faults(){return db.queryForList("SELECT * FROM fault ORDER BY id DESC LIMIT 200");}
  record Status(String status){}
  @PutMapping("/users/{id}/status") @Transactional public void status(@PathVariable int id,@RequestBody Status r){
    if(!Set.of("active","suspended").contains(r.status()))throw new IllegalArgumentException("账号状态无效");
    if(id==SecurityConfiguration.current().id())throw new IllegalArgumentException("不能暂停当前管理员");
    db.update("UPDATE account SET status=? WHERE id=? AND admin=FALSE",r.status(),id);
    if(r.status().equals("suspended")){streams.stopUser(id,org.example.aichat.release.CancelCause.ACCOUNT_SUSPENDED);db.update("DELETE FROM auth_session WHERE user_id=?",id);}
    audit("account_status:"+r.status(),Integer.toString(id));
  }
  @PostMapping("/invitations") public Object invite(){
    String code=Secrets.token(),id=UUID.randomUUID().toString();String expiry=Instant.now().plusSeconds(7*86400).toString();
    db.update("INSERT INTO invitation(id,code_hash,expires_at,created_at) VALUES(?,?,?,?)",id,Secrets.hash(code),expiry,Instant.now().toString());audit("invitation_created",id);return Map.of("code",code,"expiresAt",expiry);
  }
  private void audit(String action,String subject){db.update("INSERT INTO audit_event(actor,action,subject,created_at) VALUES(?,?,?,?)",SecurityConfiguration.current().id(),action,subject,Instant.now().toString());}
}
