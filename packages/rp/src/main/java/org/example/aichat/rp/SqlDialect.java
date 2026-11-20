package org.example.aichat.rp;

import org.springframework.jdbc.core.JdbcTemplate;

/** Only SQL differences belong here; RP behavior is shared by all distributions. */
public enum SqlDialect {
  MYSQL, POSTGRES, SQLITE;
  public static SqlDialect of(JdbcTemplate db) {
    return db.execute((org.springframework.jdbc.core.ConnectionCallback<SqlDialect>) c -> {
      String name=c.getMetaData().getDatabaseProductName();
      return name.contains("SQLite") ? SQLITE : name.contains("PostgreSQL") ? POSTGRES : MYSQL;
    });
  }
  public void insertIgnore(JdbcTemplate db, String table, String columns, String values, Object... args) {
    String sql="INSERT "+(this==MYSQL ? "IGNORE " : "")+"INTO "+table+"("+columns+") VALUES("+values+")";
    db.update(sql+(this==MYSQL ? "" : " ON CONFLICT DO NOTHING"),args);
  }
  public String lock() { return this==SQLITE ? "" : " FOR UPDATE"; }
}
