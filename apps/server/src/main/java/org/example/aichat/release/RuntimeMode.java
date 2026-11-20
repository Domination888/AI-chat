package org.example.aichat.release;

import java.nio.file.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RuntimeMode {
  private final Environment env;
  public RuntimeMode(Environment env) throws Exception {
    this.env=env; Files.createDirectories(data());
    if (!cloud() && !env.getProperty("story.llm-url", "").isBlank()) NetworkPolicy.requireLoopback(env.getProperty("story.llm-url"));
  }
  public boolean cloud() { return "cloud".equals(env.getProperty("story.mode")); }
  public Path data() { return Path.of(env.getProperty("story.data-dir", "./story-data")).toAbsolutePath().normalize(); }
  public String value(String key, String fallback) { return env.getProperty("story."+key, fallback); }
  public int number(String key,int fallback) { return env.getProperty("story."+key,Integer.class,fallback); }
}
