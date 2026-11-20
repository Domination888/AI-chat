package org.example.aichat.release.cloud;

import org.example.aichat.release.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;

@Service @DependsOn("releaseMigrations")
@ConditionalOnProperty(name="story.mode",havingValue="cloud")
public class CloudIdentity implements IdentityService {
  private final JdbcTemplate db;private final RuntimeMode mode;private final JavaMailSender mail;
  private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12);
  private final Map<String,Deque<Instant>> attempts=new java.util.concurrent.ConcurrentHashMap<>();
  public CloudIdentity(JdbcTemplate db,RuntimeMode mode,JavaMailSender mail) {
    this.db=db;this.mode=mode;this.mail=mail;
    String email=mode.value("admin-email",""),pwd=mode.value("admin-password",""),totp=mode.value("admin-totp-secret","");
    if(email.isBlank() || pwd.length()<16 || Totp.decode(totp).length<16) throw new IllegalStateException("Configure admin email, 16+ character password and TOTP secret before cloud startup");
    if(mode.value("signing-key","").length()<32) throw new IllegalStateException("STORY_SIGNING_KEY must contain at least 32 random characters");
    db.update("INSERT INTO account(email,username,password,admin,created_at) VALUES(?,?,?,TRUE,?) ON CONFLICT(email) DO NOTHING",normalize(email),"管理员",passwords.encode(pwd),now());
  }
  static String now() { return Instant.now().toString(); }
  static String normalize(String email) {
    if(email==null || email.length()>254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException("请输入有效邮箱");return email.toLowerCase(Locale.ROOT).trim();
  }
  private ResponseStatusException denied(String message) { return new ResponseStatusException(HttpStatus.UNAUTHORIZED,message); }
  public synchronized void limit(String key,int count,int minutes) {
    Instant now=Instant.now();attempts.entrySet().removeIf(e->e.getValue().isEmpty() || e.getValue().getLast().isBefore(now.minusSeconds(3600)));
    if(attempts.size()>10000 && !attempts.containsKey(key))throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"请求繁忙，请稍后重试");
    var q=attempts.computeIfAbsent(key,k->new ArrayDeque<>());while(!q.isEmpty() && q.getFirst().isBefore(now.minusSeconds(minutes*60L)))q.removeFirst();
    if(q.size()>=count)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"请求过于频繁，请稍后重试");q.addLast(now);
  }
  private String codeHash(String email,String code) {return Secrets.hash(mode.value("signing-key","")+":"+email+":"+code);}
  public void sendCode(String raw,String invite,String ip) {
    String email=normalize(raw);limit("mail-ip:"+ip,10,60);limit("mail:"+email,3,10);
    var valid=db.queryForList("SELECT id FROM invitation WHERE code_hash=? AND used_by IS NULL AND expires_at>?",Secrets.hash(Objects.toString(invite,"")),now());
    if(valid.isEmpty())throw new IllegalArgumentException("邀请码无效或已过期");
    String code=String.format("%06d",new java.security.SecureRandom().nextInt(1000000));
    var message=new SimpleMailMessage();message.setFrom(mode.value("mail-from","").isBlank()?mode.value("admin-email",""):mode.value("mail-from",""));message.setTo(email);message.setSubject("幕间 · 验证你的邮箱");message.setText("你的验证码是 "+code+"，10 分钟内有效。若非本人操作，请忽略。");
    // SMTP happens before persistence: a delivery failure never leaves a usable challenge.
    try {mail.send(message);}catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"验证邮件暂时无法发送，请稍后重试");}
    db.update("INSERT INTO email_challenge VALUES(?,?,?,0) ON CONFLICT(email) DO UPDATE SET code_hash=excluded.code_hash,expires_at=excluded.expires_at,attempts=0",email,codeHash(email,code),Instant.now().plusSeconds(600).toString());
  }
  public void verifyCode(String email,String code) {
    var rows=db.queryForList("UPDATE email_challenge SET attempts=attempts+1 WHERE email=? AND attempts<5 AND expires_at>? RETURNING code_hash",email,now());
    if(rows.isEmpty() || !java.security.MessageDigest.isEqual(Objects.toString(rows.get(0).get("code_hash"),"").getBytes(),codeHash(email,Objects.toString(code,"")).getBytes()))throw new IllegalArgumentException("验证码无效或已过期");
  }
  @Transactional
  public Map<String,Object> registerVerified(String email,String password,String name,String invite) {
    if(password==null || password.length()<10 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw new IllegalArgumentException("密码须为 10–72 字节");
    if(name==null || name.isBlank() || name.length()>40)throw new IllegalArgumentException("称呼须为 1–40 个字符");
    if(db.queryForObject("SELECT COUNT(*) FROM account WHERE email=?",Integer.class,email)>0)throw new IllegalArgumentException("邮箱已注册，请登录");
    var rows=db.queryForList("SELECT id FROM invitation WHERE code_hash=? AND used_by IS NULL AND expires_at>? FOR UPDATE",Secrets.hash(Objects.toString(invite,"")),now());
    if(rows.isEmpty())throw new IllegalArgumentException("邀请码无效或已过期");
    int id=db.queryForObject("INSERT INTO account(email,username,password,created_at) VALUES(?,?,?,?) RETURNING id",Integer.class,email,name,passwords.encode(password),now());
    db.update("UPDATE invitation SET used_by=? WHERE id=?",id,rows.get(0).get("id"));db.update("DELETE FROM email_challenge WHERE email=?",email);
    return createSession(new Identity(id,name,email,false));
  }
  public Map<String,Object> login(String email,String password,String otp,String ip,boolean adminOnly) {
    limit("login:"+ip,15,10);email=normalize(email);limit("account:"+email,10,10);
    var rows=db.queryForList("SELECT * FROM account WHERE email=? AND status='active'",email);
    if(rows.isEmpty() || password==null || password.length()>72 || !passwords.matches(password,Objects.toString(rows.get(0).get("password"),"")))throw denied("邮箱或密码错误");
    var row=rows.get(0);boolean admin=Boolean.TRUE.equals(row.get("admin"));
    if(admin && !Totp.valid(mode.value("admin-totp-secret",""),otp))throw denied("管理员需要有效的动态验证码");
    if(adminOnly&&!admin)throw denied("此入口仅限管理员");
    return createSession(new Identity(((Number)row.get("id")).intValue(),(String)row.get("username"),email,admin));
  }
  private Map<String,Object> createSession(Identity who) {
    String access=Secrets.token(),refresh=Secrets.token(),session=UUID.randomUUID().toString();
    db.update("INSERT INTO auth_session VALUES(?,?,?,?,?,?,?)",session,who.id(),Secrets.hash(access),Instant.now().plusSeconds(900).toString(),Secrets.hash(refresh),Instant.now().plusSeconds(30*86400L).toString(),now());
    return Map.of("accessToken",access,"refreshToken",refresh,"expiresIn",900,"user",who);
  }
  @Transactional public Map<String,Object> refresh(String token) {
    var rows=db.queryForList("SELECT s.id AS session_id,a.* FROM auth_session s JOIN account a ON a.id=s.user_id WHERE s.refresh_hash=? AND s.refresh_expires>? AND a.status='active' FOR UPDATE OF s",Secrets.hash(Objects.toString(token,"")),now());
    if(rows.isEmpty())throw denied("登录已过期，请重新登录");var r=rows.get(0);db.update("DELETE FROM auth_session WHERE id=?",r.get("session_id"));
    return createSession(new Identity(((Number)r.get("id")).intValue(),(String)r.get("username"),(String)r.get("email"),Boolean.TRUE.equals(r.get("admin"))));
  }
  public Identity authenticate(HttpServletRequest request) {
    String h=request.getHeader("Authorization");if(h==null || !h.startsWith("Bearer ") || h.length()>200)return null;
    var rows=db.queryForList("SELECT a.id,a.username,a.email,a.admin FROM auth_session s JOIN account a ON a.id=s.user_id WHERE s.access_hash=? AND s.access_expires>? AND a.status='active'",Secrets.hash(h.substring(7)),now());
    if(rows.isEmpty())return null;var r=rows.get(0);return new Identity(((Number)r.get("id")).intValue(),(String)r.get("username"),(String)r.get("email"),Boolean.TRUE.equals(r.get("admin")));
  }
  public void logout(String token) {db.update("DELETE FROM auth_session WHERE refresh_hash=?",Secrets.hash(Objects.toString(token,"")));}
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
  public void cleanup() {db.update("DELETE FROM auth_session WHERE refresh_expires<?",now());db.update("DELETE FROM email_challenge WHERE expires_at<?",now());}
}
