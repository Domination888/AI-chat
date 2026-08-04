package org.example.aichat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LlmThinkingConfigurerTest {

    @Test
    void sendsThinkingControlsAndReplaysReasoningContentForToolContinuation() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String sse = "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\","
                    + "\"created\":1,\"model\":\"deepseek-test\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"role\":\"assistant\",\"content\":\"完成\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\","
                    + "\"created\":1,\"model\":\"deepseek-test\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            var builder = OpenAiStreamingChatModel.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .apiKey("test-key")
                    .modelName("deepseek-test");
            LlmThinkingConfigurer.configure(builder, "enabled", "max");
            StreamingChatModel model = builder.build();

            ToolExecutionRequest toolCall = ToolExecutionRequest.builder()
                    .id("call-1")
                    .name("weather")
                    .arguments("{\"city\":\"杭州\"}")
                    .build();
            AiMessage assistantToolCall = AiMessage.builder()
                    .thinking("先查询天气")
                    .toolExecutionRequests(List.of(toolCall))
                    .build();
            var request = dev.langchain4j.model.chat.request.ChatRequest.builder()
                    .messages(List.of(
                            UserMessage.from("杭州天气如何？"),
                            assistantToolCall,
                            ToolExecutionResultMessage.from(toolCall, "晴，25℃")))
                    .build();

            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            model.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    completed.countDown();
                }

                @Override
                public void onError(Throwable throwable) {
                    error.set(throwable);
                    completed.countDown();
                }
            });

            assertTrue(completed.await(5, TimeUnit.SECONDS), "流式调用未完成");
            assertNull(error.get(), () -> "流式调用失败: " + error.get());
            JsonNode json = new ObjectMapper().readTree(requestBody.get());
            assertEquals("enabled", json.at("/thinking/type").asText());
            assertEquals("max", json.path("reasoning_effort").asText());
            assertEquals("先查询天气", json.at("/messages/1/reasoning_content").asText());
            assertEquals("call-1", json.at("/messages/1/tool_calls/0/id").asText());
        } finally {
            server.stop(0);
        }
    }
}
