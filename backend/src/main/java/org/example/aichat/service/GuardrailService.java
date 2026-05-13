package org.example.aichat.service;

import org.example.aichat.dto.ChatRequest;

public interface GuardrailService {

    boolean checkInput(ChatRequest request);

    void checkOutput(String content);
}