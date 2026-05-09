package org.example.aichat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.aichat.dto.RoleCard;
import org.example.aichat.mapper.RoleCardMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 角色卡领域服务：CRUD + 系统 Prompt 渲染。
 * 优先读取 role_card.persona_card_path 指向的 JSON 人设卡，缺失时回退数据库字段。
 */
@Service
@RequiredArgsConstructor
public class RoleCardService {

    private final RoleCardMapper roleCardMapper;
    private final PromptService promptService;
    private final ObjectMapper objectMapper;

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
     * 按角色卡渲染系统 Prompt。
     */
    public String buildSystemPrompt(Integer roleId) {
        RoleCard role = roleId == null ? null : roleCardMapper.findById(roleId);
        if (role == null) {
            return promptService.getSystemPrompt();
        }

        Map<String, String> vars = buildVarsFromRole(role);
        return promptService.render("role_system.txt", vars);
    }

    private Map<String, String> buildVarsFromRole(RoleCard role) {
        Map<String, String> vars = new HashMap<>();
        vars.put("name", nullToEmpty(role.getName()));
        vars.put("profile", nullToEmpty(role.getProfile()));
        vars.put("background", nullToEmpty(role.getBackground()));
        vars.put("personality", nullToEmpty(role.getPersonality()));
        vars.put("exampleDialogue", nullToEmpty(role.getExampleDialogue()));

        if (!StringUtils.hasText(role.getPersonaCardPath())) {
            return vars;
        }

        try {
            String json = readPersonaJson(role.getPersonaCardPath());
            if (!StringUtils.hasText(json)) {
                return vars;
            }
            JsonNode root = objectMapper.readTree(json);

            // name / profile / background：persona_card 优先覆盖数据库字段
            overwriteIfText(root, "name", vars, "name");
            overwriteIfText(root, "identity", vars, "profile");
            overwriteIfText(root, "background_oneliner", vars, "background");

            // personality：合并 personality[] + speech_style + catchphrases[] + taboo[] + output_rules[]
            String personalityBlock = renderPersonalityBlock(root);
            if (StringUtils.hasText(personalityBlock)) {
                vars.put("personality", personalityBlock);
            }

            // exampleDialogue：persona_card 里是对象数组 [{user, assistant}, ...]
            String dialogueBlock = renderExampleDialogue(root);
            if (StringUtils.hasText(dialogueBlock)) {
                vars.put("exampleDialogue", dialogueBlock);
            }
        } catch (Exception ignored) {
            // 读取失败时保持数据库字段兜底，避免影响主流程
        }

        return vars;
    }

    /**
     * 把 persona_card.json 的多个人格相关字段渲染成一段结构化文本，注入到 {{personality}} 占位符。
     * 合并顺序（有的字段缺失就跳过）：
     *   - personality[] 核心性格标签
     *   - speech_style 说话风格
     *   - catchphrases[] 口癖
     *   - taboo[] 禁忌
     *   - output_rules[] 输出规则
     *   - relationships[] 关系（who/how）
     */
    private String renderPersonalityBlock(JsonNode root) {
        StringBuilder sb = new StringBuilder();

        appendArrayAsBullets(sb, root.get("personality"), "核心性格");
        appendTextLine(sb, root.get("speech_style"), "说话风格");
        appendArrayAsBullets(sb, root.get("catchphrases"), "口癖");
        appendArrayAsBullets(sb, root.get("taboo"), "禁忌");
        appendArrayAsBullets(sb, root.get("output_rules"), "输出规则");

        JsonNode rel = root.get("relationships");
        if (rel != null && rel.isArray() && !rel.isEmpty()) {
            sb.append("关系：\n");
            for (JsonNode item : rel) {
                String who = item.path("who").asText("").trim();
                String how = item.path("how").asText("").trim();
                if (!who.isEmpty() && !how.isEmpty()) {
                    sb.append("- ").append(who).append("：").append(how).append("\n");
                }
            }
        }

        return sb.toString().trim();
    }

    private void appendTextLine(StringBuilder sb, JsonNode node, String title) {
        if (node != null && node.isTextual() && StringUtils.hasText(node.asText())) {
            sb.append(title).append("：").append(node.asText().trim()).append("\n");
        }
    }

    private void appendArrayAsBullets(StringBuilder sb, JsonNode node, String title) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return;
        }
        sb.append(title).append("：\n");
        for (JsonNode item : node) {
            String text = item.asText("").trim();
            if (!text.isEmpty()) {
                sb.append("- ").append(text).append("\n");
            }
        }
    }

    /**
     * 渲染 example_dialogue 数组为 "User: ...\n{name}: ..." 形式的多轮样例。
     */
    private String renderExampleDialogue(JsonNode root) {
        JsonNode arr = root.get("example_dialogue");
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return "";
        }
        String roleName = root.path("name").asText("AI");
        StringBuilder sb = new StringBuilder();
        for (JsonNode turn : arr) {
            String u = turn.path("user").asText("").trim();
            String a = turn.path("assistant").asText("").trim();
            if (u.isEmpty() && a.isEmpty()) continue;
            if (!u.isEmpty()) sb.append("User: ").append(u).append("\n");
            if (!a.isEmpty()) sb.append(roleName).append(": ").append(a).append("\n");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String readPersonaJson(String personaCardPath) throws IOException {
        Path path = Path.of(personaCardPath);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(personaCardPath);
        }
        if (!Files.exists(path)) {
            return "";
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private void overwriteIfText(JsonNode root, String field, Map<String, String> vars, String targetKey) {
        JsonNode node = root.get(field);
        if (node != null && node.isTextual() && StringUtils.hasText(node.asText())) {
            vars.put(targetKey, node.asText().trim());
        }
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