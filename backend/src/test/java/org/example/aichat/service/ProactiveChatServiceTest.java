package org.example.aichat.service;

import org.example.aichat.mapper.ProactiveCandidateMapper;
import org.example.aichat.util.LatencyLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ProactiveChatServiceTest {

    @Test
    void reconnectReusesEventBusAndReceivesEventsBufferedDuringDisconnect() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProactiveResearchService> researchProvider = mock(ObjectProvider.class);
        ProactiveChatService service = new ProactiveChatService(
                mock(SinkRegistry.class),
                mock(ChatService.class),
                mock(RoleCardService.class),
                mock(VoiceService.class),
                mock(LatencyLogger.class),
                mock(ProactiveCandidateMapper.class),
                mock(ConversationTopicStateService.class),
                researchProvider);
        try {
            Sinks.Many<ServerSentEvent<String>> original = service.getOrCreateProactiveSink("conversation");
            Disposable firstConnection = original.asFlux().subscribe();
            firstConnection.dispose();

            ServerSentEvent<String> buffered = ServerSentEvent.<String>builder()
                    .event("proactive")
                    .data("{\"mode\":\"researched_topic\"}")
                    .build();
            assertEquals(Sinks.EmitResult.OK, original.tryEmitNext(buffered));

            Sinks.Many<ServerSentEvent<String>> reconnected = service.getOrCreateProactiveSink("conversation");
            List<ServerSentEvent<String>> received = new CopyOnWriteArrayList<>();
            Disposable secondConnection = reconnected.asFlux().subscribe(received::add);

            assertSame(original, reconnected);
            assertTrue(received.stream().anyMatch(event -> "proactive".equals(event.event())));
            secondConnection.dispose();
        } finally {
            service.shutdown();
        }
    }
}
