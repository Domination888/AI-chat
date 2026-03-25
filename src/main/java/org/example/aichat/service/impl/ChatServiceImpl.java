package org.example.aichat.service.impl;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.dto.ChatRequest;
import org.example.aichat.dto.Memory;
import org.example.aichat.service.GuardrailService;
import org.example.aichat.service.PromptService;
import org.example.aichat.service.ChatService;
import org.example.aichat.service.MemoryService;
import org.example.aichat.service.RagService;
import org.example.aichat.mapper.ConversationMapper;
import org.example.aichat.dto.Conversation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
// ... other imports

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {
    @Resource
    private StreamingChatModel streamingChatModel;
    @Resource
    private ChatModel chatModel;
    @Resource
    private GuardrailService guardrailService;
    @Resource
    private PromptService promptService;
    @Resource
    private MemoryService memoryService;
    @Resource
    private RagService ragService;
    @Resource
    private ChatMemoryStore chatMemoryStore;
    @Resource(name = "zhipuMcpClient")
    private McpClient zhipuMcpClient;
    @Autowired(required = false)
    @Qualifier("localMcpClient")
    private McpClient localMcpClient;
    @Resource
    private ConversationMapper conversationMapper;


    /** ChatMemory 窗口大小：保留最近的消息条数 */
    private static final int MAX_MEMORY_MESSAGES = 20;
    /** 本地工具调用最大轮数 */
    private static final int MAX_TOOL_ROUNDS = 3;

    @Override
    public Flux<String> chatStream(ChatRequest request) {

        if (guardrailService.checkInput(request)) {
            return Flux.just("输入违规");
        }

        String conversationId = request.getConversationId();
        // 0. 保存或更新会话表
        try {
            Integer uid = Integer.parseInt(request.getUserId());
            Conversation conv = new Conversation();
            conv.setId(conversationId);
            conv.setUserId(uid);
            String title = "新对话";
            if (request.getMessage() != null && request.getMessage().length() > 0) {
                title = request.getMessage().length() > 15 ? request.getMessage().substring(0, 15) + "..." : request.getMessage();
            }
            conv.setTitle(title);
            conversationMapper.insertOrUpdate(conv);
        } catch (Exception e) {
            log.error("保存会话失败", e);
        }
        // 1️⃣ 获取长期记忆摘要
        Memory memory = memoryService.findByConversationId(conversationId);
        String memorySummary = (memory != null) ? memory.getSummary() : null;

        // 2️⃣ 构建系统指令文本（基础 system prompt + 长期记忆摘要）
        String systemPromptText = promptService.getSystemPrompt();
        if (memorySummary != null && !memorySummary.isEmpty()) {
            systemPromptText += "\n\n【长期记忆】\n" + memorySummary;
        }

        // 3️⃣ 创建 ChatMemory —— 自动从 history 表加载历史消息
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .id(conversationId)
                .maxMessages(MAX_MEMORY_MESSAGES)
                .chatMemoryStore(chatMemoryStore)
                .build();

        // 4️⃣ 构建用户消息
        String textToSave = (request.getMessage() != null && !request.getMessage().trim().isEmpty()) 
                ? request.getMessage() : "[图片上传]";
        
        List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
        contents.add(dev.langchain4j.data.message.TextContent.from(textToSave));
        
        boolean hasImages = false;
        if (request.getImages() != null && !request.getImages().isEmpty()) {    
            hasImages = true;
            for (String base64 : request.getImages()) {
                String mimeType = "image/jpeg";
                if (base64.startsWith("data:")) {
                    int commaIndex = base64.indexOf(",");
                    if (commaIndex != -1) {
                        if (base64.startsWith("data:image/png")) mimeType = "image/png";
                        else if (base64.startsWith("data:image/gif")) mimeType = "image/gif";
                        else if (base64.startsWith("data:image/webp")) mimeType = "image/webp";
                        base64 = base64.substring(commaIndex + 1);
                    }
                }
                contents.add(dev.langchain4j.data.message.ImageContent.from(base64, mimeType));
            }
        }

        // 5️⃣ 将纯文本用户消息加入 ChatMemory（自动持久化纯文本到 history 表，防止数据库和序列化报错）
        chatMemory.add(UserMessage.from(textToSave));
        // 6️⃣ 组装消息列表：系统指令对 + ChatMemory 历史消息
        List<ChatMessage> allMessages = new ArrayList<>();
        allMessages.add(UserMessage.from(systemPromptText));
        allMessages.add(AiMessage.from("好的，我明白了。"));
        allMessages.addAll(chatMemory.messages());

        // 7️⃣ 若当前请求包含图片，则临时替换最后一条消息为多模态内容，以便发送给具有视觉能力的模型分析
        if (hasImages) {
            allMessages.set(allMessages.size() - 1, UserMessage.from(contents));
        }

        // 7️⃣ 按需联网搜索（直接调用 MCP 工具，将结果注入上下文）
        if (Boolean.TRUE.equals(request.getSearch()) && request.getMessage() != null) {
            String searchResult = executeWebSearch(request.getMessage());
            if (searchResult != null && !searchResult.isEmpty()) {
                String searchContext = "【联网搜索结果】\n" + searchResult
                        + "\n\n请根据以上搜索结果回答用户的问题。如果搜索结果与问题无关，请忽略搜索结果并使用你的知识回答。";
                // 在用户消息之前插入搜索结果作为上下文
                allMessages.add(allMessages.size() - 1, UserMessage.from(searchContext));
                allMessages.add(allMessages.size() - 1, AiMessage.from("好的，我已了解搜索结果，我会结合这些信息来回答。"));
                log.info("已注入联网搜索结果，长度: {}", searchResult.length());
            }
        }

        // 8️⃣ 按需本地知识库检索（RAG）
        if (Boolean.TRUE.equals(request.getRag()) && request.getMessage() != null) {
            String ragContext = ragService.retrieveContext(request.getMessage(), 3);
            if (ragContext != null && !ragContext.isEmpty()) {
                allMessages.add(allMessages.size() - 1, UserMessage.from(ragContext));
                allMessages.add(allMessages.size() - 1, AiMessage.from("好的，我会优先依据本地知识库检索结果进行回答。"));
                log.info("已注入 RAG 上下文，长度: {}", ragContext.length());
            }
        }

        // 9️⃣ 本地 MCP 工具调用（真正的 Agent 模式，模型自主决定）
        List<ToolSpecification> localToolSpecs = getLocalToolSpecs();

        log.debug("发送消息列表大小: {}, conversationId: {}", allMessages.size(), conversationId);

        final List<ChatMessage> finalMessages = allMessages;

        return Flux.create(sink -> {
            doStreamToolLoop(finalMessages, localToolSpecs, chatMemory, conversationId, sink, new StringBuilder(), 1);
        });
    }

    private void doStreamToolLoop(List<ChatMessage> messages,
                                  List<ToolSpecification> toolSpecs,
                                  ChatMemory chatMemory,
                                  String conversationId,
                                  reactor.core.publisher.FluxSink<String> sink,
                                  StringBuilder fullResponse,
                                  int round) {
        
        dev.langchain4j.model.chat.request.ChatRequest.Builder reqBuilder = 
                dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(messages);
        
        if (toolSpecs != null && !toolSpecs.isEmpty() && round <= MAX_TOOL_ROUNDS) {
            reqBuilder.parameters(dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder()
                    .toolSpecifications(toolSpecs)
                    .build());
        }

        streamingChatModel.chat(reqBuilder.build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                fullResponse.append(partialResponse);
                sink.next(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                AiMessage aiMsg = completeResponse.aiMessage();
                if (aiMsg.hasToolExecutionRequests() && round <= MAX_TOOL_ROUNDS) {
                    messages.add(aiMsg);
                    try {
                        for (ToolExecutionRequest toolReq : aiMsg.toolExecutionRequests()) {
                            String result = localMcpClient.executeTool(toolReq).resultText();
                            messages.add(ToolExecutionResultMessage.from(toolReq, result));
                            log.info("工具 {} 返回: {}", toolReq.name(), result);
                        }
                    } catch (Exception e) {
                        log.error("工具执行失败", e);
                        for (ToolExecutionRequest toolReq : aiMsg.toolExecutionRequests()) {
                            messages.add(ToolExecutionResultMessage.from(toolReq, "工具执行失败: " + e.getMessage()));
                        }
                    }
                    doStreamToolLoop(messages, toolSpecs, chatMemory, conversationId, sink, fullResponse, round + 1);
                } else {
                    if (fullResponse.length() > 0) {
                        chatMemory.add(AiMessage.from(fullResponse.toString()));
                    }
                    memoryService.compressIfNeeded(conversationId);
                    sink.complete();
                }
            }

            @Override
            public void onError(Throwable error) {
                log.error("LLM 调用失败, conversationId: {}", conversationId, error);
                sink.error(error);
            }
        });
    }

    /**
     * 直接调用智谱 MCP 搜索工具，将结果注入上下文。
     */
    private String executeWebSearch(String query) {
        try {
            ToolExecutionRequest searchReq = ToolExecutionRequest.builder()
                    .name("webSearchPro")
                    .arguments("{\"search_query\":\"" + query.replace("\"", "\\\"") + "\"}")
                    .build();
            String result = zhipuMcpClient.executeTool(searchReq).resultText();
            log.info("MCP 搜索完成，结果长度: {}", result.length());
            return result;
        } catch (Exception e) {
            log.error("MCP 搜索失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取本地 MCP 工具定义列表，供模型看到并自主决定是否调用。
     */
    private List<ToolSpecification> getLocalToolSpecs() {
        if (localMcpClient == null) return List.of();
        try {
            List<ToolSpecification> specs = localMcpClient.listTools();
            log.debug("本地 MCP 提供了 {} 个工具", specs.size());
            return specs;
        } catch (Exception e) {
            log.warn("获取本地 MCP 工具失败: {}", e.getMessage());
            return List.of();
        }
    }

    
}
