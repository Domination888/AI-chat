package org.example.aichat.release.cloud;

import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController @RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name="story.mode",havingValue="cloud")
public class AuthController {
  private final CloudIdentity auth;public AuthController(CloudIdentity auth){this.auth=auth;}
  record Credentials(String email,String password,String otp,String username,String invitation,String code,String refreshToken){}
  @PostMapping("/email-code") public void code(@RequestBody Credentials r,HttpServletRequest request){auth.sendCode(r.email(),r.invitation(),request.getRemoteAddr());}
  @PostMapping("/register") public Object register(@RequestBody Credentials r,HttpServletRequest request){
    auth.limit("register:"+request.getRemoteAddr(),10,10);String email=CloudIdentity.normalize(r.email());auth.verifyCode(email,r.code());return auth.registerVerified(email,r.password(),r.username(),r.invitation());
  }
  @PostMapping("/login") public Object login(@RequestBody Credentials r,HttpServletRequest request){return auth.login(r.email(),r.password(),r.otp(),request.getRemoteAddr(),false);}
  @PostMapping("/admin-login") public Object admin(@RequestBody Credentials r,HttpServletRequest request){return auth.login(r.email(),r.password(),r.otp(),request.getRemoteAddr(),true);}
  @PostMapping("/refresh") public Object refresh(@RequestBody Credentials r){return auth.refresh(r.refreshToken());}
  @PostMapping("/logout") public void logout(@RequestBody Credentials r){auth.logout(r.refreshToken());}
}
