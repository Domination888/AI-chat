package org.example.aichat.release;

import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@DependsOn("releaseMigrations")
@ConditionalOnProperty(name="story.mode",havingValue="offline")
public class OfflineConnections implements ModelConnections {
  private final JdbcTemplate db;private final RuntimeMode mode;private final byte[] key;
  public OfflineConnections(JdbcTemplate db,RuntimeMode mode) throws Exception {
    this.db=db;this.mode=mode;var file=mode.data().resolve(".credentials-key");
    if(!Files.exists(file)) { Files.writeString(file,Secrets.token());try { Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }catch(UnsupportedOperationException ignored){} }
    key=Base64.getUrlDecoder().decode(Files.readString(file));
  }
  public Connection forUser(int user) {
    var rows=db.queryForList("SELECT * FROM model_connection WHERE user_id=1");
    if(rows.isEmpty()) return new Connection(mode.value("llm-url","http://127.0.0.1:12345/v1"),mode.value("llm-model","Qwen3-4B"),mode.value("llm-key",""),false);
    var r=rows.get(0);return new Connection((String)r.get("base_url"),(String)r.get("model"),Secrets.decrypt((String)r.get("key_cipher"),key,"local:1"),true);
  }
  public Map<String,Object> metadata(int user) { var c=forUser(user);return Map.of("custom",c.custom(),"baseUrl",c.baseUrl(),"model",c.model(),"hasKey",!c.apiKey().isBlank(),"allowNetwork",!isLoopback(c.baseUrl())); }
  private boolean isLoopback(String url) { try {NetworkPolicy.requireLoopback(url);return true;}catch(Exception e){return false;} }
  public void save(int user,String url,String model,String secret,boolean allowNetwork) {
    if(allowNetwork) NetworkPolicy.offlineExternalHttps(url);else NetworkPolicy.requireLoopback(url);
    if(model==null || model.isBlank() || model.length()>200 || secret==null || secret.length()>4096) throw new IllegalArgumentException("模型或密钥格式不合法");
    if(secret.isBlank() && forUser(user).baseUrl().equals(url)) secret=forUser(user).apiKey();
    db.update("INSERT INTO model_connection VALUES(1,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET base_url=excluded.base_url,model=excluded.model,key_cipher=excluded.key_cipher,updated_at=excluded.updated_at",url,model,Secrets.encrypt(secret,key,"local:1"),java.time.Instant.now().toString());
  }
  public void delete(int user) { db.update("DELETE FROM model_connection WHERE user_id=1"); }
}
