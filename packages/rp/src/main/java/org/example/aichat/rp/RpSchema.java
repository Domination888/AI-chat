package org.example.aichat.rp;

import org.springframework.jdbc.core.JdbcTemplate;

/** Additive RP tables. Existing conversation/history tables are not rewritten. */
public final class RpSchema {
  private RpSchema() {}

  public static void ensure(JdbcTemplate db) {
    db.execute(
        "CREATE TABLE IF NOT EXISTS rp_audio_sentence(stream_id VARCHAR(64),conversation_id"
            + " VARCHAR(64) NOT NULL,idx INT,text TEXT NOT NULL,heard BOOLEAN DEFAULT"
            + " FALSE,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY"
            + " KEY(stream_id,idx),INDEX rp_audio_conversation(conversation_id,created_at))");
    db.execute(
        "CREATE TABLE IF NOT EXISTS rp_attachment(history_id BIGINT PRIMARY KEY,conversation_id"
            + " VARCHAR(64) NOT NULL,images_json MEDIUMTEXT NOT NULL)");
    db.execute(
        "CREATE TABLE IF NOT EXISTS rp_world (id VARCHAR(64) PRIMARY KEY, user_id INT NOT NULL,"
            + " role_id INT NOT NULL, state_json MEDIUMTEXT NOT NULL, initial_json MEDIUMTEXT NOT"
            + " NULL)");
    db.execute(
        "CREATE TABLE IF NOT EXISTS rp_session (conversation_id VARCHAR(64) PRIMARY KEY, world_id"
            + " VARCHAR(64) NOT NULL, INDEX rp_world_idx(world_id))");
    db.execute(
        "CREATE TABLE IF NOT EXISTS rp_turn (id BIGINT AUTO_INCREMENT PRIMARY KEY, world_id"
            + " VARCHAR(64) NOT NULL, conversation_id VARCHAR(64) NOT NULL, history_id BIGINT NOT"
            + " NULL, state_json MEDIUMTEXT NOT NULL, user_text MEDIUMTEXT NOT NULL, assistant_text"
            + " MEDIUMTEXT NOT NULL, status VARCHAR(20) NOT NULL, images_json MEDIUMTEXT NOT NULL,"
            + " bookmarked BOOLEAN DEFAULT FALSE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
            + " INDEX rp_turn_world(world_id,id), INDEX"
            + " rp_turn_history(conversation_id,history_id))");
    db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection->{
      boolean found=false;try(var columns=connection.getMetaData().getColumns(connection.getCatalog(),null,"rp_turn","before_state_json")){found=columns.next();}
      if(!found)try(var statement=connection.createStatement()){statement.execute("ALTER TABLE rp_turn ADD COLUMN before_state_json MEDIUMTEXT NULL");}
      return null;
    });
  }
}
