package org.example.aichat.controller;

import lombok.RequiredArgsConstructor;
import org.example.aichat.config.MemosProperties;
import org.example.aichat.service.memos.MemosClient;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/memories")
@RequiredArgsConstructor
public class MemoryController {

    private final MemosClient memosClient;
    private final MemosProperties memosProperties;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) Integer roleId) {
        String userId = memosProperties.getEffectiveUserId();
        List<MemosClient.ManagedMemoryItem> memories = memosClient.listManagedMemories(userId, roleId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("roleId", roleId);
        resp.put("items", memories);
        return ResponseEntity.ok(resp);
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> deleteBatch(@RequestParam(required = false) Integer roleId,
                                                          @RequestBody DeleteRequest request) {
        if (request == null || request.memoryIds() == null || request.memoryIds().isEmpty()) {
            throw new IllegalArgumentException("memoryIds 不能为空");
        }
        List<String> memoryIds = request.memoryIds().stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (memoryIds.isEmpty()) {
            throw new IllegalArgumentException("memoryIds 不能为空");
        }
        String userId = memosProperties.getEffectiveUserId();
        boolean deleted = memosClient.deleteManagedMemories(memoryIds, userId, roleId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", deleted);
        resp.put("memoryIds", memoryIds);
        resp.put("count", memoryIds.size());
        return ResponseEntity.ok(resp);
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String memoryId,
                                                      @RequestParam(required = false) Integer roleId) {
        if (!StringUtils.hasText(memoryId)) {
            throw new IllegalArgumentException("memoryId 不能为空");
        }
        String userId = memosProperties.getEffectiveUserId();
        boolean deleted = memosClient.deleteManagedMemories(List.of(memoryId), userId, roleId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", deleted);
        resp.put("memoryId", memoryId);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/{memoryId}/feedback")
    public ResponseEntity<Map<String, Object>> feedback(@PathVariable String memoryId,
                                                        @RequestParam(required = false) Integer roleId,
                                                        @RequestBody FeedbackRequest request) {
        if (!StringUtils.hasText(memoryId)) {
            throw new IllegalArgumentException("memoryId 不能为空");
        }
        if (request == null || !StringUtils.hasText(request.feedback())) {
            throw new IllegalArgumentException("feedback 不能为空");
        }
        String userId = memosProperties.getEffectiveUserId();
        boolean ok = memosClient.feedbackManaged(userId, roleId, request.feedback().trim(), memoryId, List.of());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", ok);
        resp.put("memoryId", memoryId);
        return ResponseEntity.ok(resp);
    }

    public record FeedbackRequest(String feedback) {}
    public record DeleteRequest(List<String> memoryIds) {}
}
