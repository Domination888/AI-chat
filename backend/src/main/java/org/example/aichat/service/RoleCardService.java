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

    /** system.txt 破甲基底，所有角色通用。 */
    public String buildBasePrompt() {
        return promptService.getSystemPrompt();
    }

    /**
     * 按角色卡渲染【角色层】；无角色时返回空串。
     */
    public String buildRoleLayerPrompt(Integer roleId) {
        RoleCard role = roleId == null ? null : roleCardMapper.findById(roleId);
        if (role == null) {
            return "";
        }
        Map<String, String> vars = buildVarsFromRole(role);
        return promptService.render("role_system.txt", vars);
    }

    /**
     * 按角色卡渲染系统 Prompt（预览/脚本用，不含【能力层】技能段）。
     * 完整对话 Prompt 由 ChatServiceImpl 组装：base → 能力层 → 角色层 → 记忆。
     */
    public String buildSystemPrompt(Integer roleId) {
        String base = buildBasePrompt();
        String roleLayer = buildRoleLayerPrompt(roleId);
        if (roleLayer.isEmpty()) {
            return base;
        }
        return base + "\n\n" + roleLayer;
    }

    private Map<String, String> buildVarsFromRole(RoleCard role) {
        Map<String, String> vars = new HashMap<>();
        vars.put("name", nullToEmpty(role.getName()));
        vars.put("aka", "");
        vars.put("persona", renderDbPersona(role));
        vars.put("relationships", "");
        vars.put("examples", nullToEmpty(role.getExampleDialogue()));
        vars.put("mantra", "");
        // vars.put("intimacy_profile", "");
        vars.put("sister_profiles", "");
        vars.put("wardrobe", "");
        vars.put("dragon_bubble", "");
        vars.put("nsfw", "");

        if (!StringUtils.hasText(role.getPersonaCardPath())) {
            return vars;
        }

        try {
            String json = readPersonaJson(role.getPersonaCardPath());
            if (!StringUtils.hasText(json)) {
                return vars;
            }
            JsonNode root = objectMapper.readTree(json);

            overwriteIfText(root, "name", vars, "name");

            String akaText = renderAka(root);
            if (StringUtils.hasText(akaText)) {
                vars.put("aka", akaText);
            }

            String personaBlock = renderPersona(root);
            if (StringUtils.hasText(personaBlock)) {
                vars.put("persona", personaBlock);
            }

            String relationshipsBlock = renderRelationships(root);
            if (StringUtils.hasText(relationshipsBlock)) {
                vars.put("relationships", relationshipsBlock);
            }

            String dialogueBlock = renderExamples(root);
            if (StringUtils.hasText(dialogueBlock)) {
                vars.put("examples", dialogueBlock);
            }

            overwriteIfText(root, "mantra", vars, "mantra");

            // overwriteIfText(root, "intimacy_profile", vars, "intimacy_profile");

            overwriteIfText(root, "sister_profiles", vars, "sister_profiles");
            overwriteIfText(root, "wardrobe", vars, "wardrobe");
            overwriteIfText(root, "dragon_bubble", vars, "dragon_bubble");

            String nsfwBlock = renderNsfw(root);
            if (StringUtils.hasText(nsfwBlock)) {
                vars.put("nsfw", nsfwBlock);
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
     * persona_card 主体块：
     *   persona、relationships、examples、mantra，以及可选的 intimacy_profile、
     *   sister_profiles、wardrobe、dragon_bubble、nsfw（来自酒馆卡蒸馏）。
     * 这里把 persona 对象渲染成一段稳定文本，避免模板和 JSON 字段互相嵌套太深。
     */
    private String renderPersona(JsonNode root) {
        JsonNode persona = root.get("persona");
        if (persona != null && persona.isTextual()) {
            return persona.asText("").trim();
        }
        if (persona != null && persona.isObject()) {
            StringBuilder sb = new StringBuilder();
            appendTextLine(sb, persona.get("identity"), "身份");
            appendTextLine(sb, persona.get("origin"), "来历");
            appendTextLine(sb, persona.get("appearance"), "外貌");
            appendArrayAsBullets(sb, persona.get("traits"), "性格与日常");
            appendTextLine(sb, persona.get("speech"), "说话方式");
            appendArrayAsBullets(sb, persona.get("rules"), "扮演规则");
            return sb.toString().trim();
        }

        return "";
    }

    private String renderNsfw(JsonNode root) {
        JsonNode nsfw = root.get("nsfw");
        if (nsfw == null || !nsfw.isObject()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendTextLine(sb, nsfw.get("profile"), "档案");
        appendTextLine(sb, nsfw.get("dialogue_full"), "完整语料");
        appendArrayAsBullets(sb, nsfw.get("rules"), "演绎规则");
        JsonNode samples = nsfw.get("dialogue_samples");
        if (samples != null && samples.isArray() && !samples.isEmpty()) {
            sb.append("台词参考（可化用，勿整段照搬）：\n");
            for (JsonNode item : samples) {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    sb.append("- ").append(text).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    private String renderRelationships(JsonNode root) {
        JsonNode rel = root.get("relationships");
        if (rel == null || !rel.isArray() || rel.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : rel) {
            String who = item.path("who").asText("").trim();
            String how = item.path("how").asText("").trim();
            if (!who.isEmpty() && !how.isEmpty()) {
                sb.append("- ").append(who).append("：").append(how).append("\n");
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
     * 渲染 examples 数组为 "User: ...\n{name}: ..." 形式的多轮样例。
     */
    private String renderExamples(JsonNode root) {
        JsonNode arr = root.get("examples");
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

    private String renderDbPersona(RoleCard role) {
        StringBuilder sb = new StringBuilder();
        appendPlainLine(sb, role.getProfile(), "身份");
        appendPlainLine(sb, role.getBackground(), "来历");
        appendPlainLine(sb, role.getPersonality(), "性格与规则");
        return sb.toString().trim();
    }

    private void appendPlainLine(StringBuilder sb, String text, String title) {
        if (StringUtils.hasText(text)) {
            sb.append(title).append("：").append(text.trim()).append("\n");
        }
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
