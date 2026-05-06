package org.example.aichat.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;
import org.example.aichat.dto.Conversation;

import java.util.List;

@Mapper
public interface ConversationMapper {

    @Insert("INSERT IGNORE INTO conversation(id, user_id, role_id, title) VALUES(#{id}, #{userId}, #{roleId}, #{title})")
    void insertOrUpdate(Conversation conversation);

    @Select("SELECT * FROM conversation WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<Conversation> findByUserId(Integer userId);

    @Select("SELECT * FROM conversation WHERE id = #{id}")
    Conversation findById(String id);

    @Delete("DELETE FROM conversation WHERE id = #{id}")
    void deleteById(String id);
}