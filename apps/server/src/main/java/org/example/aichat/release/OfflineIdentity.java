package org.example.aichat.release;

import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="story.mode",havingValue="offline")
public class OfflineIdentity implements IdentityService {
  private final String token;
  public OfflineIdentity(RuntimeMode mode) {
    token=mode.value("local-token", "");
    if(token.length()<32) throw new IllegalStateException("STORY_LOCAL_TOKEN must be a random token of at least 32 characters");
  }
  public Identity authenticate(HttpServletRequest request) {
    String header=request.getHeader("Authorization");
    return header!=null && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),("Bearer "+token).getBytes(StandardCharsets.UTF_8)) ? new Identity(1,"本地创作者","local@offline",false) : null;
  }
}
