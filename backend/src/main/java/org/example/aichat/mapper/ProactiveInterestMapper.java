package org.example.aichat.mapper;

import org.apache.ibatis.annotations.*;
import org.example.aichat.dto.ProactiveInterest;

import java.util.List;

@Mapper
public interface ProactiveInterestMapper {
    @Select("""
            SELECT * FROM proactive_interest WHERE user_id=#{userId}
            ORDER BY enabled DESC, weight DESC, updated_at DESC
            """)
    List<ProactiveInterest> findByUserId(Integer userId);

    @Select("""
            SELECT * FROM proactive_interest
            WHERE user_id=#{userId} AND enabled=1 AND (muted_until IS NULL OR muted_until < NOW())
            ORDER BY weight DESC, updated_at DESC LIMIT #{limit}
            """)
    List<ProactiveInterest> findActive(@Param("userId") Integer userId, @Param("limit") int limit);

    @Insert("""
            INSERT INTO proactive_interest(user_id,topic,source,weight,enabled,evidence,last_inferred_at)
            VALUES(#{userId},#{topic},#{source},#{weight},#{enabled},#{evidence},#{lastInferredAt})
            ON DUPLICATE KEY UPDATE
              evidence=IF(source='manual' AND VALUES(source)<>'manual',evidence,VALUES(evidence)),
              weight=IF(source='manual' AND VALUES(source)<>'manual',weight,
                        IF(VALUES(source)='manual',VALUES(weight),GREATEST(weight,VALUES(weight)))),
              last_inferred_at=IF(source='manual' AND VALUES(source)<>'manual',last_inferred_at,VALUES(last_inferred_at)),
              source=IF(VALUES(source)='manual','manual',source),updated_at=CURRENT_TIMESTAMP
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(ProactiveInterest interest);

    @Update("""
            UPDATE proactive_interest SET topic=#{topic}, enabled=#{enabled}, weight=#{weight},
              muted_until=#{mutedUntil}, updated_at=CURRENT_TIMESTAMP
            WHERE id=#{id} AND user_id=#{userId}
            """)
    int update(ProactiveInterest interest);

    @Update("UPDATE proactive_interest SET weight=LEAST(1.0,weight+0.15),muted_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE user_id=#{userId} AND topic=#{topic}")
    int markInterested(@Param("userId") Integer userId, @Param("topic") String topic);

    @Update("UPDATE proactive_interest SET weight=GREATEST(0.05,weight-0.40),muted_until=DATE_ADD(NOW(),INTERVAL 30 DAY),updated_at=CURRENT_TIMESTAMP WHERE user_id=#{userId} AND topic=#{topic}")
    int markLessLike(@Param("userId") Integer userId, @Param("topic") String topic);

    @Delete("DELETE FROM proactive_interest WHERE id=#{id} AND user_id=#{userId}")
    int delete(@Param("id") Long id, @Param("userId") Integer userId);
}
