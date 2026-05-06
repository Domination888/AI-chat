package org.example.aichat.service;

import lombok.RequiredArgsConstructor;
import org.example.aichat.dto.RoleCard;
import org.example.aichat.mapper.RoleCardMapper;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 角色卡领域服务：CRUD + 系统 Prompt 渲染。
 * 阶段 3 起 ChatService 会调用 {@link #buildSystemPrompt(Integer)} 注入到 LLM。
 */
@Service
@RequiredArgsConstructor
public class RoleCardService {

    private final RoleCardMapper roleCardMapper;
    private final PromptService promptService;

    public List<RoleCard> listAll() {
        return roleCardMapper.findAll();
    }

    public Optional<RoleCard> findById(Integer id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(roleCardMapper.findById(id));
    }

    public RoleCard create(RoleCard roleCard) {
        validate(roleCard);
        roleCardMapper.insert(roleCard);
        return roleCard;
    }

    public RoleCard update(Integer id, RoleCard roleCard) {
        RoleCard exists = roleCardMapper.findById(id);
        if (exists == null) {
            throw new IllegalArgumentException("角色不存在: " + id);
        }
        roleCard.setId(id);
        roleCardMapper.update(roleCard);
        return roleCardMapper.findById(id);
    }

    public void delete(Integer id) {
        roleCardMapper.deleteById(id);
    }

    /**
     * 按角色卡渲染系统 Prompt。如果角色不存在或字段缺失，会回退到默认 system.txt。
     */
    public String buildSystemPrompt(Integer roleId) {
        RoleCard role = roleId == null ? null : roleCardMapper.findById(roleId);
        if (role == null) {
            return promptService.getSystemPrompt();
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("name", nullToEmpty(role.getName()));
        vars.put("profile", nullToEmpty(role.getProfile()));
        vars.put("background", nullToEmpty(role.getBackground()));
        vars.put("personality", nullToEmpty(role.getPersonality()));
        vars.put("exampleDialogue", nullToEmpty(role.getExampleDialogue()));
        return promptService.render("role_system.txt", vars);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private void validate(RoleCard roleCard) {
        if (roleCard == null || roleCard.getName() == null || roleCard.getName().isBlank()) {
            throw new IllegalArgumentException("角色名称不能为空");
        }
    }
}