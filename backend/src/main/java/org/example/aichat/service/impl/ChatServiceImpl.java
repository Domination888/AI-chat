package org.example.aichat.service.impl;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
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
import org.example.aichat.service.PromptService;
import org.example.aichat.service.ChatService;
import org.example.aichat.service.RagService;
import org.example.aichat.service.RoleCardService;
import org.example.aichat.service.SinkRegistry;
import org.example.aichat.mcp.McpClientManager;
import org.example.aichat.skill.SkillRuntimeService;
import org.example.aichat.skill.SkillService;
import org.example.aichat.service.memos.MemosClient;
import org.example.aichat.service.memos.MemosWriteQueueService;
import org.example.aichat.mapper.ConversationMapper;
import org.example.aichat.dto.Conversation;
import org.example.aichat.config.LlmProperties;
import org.example.aichat.util.PromptLogger;
import org.example.aichat.util.LatencyTrace;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    @Resource
    private StreamingChatModel streamingChatModel;
    @Resource
    private PromptService promptService;
    @Resource
    private MemosClient memosClient;
    @Resource
    private MemosWriteQueueService memosWriteQueueService;
    @Resource
    private RagService ragService;
    @Resource
    private ChatMemoryStore chatMemoryStore;
    @Resource
    private McpClientManager mcpClientManager;
    @Resource
    private SkillService skillService;
    @Resource
    private SkillRuntimeService skillRuntimeService;
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

    @Resource
    private org.example.aichat.config.MemosProperties memosProperties;

    @Resource
    private PromptLogger promptLogger;

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
        Integer roleId = resolveRoleId(request.getRoleId());
        Integer userId = 0;
        LatencyTrace trace = request.getLatencyTrace();
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

        // 1️⃣ 构建 System Prompt：基底 → 能力层（技能）→ 角色层
        StringBuilder sysPrompt = new StringBuilder();
        sysPrompt.append(roleCardService.buildBasePrompt()).append("\n\n");

        String capabilitySection = skillService.buildCapabilityPromptSection(mcpClientManager);
        if (capabilitySection != null && !capabilitySection.isEmpty()) {
            sysPrompt.append(capabilitySection).append("\n\n");
        }

        String roleLayer = roleCardService.buildRoleLayerPrompt(roleId);
        if (roleLayer != null && !roleLayer.isEmpty()) {
            sysPrompt.append(roleLayer).append("\n\n");
        }
        sysPrompt.append("下面开始与用户对话。\n");

        // 2️⃣ 创建 ChatMemory —— 自动从 history 表加载历史消息；后续搜索和 prompt 注入都复用同一段历史
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .id(conversationId)
                .maxMessages(MAX_MEMORY_MESSAGES)
                .chatMemoryStore(chatMemoryStore)
                .build();
        List<Map<String, String>> memosChatHistory = toMemosHistory(chatMemory.messages(), MAX_MEMORY_MESSAGES);
        StringBuilder memoryContext = new StringBuilder();

        // 3️⃣ 加载长期记忆（Memos 结构化分段注入）
        if (request.getMessage() != null) {
            String memosUserId = memosProperties.getEffectiveUserId();
            MemosClient.SearchResult memResult = memosClient.searchStructured(
                    memosUserId, null, roleId, request.getMessage(), memosClient.defaultSearchTopK(), memosChatHistory);

            if (!memResult.isEmpty()) {
                // 用户事实记忆（UserMemory）—— 关于"用户是谁/喜欢什么"的事实
                if (!memResult.userMemories().isEmpty()) {
                    memoryContext.append("【关于用户的事实记忆】（你已知的用户信息，回答时必须参照，不要重复询问）\n");
                    for (MemosClient.MemoryItem m : memResult.userMemories()) {
                        memoryContext.append("- ").append(m.text()).append("\n");
                    }
                    memoryContext.append("\n");
                }

                // 长期记忆/角色日记（LongTermMemory）—— 角色第一人称视角的回忆
                if (!memResult.longTermMemories().isEmpty()) {
                    memoryContext.append("【你与用户之间的往事回忆】（以你的视角发生过的事，可作为话题与情感参考）\n");
                    for (MemosClient.MemoryItem m : memResult.longTermMemories()) {
                        memoryContext.append("- ").append(m.text()).append("\n");
                    }
                    memoryContext.append("\n");
                }

                // 偏好记忆（PrefMemory）—— Memos 提取的用户偏好
                if (!memResult.preferenceMemories().isEmpty()) {
                    sysPrompt.append("【用户偏好】（请在回答时优先满足）\n");
                    for (MemosClient.MemoryItem m : memResult.preferenceMemories()) {
                        sysPrompt.append("- ").append(m.text()).append("\n");
                    }
                    sysPrompt.append("\n");
                }
            } else if (!memosClient.isEnabled() || memosClient.isFallbackToRag()) {
                log.info("Memos 记忆未命中：query='{}', userId={}, roleId={}, enabled={}, fallbackToRedisRag={}",
                        abbreviate(request.getMessage()), memosUserId, roleId,
                        memosClient.isEnabled(), memosClient.isFallbackToRag());
                // Memos 不可用时降级到 Redis RAG 长期记忆
                String longTermMemory = ragService.searchLongTermMemoryContext(userId, roleId, request.getMessage(), 3);
                if (longTermMemory != null && !longTermMemory.isEmpty()) {
                    memoryContext.append("【曾经闪过的往事片段】\n").append(longTermMemory).append("\n\n");
                } else {
                    log.info("Redis fallback 长期记忆未注入：query='{}', userId={}, roleId={}",
                            abbreviate(request.getMessage()), userId, roleId);
                }
            } else {
                log.info("Memos 记忆未命中：query='{}', userId={}, roleId={}, enabled=true, fallbackToRedisRag=false",
                        abbreviate(request.getMessage()), memosUserId, roleId);
            }
        } else {
            log.info("记忆检索跳过：用户消息为空 userId={}, roleId={}", userId, roleId);
        }
        if (trace != null) trace.mark("context_memos");

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

        // 技能预取 + 通用联网预搜索（SearXNG）；webSearch 工具仍可供模型自行调用
        List<ChatMessage> historyForSkills = new ArrayList<>(chatMemory.messages());
        injectPreSearch(skillRuntimeService.tryPreInject(
                request.getMessage(), historyForSkills, useSearch, mcpClientManager), allMessages);
        if (useSearch && request.getMessage() != null
                && !skillRuntimeService.shouldSkipGenericPreSearch(request.getMessage(), historyForSkills)) {
            injectPreSearch(skillRuntimeService.tryGenericWebPreSearch(
                    request.getMessage(), useSearch, mcpClientManager), allMessages);
        }
        if (trace != null) trace.mark("context_pre_search");

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
            injectRagAndMemoryContext(allMessages, ragContext, memoryContext.toString(), roleCode, request.getMessage());
        } else {
            if (!useRag) {
                log.info("RAG 检索跳过：请求关闭 RAG query='{}', roleId={}",
                        abbreviate(request.getMessage()), roleId);
            } else {
                log.info("RAG 检索跳过：用户消息为空 roleId={}", roleId);
            }
            injectRagAndMemoryContext(allMessages, "", memoryContext.toString(), null, request.getMessage());
        }
        if (trace != null) trace.mark("context_rag");

        // MCP 工具调用（默认开；Gemma4 原生支持）：聚合所有已启用 MCP 服务器的工具（含 SearXNG webSearch）
        List<ToolSpecification> localToolSpecs = useTools ? getMcpToolSpecs() : List.of();

        allMessages = normalizeMessagesForChatTemplate(allMessages);
        log.debug("发送消息列表大小: {}, conversationId: {}", allMessages.size(), conversationId);
        validateMessagesBeforeLlm(conversationId, allMessages);

        final List<ChatMessage> finalMessages = allMessages;
        final Integer finalUserId = userId;
        final Integer finalRoleId = roleId;
        final StreamingChatModel finalCurrentStreamingChatModel = currentStreamingChatModel;
        final LatencyTrace finalTrace = trace;
        final String finalStreamId = request.getStreamId();
        if (finalTrace != null) finalTrace.mark("llm_prompt_ready");

        return Flux.create(sink -> {
            AtomicBoolean llmFirstToken = new AtomicBoolean(false);
            doStreamToolLoop(finalMessages, localToolSpecs, chatMemory, conversationId,
                    finalUserId, finalRoleId, sink, new StringBuilder(), 1, finalCurrentStreamingChatModel,
                    request.getMessage(), memosChatHistory, finalTrace, llmFirstToken, finalStreamId);
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
                                  StreamingChatModel currentStreamingChatModel,
                                  String originalUserMessage,
                                  List<Map<String, String>> memosChatHistory,
                                  LatencyTrace trace,
                                  AtomicBoolean llmFirstToken,
                                  String streamId) {

        dev.langchain4j.model.chat.request.ChatRequest.Builder reqBuilder =
                dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(messages);

        // 记录发送给 LLM 的完整 prompt（含 toolSpecifications）
        promptLogger.log(conversationId, messages, toolSpecs);

        if (toolSpecs != null && !toolSpecs.isEmpty() && round <= MAX_TOOL_ROUNDS) {
            reqBuilder.parameters(dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder()
                    .toolSpecifications(toolSpecs)
                    .build());
        }

        if (trace != null && round == 1) {
            trace.mark("llm_request");
        }

        currentStreamingChatModel.chat(reqBuilder.build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                // 检查取消标记：已被打断时跳过输出，不再向 sink 推数据
                if (sinkRegistry.isCancelled(conversationId, streamId)) {
                    log.info("onPartialResponse: conversationId={} 已被取消，跳过", conversationId);
                    return;
                }
                if (trace != null && llmFirstToken.compareAndSet(false, true)) {
                    trace.mark("llm_first_token");
                }
                fullResponse.append(partialResponse);
                sink.next(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                boolean cancelled = sinkRegistry.isCancelled(conversationId, streamId);
                if (cancelled) {
                    log.info("onCompleteResponse: conversationId={} 已被取消", conversationId);
                }
                if (trace != null && !cancelled) {
                    trace.mark("llm_complete");
                }
                AiMessage aiMsg = completeResponse.aiMessage();
                if (!cancelled && aiMsg.hasToolExecutionRequests() && round <= MAX_TOOL_ROUNDS) {
                    messages.add(aiMsg);
                    if (trace != null) trace.mark("tool_round_" + round + "_start");
                    try {
                        for (ToolExecutionRequest toolReq : aiMsg.toolExecutionRequests()) {
                            String result = mcpClientManager.executeTool(toolReq);
                            messages.add(ToolExecutionResultMessage.from(toolReq, result));
                            log.info("工具 {} 返回: {}", toolReq.name(), result);
                        }
                    } catch (Exception e) {
                        log.error("工具执行失败", e);
                        for (ToolExecutionRequest toolReq : aiMsg.toolExecutionRequests()) {
                            messages.add(ToolExecutionResultMessage.from(toolReq, "工具执行失败: " + e.getMessage()));
                        }
                    }
                    if (trace != null) trace.mark("tool_round_" + round + "_done");
                    doStreamToolLoop(messages, toolSpecs, chatMemory, conversationId,
                            userId, roleId, sink, fullResponse, round + 1, currentStreamingChatModel, originalUserMessage,
                            memosChatHistory, trace, llmFirstToken, streamId);
                } else if (!cancelled && fullResponse.length() > 0) {
                    String normalized = normalizeEmotionTags(fullResponse.toString());
                    chatMemory.add(AiMessage.from(normalized));
                    sink.complete();
                    enqueueConversationTurnToMemos(conversationId, userId, roleId, originalUserMessage, normalized, memosChatHistory);
                    return;
                } else if (cancelled) {
                    sink.complete();
                    enqueueConversationTurnToMemos(conversationId, userId, roleId, originalUserMessage, null, memosChatHistory);
                    return;
                }
                sink.complete();
            }

            @Override
            public void onError(Throwable error) {
                log.error("LLM 调用失败, conversationId: {}", conversationId, error);
                sink.error(error);
            }
        });
    }

    private Integer resolveRoleId(Integer roleId) {
        if (roleId == null) {
            return 1;
        }
        org.example.aichat.dto.RoleCard role = roleCardMapper.findById(roleId);
        if (role != null) {
            return role.getId();
        }
        log.warn("请求 roleId={} 不存在于 role_card，Memos/RAG 回退 roleId=1", roleId);
        return 1;
    }

    private void validateMessagesBeforeLlm(String conversationId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalStateException("LLM 请求 messages 为空, conversationId=" + conversationId);
        }
        boolean hasUserQuery = messages.stream().anyMatch(this::isNonEmptyUserMessage);
        if (!hasUserQuery) {
            log.error("LLM 请求缺少非空 user 消息, conversationId={}, messageTypes={}",
                    conversationId, messages.stream().map(m -> m.getClass().getSimpleName()).toList());
            throw new IllegalStateException("LLM 请求缺少非空 user 消息，请检查消息裁剪/并发发送逻辑");
        }
    }

    private boolean isNonEmptyUserMessage(ChatMessage message) {
        if (!(message instanceof UserMessage userMessage)) {
            return false;
        }
        if (userMessage.hasSingleText()) {
            return userMessage.singleText() != null && !userMessage.singleText().isBlank();
        }
        if (userMessage.contents() == null) {
            return false;
        }
        return userMessage.contents().stream().anyMatch(content -> {
            if (content instanceof TextContent textContent) {
                return textContent.text() != null && !textContent.text().isBlank();
            }
            return content instanceof ImageContent;
        });
    }

    private List<ChatMessage> normalizeMessagesForChatTemplate(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<ChatMessage> normalized = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message instanceof UserMessage userMessage
                    && !normalized.isEmpty()
                    && normalized.get(normalized.size() - 1) instanceof UserMessage previousUser) {
                normalized.set(normalized.size() - 1, mergeUserMessages(previousUser, userMessage));
            } else {
                normalized.add(message);
            }
        }
        return normalized;
    }

    private UserMessage mergeUserMessages(UserMessage first, UserMessage second) {
        if (first.hasSingleText() && second.hasSingleText()) {
            return UserMessage.from(joinUserText(first.singleText(), second.singleText()));
        }

        List<Content> contents = new ArrayList<>();
        appendUserContents(contents, first);
        contents.add(TextContent.from("\n\n"));
        appendUserContents(contents, second);
        return UserMessage.from(contents);
    }

    private String joinUserText(String first, String second) {
        String left = first == null ? "" : first.stripTrailing();
        String right = second == null ? "" : second.stripLeading();
        if (left.isBlank()) {
            return right;
        }
        if (right.isBlank()) {
            return left;
        }
        return left + "\n\n" + right;
    }

    private void appendUserContents(List<Content> out, UserMessage message) {
        if (message.hasSingleText()) {
            out.add(TextContent.from(message.singleText() == null ? "" : message.singleText()));
            return;
        }
        if (message.contents() != null) {
            out.addAll(message.contents());
        }
    }

    private void enqueueConversationTurnToMemos(String conversationId, Integer userId, Integer roleId,
                                                String userMsg, String assistantMsg,
                                                List<Map<String, String>> memosChatHistory) {
        memosWriteQueueService.enqueueConversationTurn(
                conversationId, userId, roleId, userMsg, assistantMsg, memosChatHistory);
    }

    private List<Map<String, String>> toMemosHistory(List<ChatMessage> messages, int maxMessages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, messages.size() - Math.max(maxMessages, 1));
        List<Map<String, String>> out = new ArrayList<>();
        for (ChatMessage msg : messages.subList(from, messages.size())) {
            Map<String, String> item = toMemosMessage(msg);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    private Map<String, String> toMemosMessage(ChatMessage msg) {
        if (msg == null) {
            return null;
        }
        String role;
        if (msg instanceof UserMessage) {
            role = "user";
            try {
                String content = ((UserMessage) msg).singleText();
                return memosMessage(role, content);
            } catch (Exception e) {
                return null;
            }
        } else if (msg instanceof AiMessage) {
            role = "assistant";
            return memosMessage(role, ((AiMessage) msg).text());
        } else if (msg instanceof ToolExecutionResultMessage) {
            role = "tool";
        } else {
            return null;
        }
        String content = msg.toString();
        if (content == null || content.isBlank()) {
            return null;
        }
        Map<String, String> item = new LinkedHashMap<>();
        item.put("role", role);
        item.put("content", content);
        return item;
    }

    private Map<String, String> memosMessage(String role, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Map<String, String> item = new LinkedHashMap<>();
        item.put("role", role);
        item.put("content", content);
        return item;
    }

    private void injectPreSearch(java.util.Optional<SkillRuntimeService.PreInjection> pre, List<ChatMessage> allMessages) {
        pre.ifPresent(p -> {
            String block = p.blockTitle() + "\n" + p.body() + "\n\n" + p.tailHint();
            allMessages.add(allMessages.size() - 1, UserMessage.from(block));
            log.info("已注入技能/搜索上下文 [{}], 长度: {}", p.logTag(), p.body().length());
        });
    }

    private void injectRagAndMemoryContext(List<ChatMessage> allMessages,
                                           String ragContext,
                                           String memoryContext,
                                           String roleCode,
                                           String query) {
        boolean hasRag = ragContext != null && !ragContext.isBlank();
        boolean hasMemory = memoryContext != null && !memoryContext.isBlank();
        if (!hasRag && !hasMemory) {
            log.info("RAG/记忆上下文均未注入：query='{}', roleCode={}", abbreviate(query), roleCode);
            return;
        }

        StringBuilder block = new StringBuilder();
        if (hasRag) {
            block.append(ragContext.stripTrailing());
        }
        if (hasMemory) {
            if (!block.isEmpty()) {
                block.append("\n\n");
            }
            block.append(memoryContext.stripTrailing());
        }

        allMessages.add(allMessages.size() - 1, UserMessage.from(block.toString()));
        if (hasRag) {
            log.info("已注入 RAG 上下文，roleCode={}, 长度: {}", roleCode, ragContext.length());
        }
        if (hasMemory) {
            log.info("已注入记忆上下文到当前问题前，长度: {}", memoryContext.length());
        }
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() > 80 ? compact.substring(0, 80) + "..." : compact;
    }

    /**
     * 获取全部已启用 MCP 服务器聚合的工具定义，供模型看到并自主决定是否调用。
     */
    private List<ToolSpecification> getMcpToolSpecs() {
        try {
            List<ToolSpecification> specs = mcpClientManager.listAllTools();
            log.debug("MCP 注册中心提供了 {} 个工具", specs.size());
            return specs;
        } catch (Exception e) {
            log.warn("获取 MCP 工具失败: {}", e.getMessage());
            return List.of();
        }
    }

}
