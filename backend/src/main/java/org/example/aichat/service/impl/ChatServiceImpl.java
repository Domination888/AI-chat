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
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.dto.ChatRequest;
import org.example.aichat.dto.Memory;
import org.example.aichat.service.PromptService;
import org.example.aichat.service.ChatService;
import org.example.aichat.service.MemoryService;
import org.example.aichat.service.RagService;
import org.example.aichat.service.RoleCardService;
import org.example.aichat.service.SinkRegistry;
import org.example.aichat.mapper.ConversationMapper;
import org.example.aichat.dto.Conversation;
import org.example.aichat.config.LlmProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

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
    private PromptService promptService;
    @Resource
    private MemoryService memoryService;
    @Resource
    private RagService ragService;
    @Resource
    private ChatMemoryStore chatMemoryStore;
    @Autowired(required = false)
    @Qualifier("zhipuMcpClient")
    private McpClient zhipuMcpClient;
    @Autowired(required = false)
    @Qualifier("localMcpClient")
    private McpClient localMcpClient;
    @Resource
    private ConversationMapper conversationMapper;
    @Resource
    private org.example.aichat.mapper.RoleCardMapper roleCardMapper;
    @Resource
    private RoleCardService roleCardService;
    @Resource
    private SinkRegistry sinkRegistry;

    @Resource
    private LlmProperties llmProperties;

    /** ChatMemory 窗口大小：保留最近的消息条数 */
    private static final int MAX_MEMORY_MESSAGES = 20;

    /** 合法情绪标签集合（与前端 emotion-mappings.js 对齐） — 已迁移到 EmotionTagNormalizer */
    // VALID_EMOTION_TAGS 和 EMOTION_TAG_PATTERN 已迁移

    /**
     * 规范化情绪标签：将 AI 回复中不在规定范围的 <xxx> 标签替换为最近出现的合法标签。
     * @see org.example.aichat.util.EmotionTagNormalizer#normalize(String)
     */
    private String normalizeEmotionTags(String text) {
        return org.example.aichat.util.EmotionTagNormalizer.normalize(text);
    }
    /** 本地工具调用最大轮数 */
    private static final int MAX_TOOL_ROUNDS = 3;

    /**
     * 创建流式 ChatModel：前端请求传的 baseUrl/modelName 优先，否则用 LlmProperties 当前值。
     * 始终用 SpringRestClientBuilder 以保证超时/连接参数生效。
     */
    private StreamingChatModel createStreamingChatModel(String requestBaseUrl, String requestModelName) {
        String baseUrl = (requestBaseUrl != null && !requestBaseUrl.isBlank()) ? requestBaseUrl : llmProperties.getBaseUrl();
        String modelName = (requestModelName != null && !requestModelName.isBlank()) ? requestModelName : llmProperties.getEffectiveStreamingModelName();
        log.info("创建 StreamingChatModel: baseUrl={}, modelName={}", baseUrl, modelName);
        return dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(java.time.Duration.ofMillis(llmProperties.getReadTimeoutMs()))
                .httpClientBuilder(new dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilder())
                .build();
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        // 创建动态模型实例：前端传参优先，否则用 LlmProperties 热加载配置
        StreamingChatModel currentStreamingChatModel = createStreamingChatModel(
            request.getModelBaseUrl(), request.getModelName());

        String conversationId = request.getConversationId();
        Integer roleId = request.getRoleId() != null ? request.getRoleId() : 1;
        Integer userId = 0;
        // 0. 保存或更新会话表
        try {
            userId = Integer.parseInt(request.getUserId());
            Conversation conv = new Conversation();
            conv.setId(conversationId);
            conv.setUserId(userId);
            conv.setRoleId(roleId);
            String title = "新对话";
            if (request.getMessage() != null && request.getMessage().length() > 0) {
                title = request.getMessage().length() > 15 ? request.getMessage().substring(0, 15) + "..." : request.getMessage();
            }
            conv.setTitle(title);
            conversationMapper.insertOrUpdate(conv);
        } catch (Exception e) {
            log.error("保存会话失败", e);
        }

        // 1️⃣ 构建包含角色设定的核心 System Prompt（统一走 RoleCardService）
        StringBuilder sysPrompt = new StringBuilder();
        sysPrompt.append(roleCardService.buildSystemPrompt(roleId)).append("\n\n");

        // 2️⃣ 加载长期记忆 (RAG) 和近期总结
        String longTermMemory = ragService.searchLongTermMemoryContext(userId, roleId, request.getMessage(), 3);
        if (longTermMemory != null && !longTermMemory.isEmpty()) {
            sysPrompt.append("【曾经闪过的往事片段】\n").append(longTermMemory).append("\n\n");
        }

        Memory memory = memoryService.findByConversationId(conversationId);
        String memorySummary = (memory != null) ? memory.getSummary() : null;
        if (memorySummary != null && !memorySummary.isEmpty()) {
            sysPrompt.append("【近期聊天前情提要】\n").append(memorySummary).append("\n\n");
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
        allMessages.add(dev.langchain4j.data.message.SystemMessage.from(sysPrompt.toString()));
        allMessages.addAll(chatMemory.messages());

        // 7️⃣ 若当前请求包含图片，则临时替换最后一条消息为多模态内容，以便发送给具有视觉能力的模型分析
        if (hasImages) {
            allMessages.set(allMessages.size() - 1, UserMessage.from(contents));
        }

        // 开关默认值（语音/文本统一：都默认开启）
        //   rag    = true   —— 角色卡/长期记忆都依赖 RAG，不能默认关
        //   tools  = true   —— Gemma4 原生支持 tool-call
        //   search = true   —— 语音/文本都支持联网搜索
        boolean useSearch = !Boolean.FALSE.equals(request.getSearch()); // null / true → 都开
        boolean useRag = !Boolean.FALSE.equals(request.getRag());     // null / true → 都开
        boolean useTools = !Boolean.FALSE.equals(request.getTools()); // null / true → 都开

        // 按需联网搜索（直接调用 MCP 工具，将结果注入上下文）
        if (useSearch && request.getMessage() != null) {
            String searchResult = executeWebSearch(request.getMessage());
            if (searchResult != null && !searchResult.isEmpty()) {
                String searchContext = "【联网搜索结果】\n" + searchResult
                        + "\n\n请根据以上搜索结果回答用户的问题。如果搜索结果与问题无关，请忽略搜索结果并使用你的知识回答。";
                allMessages.add(allMessages.size() - 1, UserMessage.from(searchContext));
                allMessages.add(allMessages.size() - 1, AiMessage.from("好的，我已了解搜索结果，我会结合这些信息来回答。"));
                log.info("已注入联网搜索结果，长度: {}", searchResult.length());
            }
        }

        // 本地知识库检索（RAG，默认开）
        if (useRag && request.getMessage() != null) {
            String roleCode = null;
            try {
                org.example.aichat.dto.RoleCard roleCard = roleCardMapper.findById(roleId);
                if (roleCard != null) {
                    roleCode = roleCard.getRoleCode();
                }
            } catch (Exception e) {
                log.warn("读取角色 roleCode 失败，降级全局RAG，roleId={}", roleId, e);
            }

            String ragContext = ragService.retrieveContext(roleCode, request.getMessage(), 3);
            if (ragContext != null && !ragContext.isEmpty()) {
                allMessages.add(allMessages.size() - 1, UserMessage.from(ragContext));
                allMessages.add(allMessages.size() - 1, AiMessage.from("好的，我会优先依据本地知识库检索结果进行回答。"));
                log.info("已注入 RAG 上下文，roleCode={}, 长度: {}", roleCode, ragContext.length());
            }
        }

        // 本地 MCP 工具调用（默认开；Gemma4 原生支持）
        List<ToolSpecification> localToolSpecs = useTools ? getLocalToolSpecs() : List.of();

        log.debug("发送消息列表大小: {}, conversationId: {}", allMessages.size(), conversationId);

        final List<ChatMessage> finalMessages = allMessages;
        final Integer finalUserId = userId;
        final Integer finalRoleId = roleId;
        final StreamingChatModel finalCurrentStreamingChatModel = currentStreamingChatModel;

        return Flux.create(sink -> {
            doStreamToolLoop(finalMessages, localToolSpecs, chatMemory, conversationId,
                    finalUserId, finalRoleId, sink, new StringBuilder(), 1, finalCurrentStreamingChatModel);
        });
    }

    private void doStreamToolLoop(List<ChatMessage> messages,
                                  List<ToolSpecification> toolSpecs,
                                  ChatMemory chatMemory,
                                  String conversationId,
                                  Integer userId,
                                  Integer roleId,
                                  reactor.core.publisher.FluxSink<String> sink,
                                  StringBuilder fullResponse,
                                  int round,
                                  StreamingChatModel currentStreamingChatModel) {

        dev.langchain4j.model.chat.request.ChatRequest.Builder reqBuilder =
                dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(messages);

        if (toolSpecs != null && !toolSpecs.isEmpty() && round <= MAX_TOOL_ROUNDS) {
            reqBuilder.parameters(dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder()
                    .toolSpecifications(toolSpecs)
                    .build());
        }

        currentStreamingChatModel.chat(reqBuilder.build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                // 检查取消标记：已被打断时跳过输出，不再向 sink 推数据
                if (sinkRegistry.isCancelled(conversationId)) {
                    log.info("onPartialResponse: conversationId={} 已被取消，跳过", conversationId);
                    return;
                }
                fullResponse.append(partialResponse);
                sink.next(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                // 如果已被打断，直接结束流
                if (sinkRegistry.isCancelled(conversationId)) {
                    log.info("onCompleteResponse: conversationId={} 已被取消，直接结束流", conversationId);
                    sink.complete();
                    return;
                }
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
                    doStreamToolLoop(messages, toolSpecs, chatMemory, conversationId,
                            userId, roleId, sink, fullResponse, round + 1, currentStreamingChatModel);
                } else {
                    if (fullResponse.length() > 0) {
                        // 存入 ChatMemory 前规范化情绪标签，防止非规定标签（如<温和>）污染历史
                        String normalized = normalizeEmotionTags(fullResponse.toString());
                        chatMemory.add(AiMessage.from(normalized));
                    }
                    memoryService.compressIfNeeded(conversationId);
                    // 流式对话结束后：异步抽取长期记忆并推入 Redis RAG
                    if (userId != null && roleId != null) {
                        new Thread(() -> {
                            try {
                                memoryService.compressAndExtractLongTermMemory(conversationId, userId, roleId);
                            } catch (Exception e) {
                                log.error("流式对话结束后抽取长期记忆失败 conversationId={}", conversationId, e);
                            }
                        }).start();
                    }
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
        if (zhipuMcpClient == null) {
            log.warn("智谱 MCP 不可用（启动期初始化失败/网络不可达），跳过联网搜索");
            return null;
        }
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