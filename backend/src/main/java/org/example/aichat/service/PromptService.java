package org.example.aichat.service;

import java.util.Map;

public interface PromptService {

    String getSystemPrompt();

    String getPrompt(String name);

    String render(String templateName, Map<String, String> variables);
}