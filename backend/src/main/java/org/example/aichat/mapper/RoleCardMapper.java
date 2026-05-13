package org.example.aichat.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.example.aichat.dto.RoleCard;

import java.util.List;

@Mapper
public interface RoleCardMapper {
    List<RoleCard> findAll();
    RoleCard findById(Integer id);
    int insert(RoleCard roleCard);
    int update(RoleCard roleCard);
    int deleteById(Integer id);
}
