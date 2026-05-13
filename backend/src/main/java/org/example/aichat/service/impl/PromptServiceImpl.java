package org.example.aichat.service.impl;

import org.example.aichat.service.PromptService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PromptServiceImpl implements PromptService {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Override
    public String getSystemPrompt() {
        return getPrompt("system.txt");
    }

    @Override
    public String getPrompt(String name) {
        return cache.computeIfAbsent(name, this::loadFromResources);
    }

    private String loadFromResources(String fileName) {
        try (InputStream inputStream =
                     new ClassPathResource("prompts/" + fileName).getInputStream()) {

            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

        } catch (IOException e) {
            throw new RuntimeException("加载 Prompt 失败: " + fileName, e);
        }
    }

    @Override
    public String render(String templateName, Map<String, String> variables) {

        String template = getPrompt(templateName);

        for (Map.Entry<String, String> entry : variables.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }

        return template;
    }
}