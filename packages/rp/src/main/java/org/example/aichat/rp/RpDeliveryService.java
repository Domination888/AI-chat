package org.example.aichat.rp;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Playback receipts are distinct from generated text: interrupted audio is never marked heard. */
@Service
@RequiredArgsConstructor
public class RpDeliveryService {
  private final JdbcTemplate db;

  public void sentence(String conversation, String stream, int index, String text) {
    SqlDialect.of(db).insertIgnore(db, "rp_audio_sentence", "stream_id,conversation_id,idx,text", "?,?,?,?",
        stream,
        conversation,
        index,
        text);
  }

  public void heard(String conversation, String stream, int index) {
    db.update(
        "UPDATE rp_audio_sentence SET heard=TRUE WHERE conversation_id=? AND stream_id=? AND idx=?",
        conversation,
        stream,
        index);
  }

  public String context(String conversation) {
    var rows =
        db.queryForList(
            "SELECT text,heard FROM rp_audio_sentence WHERE conversation_id=? ORDER BY created_at"
                + " DESC,idx DESC LIMIT 12",
            conversation);
    if (rows.isEmpty()) return "";
    Collections.reverse(rows);
    StringBuilder out = new StringBuilder("\n【最近语音播放记录】未确认播放不代表用户没看见文字；不要假定对方已听完。\n");
    for (var row : rows)
      out.append(Boolean.TRUE.equals(row.get("heard")) ? "已播放：" : "未确认播放：")
          .append(row.get("text"))
          .append("\n");
    return out.toString();
  }
}
