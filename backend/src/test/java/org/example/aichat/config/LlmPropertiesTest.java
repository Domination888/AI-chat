package org.example.aichat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmPropertiesTest {

    @Test
    void utilityModelCanInheritConnectionAndOverrideOnlyModelName() {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl("https://api.example.com/v1");
        properties.setApiKey("main-key");
        properties.setModelName("main-model");
        properties.setUtilityModelName("fast-model");

        assertEquals("https://api.example.com/v1", properties.getEffectiveUtilityBaseUrl());
        assertEquals("main-key", properties.getEffectiveUtilityApiKey());
        assertEquals("fast-model", properties.getEffectiveUtilityModelName());
        assertEquals("disabled", properties.getEffectiveUtilityThinkingMode());
    }

    @Test
    void utilityModelSupportsIndependentConnectionWithoutApiKey() {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl("https://api.example.com/v1");
        properties.setApiKey("main-key");
        properties.setModelName("main-model");
        properties.setUtilityInheritConnection(false);
        properties.setUtilityBaseUrl("http://127.0.0.1:1234/v1");
        properties.setUtilityApiKey("");

        assertEquals("http://127.0.0.1:1234/v1", properties.getEffectiveUtilityBaseUrl());
        assertEquals("", properties.getEffectiveUtilityApiKey());
        assertEquals("main-model", properties.getEffectiveUtilityModelName());
    }
}
