package org.example.aichat.release;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.InitializingBean;

/** Checksummed, transactional migrations; startup never silently changes an existing version. */
@Component("releaseMigrations")
public class Migrations implements InitializingBean {
  private final JdbcTemplate db; private final RuntimeMode mode;
  public Migrations(JdbcTemplate db,RuntimeMode mode) { this.db=db;this.mode=mode; }
  @Override public void afterPropertiesSet() throws Exception {
    db.execute("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL)");
    migrate(1,"db/V1__"+(mode.cloud()?"postgres":"sqlite")+".sql");
    migrate(2,"db/V2__companion.sql");
    migrate(3,"db/V3__capabilities.sql");
    migrate(4,"db/V4__anime_starters.sql");
    migrate(5,"db/V5__turn_before.sql");
  }
  private void migrate(int version,String name)throws Exception {
    var resource=new ClassPathResource(name);
    String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resource.getContentAsByteArray()));
    var old=db.query("SELECT checksum FROM schema_version WHERE version=?",(rs,n)->rs.getString(1),version);
    if (!old.isEmpty()) {
      if (!old.get(0).equals(checksum)) throw new IllegalStateException("Migration checksum mismatch: "+name);
      return;
    }
    db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
      connection.setAutoCommit(false);
      try {
        ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.support.EncodedResource(resource, StandardCharsets.UTF_8),false,false,"--","\n--statement--\n","/*","*/");
        try(var stmt=connection.prepareStatement("INSERT INTO schema_version VALUES(?,?)")) { stmt.setInt(1,version);stmt.setString(2,checksum);stmt.executeUpdate(); }
        connection.commit();
      } catch(Exception e) { connection.rollback();throw e; }
      finally { connection.setAutoCommit(true); }
      return null;
    });
  }
}
