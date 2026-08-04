package org.example.aichat.controller;

import lombok.RequiredArgsConstructor;
import org.example.aichat.dto.ProactiveCandidate;
import org.example.aichat.dto.ProactiveInterest;
import org.example.aichat.service.ProactiveResearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/proactive-research")
@RequiredArgsConstructor
public class ProactiveResearchController {
    private final ProactiveResearchService service;

    @GetMapping("/interests")
    public List<ProactiveInterest> interests(@RequestParam Integer userId) {
        return service.listInterests(userId);
    }

    @PostMapping("/interests")
    public ResponseEntity<?> addInterest(@RequestBody Map<String, Object> body) {
        Integer userId = integer(body.get("userId"));
        String topic = body.get("topic") == null ? "" : body.get("topic").toString();
        if (userId == null || topic.isBlank()) return ResponseEntity.badRequest().body(Map.of("ok", false));
        return ResponseEntity.ok(service.addManualInterest(userId, topic));
    }

    @PatchMapping("/interests/{id}")
    public ResponseEntity<?> updateInterest(@PathVariable Long id, @RequestBody ProactiveInterest interest) {
        interest.setId(id);
        return ResponseEntity.ok(Map.of("ok", service.updateInterest(interest)));
    }

    @DeleteMapping("/interests/{id}")
    public ResponseEntity<?> deleteInterest(@PathVariable Long id, @RequestParam Integer userId) {
        return ResponseEntity.ok(Map.of("ok", service.deleteInterest(userId, id)));
    }

    @PostMapping("/candidates/{id}/feedback")
    public ResponseEntity<?> feedback(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer userId = integer(body.get("userId"));
        String feedback = body.get("feedback") == null ? "" : body.get("feedback").toString();
        return ResponseEntity.ok(Map.of("ok", userId != null && service.feedback(userId, id, feedback)));
    }

    @GetMapping("/conversation/{conversationId}")
    public List<ProactiveCandidate> delivered(@PathVariable String conversationId) {
        return service.deliveredForConversation(conversationId);
    }

    @PostMapping("/actions/run-now")
    public ResponseEntity<?> runNow(@RequestBody Map<String, Object> body) {
        Integer userId = integer(body.get("userId"));
        return ResponseEntity.ok(Map.of("ok", userId != null && service.runNow(userId)));
    }

    @PostMapping("/actions/refresh-interests")
    public ResponseEntity<?> refresh(@RequestBody Map<String, Object> body) {
        Integer userId = integer(body.get("userId"));
        if (userId == null) return ResponseEntity.badRequest().body(Map.of("ok", false));
        service.refreshInterests(userId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private Integer integer(Object value) {
        try { return value == null ? null : Integer.parseInt(value.toString()); }
        catch (Exception e) { return null; }
    }
}
