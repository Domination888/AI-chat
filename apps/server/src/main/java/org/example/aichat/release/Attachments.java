package org.example.aichat.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import java.time.Instant;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service @DependsOn("releaseMigrations")
public class Attachments {
  private final JdbcTemplate db;private final ObjectStore objects;private final ObjectMapper json;private final String signing;
  public Attachments(JdbcTemplate db,ObjectStore objects,ObjectMapper json,RuntimeMode mode)throws Exception {
    this.db=db;this.objects=objects;this.json=json;String s=mode.value("signing-key","");
    if(s.isBlank()&&!mode.cloud()){var p=mode.data().resolve(".signing-key");if(!Files.exists(p))Files.writeString(p,Secrets.token());s=Files.readString(p);}
    if(s.length()<32)throw new IllegalStateException("Configure a signing key");signing=s;
  }
  public synchronized List<String> store(int user,String conversation,List<String> images)throws Exception {
    if(images==null)return List.of();if(images.size()>5)throw new IllegalArgumentException("最多上传 5 张图片");var refs=new ArrayList<String>();
    for(String value:images) {
      if(value.startsWith("attachment:")){assertOwner(user,value.substring(11));refs.add(value);link(conversation,value.substring(11));continue;}
      if(!value.matches("(?s)^data:image/(png|jpeg|webp);base64,.*") || value.length()>7*1024*1024)throw new IllegalArgumentException("图片仅支持 PNG、JPEG 或 WebP，单张不超过 5MB");
      int comma=value.indexOf(',');String type=value.substring(5,value.indexOf(';'));byte[] bytes=Base64.getDecoder().decode(value.substring(comma+1));
      if(bytes.length>5*1024*1024 || !validImage(type,bytes))throw new IllegalArgumentException("图片内容不合法");
      String id=UUID.randomUUID().toString();objects.put(user+"/"+id,bytes,type);
      if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{objects.delete(user+"/"+id);}catch(Exception ignored){}}});
      try {db.update("INSERT INTO attachment VALUES(?,?,?,?,?)",id,user,type,bytes.length,Instant.now().toString());link(conversation,id);}
      catch(Exception e){objects.delete(user+"/"+id);throw e;}
      refs.add("attachment:"+id);
    }
    return refs;
  }
  static boolean validImage(String type,byte[] b) {
    if(b.length<12)return false;
    return switch(type){case "image/png"->b[0]==(byte)137&&b[1]==80&&b[2]==78&&b[3]==71;case "image/jpeg"->b[0]==(byte)255&&b[1]==(byte)216&&b[2]==(byte)255;case "image/webp"->new String(b,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(b,8,4,StandardCharsets.US_ASCII).equals("WEBP");default->false;};
  }
  void link(String conv,String id){db.update("INSERT INTO attachment_link VALUES(?,?) ON CONFLICT DO NOTHING",conv,id);}
  Map<String,Object> assertOwner(int user,String id){
    var rows=db.queryForList("SELECT * FROM attachment WHERE id=? AND user_id=?",id,user);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"附件不存在");return rows.get(0);
  }
  public List<String> readRefs(String encoded){try{return json.readValue(encoded,json.getTypeFactory().constructCollectionType(List.class,String.class));}catch(Exception e){return List.of();}}
  public String display(int user,String encoded){return encode(readRefs(encoded).stream().map(ref->{if(!ref.startsWith("attachment:"))return "";String id=ref.substring(11);assertOwner(user,id);long expiry=Instant.now().plusSeconds(3600).getEpochSecond();return "/api/attachments/"+id+"/content?user="+user+"&expires="+expiry+"&signature="+sign(user,id,expiry);}).filter(s->!s.isBlank()).toList());}
  public List<String> forModel(int user,List<String> refs)throws Exception {
    var result=new ArrayList<String>();for(String ref:refs){if(!ref.startsWith("attachment:"))continue;String id=ref.substring(11);var r=assertOwner(user,id);result.add("data:"+r.get("media_type")+";base64,"+Base64.getEncoder().encodeToString(objects.get(user+"/"+id)));}return result;
  }
  private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
  private String sign(int user,String id,long expiry){try{var m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(signing.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal((user+":"+id+":"+expiry).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  public Map<String,Object> content(int user,String id,long expiry,String signature)throws Exception {
    if(expiry<Instant.now().getEpochSecond() || expiry>Instant.now().plusSeconds(3601).getEpochSecond() || signature==null || !java.security.MessageDigest.isEqual(sign(user,id,expiry).getBytes(),signature.getBytes()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"附件链接已失效");
    var r=assertOwner(user,id);return Map.of("type",r.get("media_type"),"bytes",objects.get(user+"/"+id));
  }
  public void deleteUser(int user)throws Exception {for(var r:db.queryForList("SELECT id FROM attachment WHERE user_id=?",user)){objects.delete(user+"/"+r.get("id"));}db.update("DELETE FROM attachment WHERE user_id=?",user);}
  public synchronized void collectUnused(int user)throws Exception {
    for(var row:db.queryForList("SELECT id FROM attachment WHERE user_id=? AND NOT EXISTS(SELECT 1 FROM attachment_link l WHERE l.attachment_id=attachment.id)",user)){
      String id=(String)row.get("id");objects.delete(user+"/"+id);db.update("DELETE FROM attachment WHERE id=? AND user_id=?",id,user);
    }
  }
  public synchronized void refreshLinks(int user,List<String> chapters)throws Exception {
    for(String chapter:chapters){
      var refs=new HashSet<String>();for(String table:List.of("rp_attachment","rp_turn"))for(var r:db.queryForList("SELECT images_json FROM "+table+" WHERE conversation_id=?",chapter))for(String ref:readRefs((String)r.get("images_json")))if(ref.startsWith("attachment:"))refs.add(ref.substring(11));
      db.update("DELETE FROM attachment_link WHERE conversation_id=?",chapter);for(String id:refs){assertOwner(user,id);link(chapter,id);}
    }
    collectUnused(user);
  }
}
