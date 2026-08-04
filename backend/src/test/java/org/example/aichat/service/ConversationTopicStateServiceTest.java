package org.example.aichat.service;

import org.example.aichat.dto.History;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.example.aichat.service.ConversationTopicStateService.TopicState.CLOSED;
import static org.example.aichat.service.ConversationTopicStateService.TopicState.OPEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTopicStateServiceTest {

    @Test
    void emptyConversationNeedsANewTopic() {
        var result = ConversationTopicStateService.classifyByRules(List.of()).orElseThrow();
        assertEquals(CLOSED, result.state());
    }

    @Test
    void assistantQuestionKeepsCurrentTopicOpen() {
        var result = ConversationTopicStateService.classifyByRules(List.of(
                history("user", "我最近总是睡不好"),
                history("assistant", "最近是入睡困难，还是容易中途醒来？")
        )).orElseThrow();
        assertEquals(OPEN, result.state());
    }

    @Test
    void explicitUserClosureEndsTopic() {
        var result = ConversationTopicStateService.classifyByRules(List.of(
                history("assistant", "可以先按这个步骤试一下。"),
                history("user", "明白了，谢谢！")
        )).orElseThrow();
        assertEquals(CLOSED, result.state());
    }

    @Test
    void continuationRequestWinsOverGenericAcknowledgement() {
        var result = ConversationTopicStateService.classifyByRules(List.of(
                history("assistant", "先说到架构部分。"),
                history("user", "好的，继续详细说说")
        )).orElseThrow();
        assertEquals(OPEN, result.state());
    }

    @Test
    void completedAssistantStatementIsLeftForLlmClassification() {
        assertTrue(ConversationTopicStateService.classifyByRules(List.of(
                history("user", "Java 21 有什么变化？"),
                history("assistant", "主要包括虚拟线程、记录模式和分代 ZGC。")
        )).isEmpty());
    }

    @Test
    void assistantRestingClosureEndsTopicWithoutAnotherLlmCall() {
        var result = ConversationTopicStateService.classifyByRules(List.of(
                history("user", "好好休息吧"),
                history("assistant", "好孩子，既然累了，就先歇着吧。等明天醒了我们再慢慢聊。")
        )).orElseThrow();
        assertEquals(CLOSED, result.state());
    }

    private static History history(String sender, String content) {
        History history = new History();
        history.setSender(sender);
        history.setContent(content);
        return history;
    }
}
