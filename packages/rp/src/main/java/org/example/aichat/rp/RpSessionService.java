package org.example.aichat.rp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RpSessionService {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  @PostConstruct
  public void schema() {
    if (SqlDialect.of(db) == SqlDialect.MYSQL) RpSchema.ensure(db);
  }

  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public RpState decode(String value) {
    try {
      return json.readValue(value, RpState.class).normalize();
    } catch (Exception e) {
      throw new IllegalStateException("场景数据无法读取，已保留原始数据", e);
    }
  }

  public String worldId(String conversation) {
    return db
        .query(
            "SELECT world_id FROM rp_session WHERE conversation_id=?",
            (rs, n) -> rs.getString(1),
            conversation)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Transactional
  public Map<String, Object> create(
      String conversation, int user, int role, String continueWorld, RpState initial) {
    initial.normalize();
    if (worldId(conversation) != null) return view(conversation);
    String world = continueWorld;
    if (world == null || world.isBlank()) {
      world = UUID.randomUUID().toString();
      db.update(
          "INSERT INTO rp_world VALUES(?,?,?,?,?)",
          world,
          user,
          role,
          encode(initial),
          encode(initial));
    } else if (db.queryForObject(
            "SELECT COUNT(*) FROM rp_world WHERE id=? AND user_id=? AND role_id=?",
            Integer.class,
            world,
            user,
            role)
        == 0) {
      throw new IllegalArgumentException("只能继续当前角色的已有世界线");
    }
    db.update("INSERT INTO rp_session VALUES(?,?)", conversation, world);
    SqlDialect.of(db).insertIgnore(db, "conversation", "id,user_id,role_id,title", "?,?,?,?",
        conversation,
        user,
        role,
        initial.location.isBlank() ? "新的相遇" : initial.location);
    return view(conversation);
  }

  @Transactional
  public void ensure(String conversation, int user, int role) {
    if (worldId(conversation) != null) return;
    RpState initial = new RpState();
    initial.mode = "companion";
    initial.style = "chat";
    initial.legacyMemory = true;
    create(conversation, user, role, null, initial);
  }

  public List<String> chapters(String conversation) {
    return db.query(
        "SELECT conversation_id FROM rp_session WHERE world_id=?",
        (rs, n) -> rs.getString(1),
        worldId(conversation));
  }

  public RpState state(String conversation) {
    String world = worldId(conversation);
    if (world == null) return new RpState();
    return decode(
        db.queryForObject("SELECT state_json FROM rp_world WHERE id=?", String.class, world));
  }

  @Transactional
  public Map<String, Object> view(String conversation) {
    if (worldId(conversation) == null) {
      var owners =
          db.queryForList("SELECT user_id,role_id FROM conversation WHERE id=?", conversation);
      if (!owners.isEmpty())
        ensure(
            conversation,
            ((Number) owners.get(0).get("user_id")).intValue(),
            ((Number) owners.get(0).get("role_id")).intValue());
    }
    return Map.of(
        "worldId",
        Objects.toString(worldId(conversation), ""),
        "state",
        state(conversation),
        "turns",
        db.queryForList(
            "SELECT id,history_id,status,bookmarked,created_at FROM rp_turn WHERE conversation_id=?"
                + " ORDER BY id DESC LIMIT 100",
            conversation));
  }

  public void saveState(String conversation, RpState state) {
    db.update(
        "UPDATE rp_world SET state_json=? WHERE id=?",
        encode(state.normalize()),
        worldId(conversation));
  }

  @Transactional
  public void editScene(String conversation, RpState state) {
    saveState(conversation, state);
    if (db.queryForObject(
            "SELECT COUNT(*) FROM rp_turn WHERE world_id=?", Integer.class, worldId(conversation))
        == 0)
      db.update(
          "UPDATE rp_world SET initial_json=? WHERE id=?",
          encode(state.normalize()),
          worldId(conversation));
  }

  public String prompt(String conversation) {
    return prompt(conversation, "");
  }

  public String prompt(String conversation, String query) {
    RpState state = state(conversation);
    String events = recentEvents(conversation);
    if (query != null && query.length() > 1) {
      var terms = query.replaceAll("[\\p{Punct}，。！？（）]", " ").split("\\s+");
      LinkedHashSet<String> recalls = new LinkedHashSet<>();
      for (String term : terms) {
        if (term.length() < 2) continue;
        String key = term.length() > 12 ? term.substring(0, 12) : term;
        for (var row :
            db.queryForList(
                "SELECT user_text,assistant_text FROM rp_turn WHERE world_id=? AND (user_text LIKE"
                    + " ? OR assistant_text LIKE ?) ORDER BY id DESC LIMIT 3",
                worldId(conversation),
                "%" + key + "%",
                "%" + key + "%"))
          recalls.add(
              "用户："
                  + cut(Objects.toString(row.get("user_text"), ""), 400)
                  + "；角色："
                  + cut(Objects.toString(row.get("assistant_text"), ""), 600));
        if (recalls.size() >= 3) break;
      }
      events += "\n相关旧事（有原始对话依据）：\n" + String.join("\n", recalls);
    }
    return RpPromptBuilder.build(state, encode(state), events);
  }

  public String recentEvents(String conversation) {
    return recentEvents(conversation, Set.of());
  }

  public String recentEvents(String conversation, Set<Long> excludedHistoryIds) {
    String world = worldId(conversation);
    if (world == null) return "";
    var rows =
        db.queryForList(
            "SELECT history_id,user_text,assistant_text,status FROM rp_turn WHERE world_id=? ORDER BY id DESC"
                + " LIMIT 12",
            world);
    Collections.reverse(rows);
    StringBuilder text = new StringBuilder();
    for (var row : rows) {
      if (excludedHistoryIds.contains(((Number) row.get("history_id")).longValue())) continue;
      text.append("用户：")
          .append(cut(Objects.toString(row.get("user_text"), ""), 500))
          .append("\n角色（")
          .append(row.get("status"))
          .append("）：")
          .append(cut(Objects.toString(row.get("assistant_text"), ""), 700))
          .append("\n");
    }
    return cut(text.toString(), 10000);
  }

  @Transactional
  public void record(
      String conversation,
      long historyId,
      String user,
      String assistant,
      String status,
      List<String> images,
      String patch) {
    String world = worldId(conversation);
    if (world == null) return;
    db.queryForObject("SELECT id FROM rp_world WHERE id=?" + SqlDialect.of(db).lock(), String.class, world);
    RpState state = state(conversation);
    String beforeState=encode(state);
    if ("complete".equals(status) && patch != null && !patch.isBlank()) {
      try {
        state.apply(json.readValue(patch, new TypeReference<Map<String, Object>>() {}));
      } catch (Exception ignored) {
        /* malformed model state never replaces a valid snapshot */
      }
    }
    saveState(conversation, state);
    db.update(
        "INSERT INTO"
            + " rp_turn(world_id,conversation_id,history_id,state_json,user_text,assistant_text,status,images_json,created_at,before_state_json)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        world,
        conversation,
        historyId,
        encode(state),
        Objects.toString(user, ""),
        Objects.toString(assistant, ""),
        status,
        encode(images == null ? List.of() : images),
        // SQLite CURRENT_TIMESTAMP has no UTC suffix. Persist the actual instant
        // explicitly so clients can display local time without guessing imported dates.
        SqlDialect.of(db) == SqlDialect.MYSQL
            ? java.sql.Timestamp.from(Instant.now()) : Instant.now().toString(),beforeState);
  }

  public List<Map<String, Object>> album(String conversation) {
    return db.queryForList(
        "SELECT id,conversation_id,history_id,user_text,assistant_text,images_json,bookmarked FROM"
            + " rp_turn WHERE world_id=? AND (images_json<>'[]' OR bookmarked=TRUE) ORDER BY id"
            + " DESC LIMIT 100",
        worldId(conversation));
  }

  public void bookmark(String conversation, long id, boolean value) {
    db.update(
        "UPDATE rp_turn SET bookmarked=? WHERE id=? AND world_id=?",
        value,
        id,
        worldId(conversation));
  }

  @Transactional
  public void rewind(String conversation, long beforeHistoryId) {
    String world = worldId(conversation);
    if (world == null) throw new IllegalArgumentException("会话没有场景记录");
    Long first =
        db.queryForObject(
            "SELECT MIN(id) FROM rp_turn WHERE conversation_id=? AND history_id>=?",
            Long.class,
            conversation,
            beforeHistoryId);
    if (first != null) {
      var previous =
          db.query(
              "SELECT state_json FROM rp_turn WHERE world_id=? AND id<? ORDER BY id DESC LIMIT 1",
              (rs, n) -> rs.getString(1),
              world,
              first);
      String before=db.queryForObject("SELECT before_state_json FROM rp_turn WHERE id=?",String.class,first);
      RpState restored = before!=null&&!before.isBlank()?decode(before):
          previous.isEmpty()
              ? decode(
                  db.queryForObject(
                      "SELECT initial_json FROM rp_world WHERE id=?", String.class, world))
              : decode(previous.get(0));
      restored.legacyMemory = false;
      saveState(conversation, restored);
      // A rewind is world-wide: later events must not survive through another session.
      var affected =
          db.queryForList(
              "SELECT conversation_id,MIN(history_id) AS cutoff FROM rp_turn WHERE world_id=? AND"
                  + " id>=? GROUP BY conversation_id",
              world,
              first);
      for (var row : affected)
        db.update(
            "DELETE FROM history WHERE conversation_id=? AND id>=?",
            row.get("conversation_id"),
            row.get("cutoff"));
      db.update("DELETE FROM rp_turn WHERE world_id=? AND id>=?", world, first);
    }
    db.update(
        "DELETE FROM history WHERE conversation_id=? AND id>=?", conversation, beforeHistoryId);
    db.update(
        "DELETE FROM rp_attachment WHERE NOT EXISTS (SELECT 1 FROM history h WHERE h.id=rp_attachment.history_id)");
    db.update(
        "DELETE FROM rp_audio_sentence WHERE conversation_id IN (SELECT conversation_id FROM"
            + " rp_session WHERE world_id=?)",
        world);
  }

  @Transactional
  public String branch(String conversation, long throughHistoryId) {
    view(conversation);
    var owner = db.queryForMap("SELECT user_id,role_id FROM conversation WHERE id=?", conversation);
    int user = ((Number) owner.get("user_id")).intValue();
    String lock = SqlDialect.of(db).lock();
    // Serialize branches for this user before reading titles. A fresh locking read
    // also sees branches committed while the first read waited for their locks.
    db.queryForList("SELECT id FROM conversation WHERE user_id=? ORDER BY id" + lock, user);
    Set<String> usedTitles = new HashSet<>();
    String sourceTitle = "新的相遇";
    for (var row :
        db.queryForList("SELECT id,title FROM conversation WHERE user_id=? ORDER BY id" + lock, user)) {
      String title = Objects.toString(row.get("title"), "");
      usedTitles.add(title);
      if (conversation.equals(row.get("id")) && !title.isBlank()) sourceTitle = title;
    }
    String branchTitle;
    for (int number = 1; ; number++) {
      String suffix = " · 分支 " + number;
      // Leave room for the marker in the shared MySQL VARCHAR(255) column.
      int length = Math.min(255 - suffix.length(), sourceTitle.codePointCount(0, sourceTitle.length()));
      branchTitle = sourceTitle.substring(0, sourceTitle.offsetByCodePoints(0, length)) + suffix;
      if (!usedTitles.contains(branchTitle)) break;
    }
    var snapshots =
        db.query(
            "SELECT state_json FROM rp_turn WHERE conversation_id=? AND history_id<=? ORDER BY id"
                + " DESC LIMIT 1",
            (rs, n) -> rs.getString(1),
            conversation,
            throughHistoryId);
    RpState copy =
        snapshots.isEmpty()
            ? decode(
                db.queryForObject(
                    "SELECT initial_json FROM rp_world WHERE id=?",
                    String.class,
                    worldId(conversation)))
            : decode(snapshots.get(0));
    copy.legacyMemory = false;
    String target = "conv_" + UUID.randomUUID();
    create(
        target,
        user,
        ((Number) owner.get("role_id")).intValue(),
        null,
        copy);
    db.update("UPDATE conversation SET title=? WHERE id=?", branchTitle, target);
    // The branch's earliest checkpoint must precede its copied history.
    db.update(
        "UPDATE rp_world SET initial_json=? WHERE id=?",
        db.queryForObject("SELECT initial_json FROM rp_world WHERE id=?", String.class, worldId(conversation)),
        worldId(target));
    for (var row :
        db.queryForList(
            "SELECT id,sender,content FROM history WHERE conversation_id=? AND id<=? ORDER BY id",
            conversation,
            throughHistoryId)) {
      db.update(
          "INSERT INTO history(conversation_id,sender,content) VALUES(?,?,?)",
          target,
          row.get("sender"),
          row.get("content"));
      Long newId =
          db.queryForObject(
              "SELECT MAX(id) FROM history WHERE conversation_id=?", Long.class, target);
      db.update(
          "INSERT INTO rp_attachment(history_id,conversation_id,images_json) SELECT ?,?,images_json"
              + " FROM rp_attachment WHERE history_id=?",
          newId,
          target,
          row.get("id"));
      db.update(
          "INSERT INTO"
              + " rp_turn(world_id,conversation_id,history_id,state_json,user_text,assistant_text,status,images_json,bookmarked,created_at,before_state_json)"
              + " SELECT ?,?,?,state_json,user_text,assistant_text,status,images_json,bookmarked,created_at,before_state_json"
              + " FROM rp_turn WHERE conversation_id=? AND history_id=?",
          worldId(target),
          target,
          newId,
          conversation,
          row.get("id"));
    }
    return target;
  }

  public String proactive(String conversation) {
    RpState state = state(conversation);
    if (state.paused) return null;
    if (state.commitments.stream().anyMatch(c -> !c.contains("完成") && !c.contains("取消")))
      return "自然关心一个仍待履行的约定，先确认用户是否方便，不宣称已完成："
          + String.join(
              "；",
              state.commitments.stream()
                  .filter(c -> !c.contains("完成") && !c.contains("取消"))
                  .toList());
    if (!state.sharedActivity.isBlank()) return "围绕正在一起做的事情轻轻接话，不替用户推进动作：" + state.sharedActivity;
    if (!state.characterGoal.isBlank())
      return "以角色自己的生活计划自然分享一个小想法，不制造用户未参与的共同经历：" + state.characterGoal;
    return "安静地陪伴当前场景，最多一句自然问候或轻短动作；不要催用户回复，不引入新闻。";
  }

  private static String cut(String s, int max) {
    return s.length() > max ? s.substring(s.length() - max) : s;
  }
}
