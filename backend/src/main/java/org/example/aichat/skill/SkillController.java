package org.example.aichat.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 技能管理 REST API，供前端"设置 - 技能"界面增删改查与启用/停用。
 */
@Slf4j
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public List<SkillManifest> list() {
        return skillService.list();
    }

    @GetMapping("/{name}")
    public ResponseEntity<SkillManifest> get(@PathVariable String name) {
        return skillService.get(name).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public SkillManifest save(@RequestBody SkillManifest manifest) {
        return skillService.save(manifest);
    }

    @PutMapping("/{name}")
    public SkillManifest update(@PathVariable String name, @RequestBody SkillManifest manifest) {
        if (manifest.getName() == null || manifest.getName().isBlank()) {
            manifest.setName(name);
        }
        return skillService.save(manifest);
    }

    @PutMapping("/{name}/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable String name, @RequestParam boolean enabled) {
        boolean ok = skillService.toggle(name, enabled);
        return ok ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String name) {
        boolean ok = skillService.delete(name);
        return ok ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.badRequest().body(Map.of("ok", false, "message", "技能不存在"));
    }
}
