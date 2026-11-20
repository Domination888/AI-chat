package org.example.aichat.release;
import java.nio.file.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Component @ConditionalOnProperty(name="story.mode",havingValue="offline")
public class FilesystemObjects implements ObjectStore {
  private final Path root;
  public FilesystemObjects(RuntimeMode mode) throws Exception {root=mode.data().resolve("attachments");Files.createDirectories(root);}
  private Path path(String key) {var p=root.resolve(key).normalize();if(!p.startsWith(root))throw new IllegalArgumentException("Invalid object key");return p;}
  public void put(String key,byte[] bytes,String type)throws Exception {var p=path(key);Files.createDirectories(p.getParent());Files.write(p,bytes,StandardOpenOption.CREATE_NEW);}
  public byte[] get(String key)throws Exception {return Files.readAllBytes(path(key));}
  public void delete(String key)throws Exception {Files.deleteIfExists(path(key));}
}
