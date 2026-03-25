package org.example.aichat.service.impl;

import org.example.aichat.dto.ChatRequest;
import org.example.aichat.service.GuardrailService;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class GuardrailServiceImpl implements GuardrailService {

    private static final Set<String> SENSITIVE_WORDS = Set.of(
            "暴力",
            "毒品",
            "色情",
            "政治敏感",
            "sex"
    );

    @Override
    public boolean checkInput(ChatRequest request) {
        String text = request.getMessage();
        if (text == null) return false;
        // 获取用户输入并转换为小写以确保大小写不敏感
        String inputText = text.toLowerCase();
        // 使用正则表达式分割输入文本为单词
        String[] words = inputText.split("\\W+");
        // 遍历所有单词，检查是否存在敏感词
        for (String word : words) {
            if (SENSITIVE_WORDS.contains(word)) {
                return true;
            }
        }
        return false;

    }

    @Override
    public void checkOutput(String content) {

        for (String word : SENSITIVE_WORDS) {
            if (content.contains(word)) {
                throw new RuntimeException("输出包含违规内容");
            }
        }
    }
}