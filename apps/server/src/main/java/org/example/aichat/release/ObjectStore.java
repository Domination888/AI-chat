package org.example.aichat.release;
public interface ObjectStore {
  void put(String key,byte[] bytes,String type) throws Exception;
  byte[] get(String key) throws Exception;
  void delete(String key) throws Exception;
}
