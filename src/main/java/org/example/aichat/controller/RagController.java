package org.example.aichat.controller;

import lombok.RequiredArgsConstructor;
import org.example.aichat.service.RagService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    @GetMapping("/reload")
    public Map<String, Object> reload() {
        int chunkCount = ragService.reload();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("chunkCount", chunkCount);
        return result;
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String query,
                                      @RequestParam(defaultValue = "3") int topK) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("query 不能为空");
        }
        String context = ragService.retrieveContext(query, topK);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("topK", topK);
        result.put("context", context);
        result.put("hit", StringUtils.hasText(context));
        return result;
    }
}
