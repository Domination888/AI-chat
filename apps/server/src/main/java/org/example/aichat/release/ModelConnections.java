package org.example.aichat.release;

import java.util.Map;

public interface ModelConnections {
  record Connection(String baseUrl,String model,String apiKey,boolean custom) {}
  Connection forUser(int user);
  Map<String,Object> metadata(int user);
  void save(int user,String url,String model,String key,boolean allowNetwork);
  void delete(int user);
}
