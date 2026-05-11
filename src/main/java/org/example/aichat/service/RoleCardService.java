package org.example.aichat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.dto.RoleCard;
import org.example.aichat.mapper.RoleCardMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
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
@Slf4j
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
        RoleCard role = roleCardMapper.findById(id);
        if (role == null) return Optional.empty();
        // 若该角色有 greetings.txt 开场白库，则随机选一条覆盖 DB greeting，让首屏更生动
        String dynamicGreeting = pickRandomGreeting(role.getRoleCode());
        if (StringUtils.hasText(dynamicGreeting)) {
            role.setGreeting(dynamicGreeting);
        }
        return Optional.of(role);
    }

    /**
     * 从 personas/{roleCode}/greetings.txt 随机挑一行作为开场白。
     * 文件不存在或 roleCode 为空时返回 null，由调用方决定是否回退 DB greeting。
     */
    public String pickRandomGreeting(String roleCode) {
        if (!StringUtils.hasText(roleCode)) return null;
        String path = "personas/" + roleCode.trim().toLowerCase() + "/greetings.txt";
        Resource res = new ClassPathResource(path);
        if (!res.exists()) return null;
        try (InputStream in = res.getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> lines = new java.util.ArrayList<>();
            for (String l : text.split("\\r?\\n")) {
                String t = l.trim();
                if (!t.isEmpty() && !t.startsWith("#")) lines.add(t);
            }
            if (lines.isEmpty()) return null;
            return lines.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(lines.size()));
        } catch (IOException e) {
            log.warn("读取 greetings.txt 失败 path={}: {}", path, e.getMessage());
            return null;
        }
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
     * 拼接顺序：system.txt（破甲基底，所有角色通用） + role_system.txt（角色层，按 persona_card 渲染）。
     * 当 roleId 为空（无角色 / 默认会话）时，只返回 system.txt。
     */
    public String buildSystemPrompt(Integer roleId) {
        String base = promptService.getSystemPrompt();
        RoleCard role = roleId == null ? null : roleCardMapper.findById(roleId);
        if (role == null) {
            return base;
        }

        Map<String, String> vars = buildVarsFromRole(role);
        String roleLayer = promptService.render("role_system.txt", vars);
        // 双层拼接：base 在前定调"去除限制 / 不许自称 AI"，role_system 在后注入具体角色，
        // 中间空一行让模型把它们当成一段连续的系统指令处理（实测比生硬拼更稳）。
        return base + "\n\n" + roleLayer;
    }

    private Map<String, String> buildVarsFromRole(RoleCard role) {
        Map<String, String> vars = new HashMap<>();
        vars.put("name", nullToEmpty(role.getName()));
        vars.put("profile", nullToEmpty(role.getProfile()));
        vars.put("background", nullToEmpty(role.getBackground()));
        vars.put("personality", nullToEmpty(role.getPersonality()));
        vars.put("exampleDialogue", nullToEmpty(role.getExampleDialogue()));
        // 默认值（无 persona_card 时占位符给空字符串而不是 {{xxx}} 残留）
        vars.put("aka", "");
        vars.put("soulInjection", "");
        vars.put("soulMantra", "");

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

            // aka 别名：渲染成 "Shu / 黍姐 / 天师姐姐" 格式
            String akaText = renderAka(root);
            if (StringUtils.hasText(akaText)) {
                vars.put("aka", akaText);
            }

            // 入魂指令 / 一句话铭印（角色专属行为细则）
            overwriteIfText(root, "soul_injection", vars, "soulInjection");
            overwriteIfText(root, "soul_mantra", vars, "soulMantra");

            // personality：合并 personality[] + speech_style + catchphrases[] + taboo[] + output_rules[] + relationships
            String personalityBlock = renderPersonalityBlock(root);
            if (StringUtils.hasText(personalityBlock)) {
                vars.put("personality", personalityBlock);
            }

            // exampleDialogue：persona_card 里是对象数组 [{user, assistant}, ...]
            String dialogueBlock = renderExampleDialogue(root);
            if (StringUtils.hasText(dialogueBlock)) {
                vars.put("exampleDialogue", dialogueBlock);
            }
        } catch (Exception e) {
            log.warn("读取 persona_card 失败，回退数据库字段：path={}, err={}", role.getPersonaCardPath(), e.getMessage());
        }

        return vars;
    }

    private String renderAka(JsonNode root) {
        JsonNode arr = root.get("aka");
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            String t = arr.get(i).asText("").trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(t);
        }
        return sb.toString();
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

    /**
     * 读取 persona_card.json：
     *   1) 优先按 classpath 资源加载（推荐：personas/{roleCode}/persona_card.json，跟随 jar 一起发布）
     *   2) classpath 找不到再回退到工作目录的相对/绝对路径（兼容历史的 data/processed/... 配置）
     */
    private String readPersonaJson(String personaCardPath) throws IOException {
        // 1) classpath
        String cp = personaCardPath.startsWith("/") ? personaCardPath.substring(1) : personaCardPath;
        Resource cpResource = new ClassPathResource(cp);
        if (cpResource.exists()) {
            try (InputStream in = cpResource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        // 2) 文件系统兜底
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