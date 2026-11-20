package org.example.aichat.release.cloud;

import org.example.aichat.release.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;

@Service @DependsOn("releaseMigrations")
@ConditionalOnProperty(name="story.mode",havingValue="cloud")
public class CloudConnections implements ModelConnections {
  private final JdbcTemplate db;private final RuntimeMode mode;private final byte[] key;
  public CloudConnections(JdbcTemplate db,RuntimeMode mode) {
    this.db=db;this.mode=mode;
    key=Base64.getDecoder().decode(mode.value("encryption-key",""));
    if(key.length!=32) throw new IllegalStateException("STORY_ENCRYPTION_KEY must be base64 of 32 random bytes");
  }
  public Connection forUser(int user) {
    var rows=db.queryForList("SELECT * FROM model_connection WHERE user_id=?",user);
    if(rows.isEmpty()) return new Connection(mode.value("llm-url",""),mode.value("llm-model",""),mode.value("llm-key",""),false);
    var r=rows.get(0);return new Connection((String)r.get("base_url"),(String)r.get("model"),Secrets.decrypt((String)r.get("key_cipher"),key,"account:"+user),true);
  }
  public Map<String,Object> metadata(int user) { var c=forUser(user);return Map.of("custom",c.custom(),"baseUrl",c.custom()?c.baseUrl():"","model",c.model(),"hasKey",c.custom()&&!c.apiKey().isBlank()); }
  public void save(int user,String url,String model,String secret,boolean ignored) {
    NetworkPolicy.publicHttps(url);
    if(mode.value("byok-proxy-host", "").isBlank()) throw new IllegalArgumentException("自带 API 代理尚未配置，请联系管理员");
    if(model==null || model.isBlank() || model.length()>200 || secret==null || secret.length()>4096) throw new IllegalArgumentException("模型或密钥格式不合法");
    if(secret.isBlank() && forUser(user).custom() && forUser(user).baseUrl().equals(url)) secret=forUser(user).apiKey();
    if(secret.isBlank()) throw new IllegalArgumentException("请输入 API 密钥");
    db.update("INSERT INTO model_connection VALUES(?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET base_url=excluded.base_url,model=excluded.model,key_cipher=excluded.key_cipher,updated_at=excluded.updated_at",user,url,model,Secrets.encrypt(secret,key,"account:"+user),java.time.Instant.now().toString());
  }
  public void delete(int user) { db.update("DELETE FROM model_connection WHERE user_id=?",user); }
}
