package org.example.aichat.mapper;

import org.apache.ibatis.annotations.*;
import org.example.aichat.dto.ProactiveCandidate;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ProactiveCandidateMapper {
    @Insert("""
            INSERT INTO proactive_candidate(user_id,topic,title,summary,reason,sources_json,score,fingerprint,status,expires_at)
            VALUES(#{userId},#{topic},#{title},#{summary},#{reason},#{sourcesJson},#{score},#{fingerprint},#{status},#{expiresAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ProactiveCandidate candidate);

    @Select("""
            SELECT * FROM proactive_candidate
            WHERE user_id=#{userId} AND status='pending' AND expires_at>NOW()
            ORDER BY score DESC,created_at ASC LIMIT 1
            """)
    ProactiveCandidate findBestPending(Integer userId);

    @Select("""
            SELECT COUNT(*) FROM proactive_candidate
            WHERE user_id=#{userId} AND fingerprint=#{fingerprint} AND created_at>=DATE_SUB(NOW(),INTERVAL 14 DAY)
            """)
    int countRecentFingerprint(@Param("userId") Integer userId, @Param("fingerprint") String fingerprint);

    @Update("""
            UPDATE proactive_candidate SET status='delivered',conversation_id=#{conversationId},delivered_at=NOW()
            WHERE id=#{id} AND status='pending'
            """)
    int markDelivered(@Param("id") Long id, @Param("conversationId") String conversationId);

    @Update("UPDATE proactive_candidate SET response_text=#{responseText} WHERE id=#{id}")
    int saveResponse(@Param("id") Long id, @Param("responseText") String responseText);

    @Update("UPDATE proactive_candidate SET feedback=#{feedback} WHERE id=#{id} AND user_id=#{userId} AND feedback IS NULL")
    int saveFeedback(@Param("id") Long id, @Param("userId") Integer userId, @Param("feedback") String feedback);

    @Select("SELECT * FROM proactive_candidate WHERE id=#{id}")
    ProactiveCandidate findById(Long id);

    @Select("SELECT MAX(delivered_at) FROM proactive_candidate WHERE user_id=#{userId} AND status='delivered'")
    LocalDateTime findLastDeliveredAt(Integer userId);

    @Select("SELECT MAX(created_at) FROM proactive_candidate WHERE user_id=#{userId}")
    LocalDateTime findLastCreatedAt(Integer userId);

    @Select("""
            SELECT * FROM proactive_candidate
            WHERE conversation_id=#{conversationId} AND status='delivered' ORDER BY delivered_at ASC
            """)
    List<ProactiveCandidate> findDeliveredByConversation(String conversationId);

    @Update("UPDATE proactive_candidate SET status='expired' WHERE status='pending' AND expires_at<=NOW()")
    int expirePending();
}
