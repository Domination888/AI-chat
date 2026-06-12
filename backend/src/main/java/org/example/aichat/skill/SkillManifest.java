package org.example.aichat.skill;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个技能（Skill）的清单，对应 config/skills/&lt;dir&gt;/SKILL.md。
 *
 * SKILL.md 采用 YAML frontmatter + Markdown 正文：
 * <pre>
 * ---
 * name: web-research
 * description: 进行多步联网检索并归纳出处
 * enabled: true
 * version: 1.0.0
 * mcpTools:
 *   - webSearch
 * ---
 * （正文：技能使用方法 / 步骤 / 注意事项，会按需注入到系统提示）
 * </pre>
 */
@Data
public class SkillManifest {

    /** 技能名（同时作为目录名的来源） */
    private String name;

    /** 一句话说明：模型据此判断何时使用该技能 */
    private String description = "";

    /** 是否启用 */
    private boolean enabled = true;

    private String version = "1.0.0";

    /** 该技能建议/绑定使用的 MCP 工具名 */
    private List<String> mcpTools = new ArrayList<>();

    /** 正文：技能的详细指令（Markdown） */
    private String instructions = "";

    /** 磁盘上的目录名（只读，由后端填充） */
    private String dirName;
}
