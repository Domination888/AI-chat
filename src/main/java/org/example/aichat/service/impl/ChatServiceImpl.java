package org.example.aichat.service.impl;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;import dev.langchain4j.mcp.client.McpClient;
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
import org.example.aichat.service.RoleCardService;
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

    /** 专用模块日志：写入 log/prompt/prompt.log（见 logback-spring.xml）—— 记录每次发往 LLM 的完整 messages */
    private static final org.slf4j.Logger PROMPT_LOG = org.slf4j.LoggerFactory.getLogger("module.prompt");
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


    /** ChatMemory 窗口大小：保留最近的消息条数 */
    private static final int MAX_MEMORY_MESSAGES = 20;
    /** 本地工具调用最大轮数 */
    private static final int MAX_TOOL_ROUNDS = 3;

    @Override
    public Flux<String> chatStream(ChatRequest request) {

//        if (guardrailService.checkInput(request)) {
//            return Flux.just("输入违规");
//        }

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

        // 开关默认值（请求未显式带字段时按"默认开"处理）：
        //   rag    = true   —— 角色卡/长期记忆都依赖 RAG，不能默认关
        //   tools  = true   —— Gemma4 原生支持 tool-call；语音通道会在 AudioController 显式置 false
        //   search = false  —— 仅用户在前端显式开启时才走联网
        boolean useSearch = Boolean.TRUE.equals(request.getSearch());
        boolean useRag = !Boolean.FALSE.equals(request.getRag());     // null / true → 都开
        boolean useTools = !Boolean.FALSE.equals(request.getTools()); // null / true → 都开

        // 7️⃣ 按需联网搜索（直接调用 MCP 工具，将结果注入上下文）
        if (useSearch && request.getMessage() != null) {
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

        // 8️⃣ 本地知识库检索（RAG，默认开）
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

        // 9️⃣ 本地 MCP 工具调用（默认开；Gemma4 原生支持）
        //    语音通道（AudioController）会显式 setTools(false) 以保首包延迟
        List<ToolSpecification> localToolSpecs = useTools ? getLocalToolSpecs() : List.of();

        log.debug("发送消息列表大小: {}, conversationId: {}", allMessages.size(), conversationId);

        // 发往 LLM 前的完整 prompt 落盘到 log/prompt/prompt.log（含 system / RAG / 历史 / 当前用户）
        dumpPromptToLog("chatStream", conversationId, userId, roleId, allMessages,
                useSearch, useRag, useTools, hasImages,
                request.getMessage(), localToolSpecs);

        final List<ChatMessage> finalMessages = allMessages;
        final Integer finalUserId = userId;
        final Integer finalRoleId = roleId;

        return Flux.create(sink -> {
            doStreamToolLoop(finalMessages, localToolSpecs, chatMemory, conversationId,
                    finalUserId, finalRoleId, sink, new StringBuilder(), 1);
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
                                  int round) {
        
        dev.langchain4j.model.chat.request.ChatRequest.Builder reqBuilder = 
                dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(messages);
        
        if (toolSpecs != null && !toolSpecs.isEmpty() && round <= MAX_TOOL_ROUNDS) {
            reqBuilder.parameters(dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder()
                    .toolSpecifications(toolSpecs)
                    .build());
        }

        // 工具回合 round>=2 时（首轮已在 chatStream 入口记录），追加记录每次重发 LLM 前的 messages 状态
        if (round > 1) {
            PROMPT_LOG.info("---- chatStream tool-round#{} | conversationId={} | userId={} | roleId={} | messages={} ----",
                    round, conversationId, userId, roleId, messages.size());
            for (int i = 0; i < messages.size(); i++) {
                PROMPT_LOG.info("[{}#{}] {}", round, i, formatMessage(messages.get(i)));
            }
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
                    doStreamToolLoop(messages, toolSpecs, chatMemory, conversationId,
                            userId, roleId, sink, fullResponse, round + 1);
                } else {
                    if (fullResponse.length() > 0) {
                        chatMemory.add(AiMessage.from(fullResponse.toString()));
                    }
                    memoryService.compressIfNeeded(conversationId);
                    // 与 chatBlocking 分支对齐：异步抽取长期记忆并推入 Redis RAG
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

    @Override
    public String chatBlocking(String conversationId, String message, Integer userId, Integer roleId) {
        // 1. 保存或更新会话表
        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setUserId(userId);
        conversation.setRoleId(roleId);
        conversation.setTitle("语音角色扮演会话");
        conversationMapper.insertOrUpdate(conversation);

        // 2. 加载短期记忆窗口
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .id(conversationId)
                .maxMessages(MAX_MEMORY_MESSAGES)
                .chatMemoryStore(chatMemoryStore)
                .build();

        // 3. 构建包含角色设定的核心 System Prompt（统一走 RoleCardService）
        StringBuilder sysPrompt = new StringBuilder();
        sysPrompt.append(roleCardService.buildSystemPrompt(roleId)).append("\n");
        sysPrompt.append("【语音输出补充】\n")
                 .append("你的回答将被转换为语音进行播放，所以请返回纯文本的口语化对白，避免 markdown 语法和复杂书面符号。\n\n");

        // 4. 加载长期记忆 (RAG)
        String longTermMemory = ragService.searchLongTermMemoryContext(userId, roleId, message, 3);
        if (longTermMemory != null && !longTermMemory.isEmpty()) {
            sysPrompt.append("【曾经零碎的长期记忆】（以下是你脑海中闪过的往事，作为你的经验和先验知识参考）：\n")
                     .append(longTermMemory).append("\n\n");
        }
        
        // 5. 加载事件总结 (Summary Memory)
        Memory memory = memoryService.findByConversationId(conversationId);
        if (memory != null && memory.getSummary() != null && !memory.getSummary().isEmpty()) {
            sysPrompt.append("【近期聊天前情提要】\n").append(memory.getSummary()).append("\n\n");
        }

        // 6. 拼装消息，发送给模型
        List<ChatMessage> finalMessages = new ArrayList<>();
        finalMessages.add(dev.langchain4j.data.message.SystemMessage.from(sysPrompt.toString()));
        
        // 加上短期上下文
        finalMessages.addAll(chatMemory.messages());
        
        // 加上当前用户消息
        UserMessage currentUserMsg = UserMessage.from(message);
        finalMessages.add(currentUserMsg);

        // 发往 LLM 前的完整 prompt 落盘到 log/prompt/prompt.log（语音通道阻塞分支）
        dumpPromptToLog("chatBlocking", conversationId, userId, roleId, finalMessages,
                false, true, false, false, message, java.util.Collections.emptyList());

        try {
            ChatResponse response = chatModel.chat(finalMessages);
            String aiText = response.aiMessage().text();            
            // 将最新回合加入记忆库
            chatMemory.add(currentUserMsg);
            chatMemory.add(AiMessage.from(aiText));
            
            // 异步触发判断是否需要浓缩记忆推入 RAG
            new Thread(() -> {
                memoryService.compressAndExtractLongTermMemory(conversationId, userId, roleId);
            }).start();
            
            return aiText;
        } catch (Exception e) {
            log.error("AI 角色扮演语音聊天失败", e);
            return "（思考中断了，请再说一遍好吗...）";
        }
    }

    // ============================================================
    // Prompt 模块日志：把每次发到 LLM 的完整消息列表写到 log/prompt/prompt.log
    // 不做截断，便于排查"角色漂移 / RAG 噪声 / 历史污染"等问题
    // ============================================================
    private void dumpPromptToLog(String channel,
                                 String conversationId,
                                 Integer userId,
                                 Integer roleId,
                                 List<ChatMessage> messages,
                                 boolean useSearch,
                                 boolean useRag,
                                 boolean useTools,
                                 boolean hasImages,
                                 String userMessage,
                                 List<ToolSpecification> toolSpecs) {
        try {
            int totalChars = 0;
            for (ChatMessage m : messages) totalChars += approxLen(m);
            PROMPT_LOG.info("==== {} | conversationId={} | userId={} | roleId={} | useSearch={} | useRag={} | useTools={} | hasImages={} | toolSpecs={} | messages={} | approxChars={} | userMsg={} ====",
                    channel, conversationId, userId, roleId, useSearch, useRag, useTools, hasImages,
                    toolSpecs == null ? 0 : toolSpecs.size(),
                    messages.size(), totalChars,
                    userMessage == null ? "" : userMessage);
            for (int i = 0; i < messages.size(); i++) {
                PROMPT_LOG.info("[{}] {}", i, formatMessage(messages.get(i)));
            }
            if (toolSpecs != null && !toolSpecs.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (ToolSpecification t : toolSpecs) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(t.name());
                }
                PROMPT_LOG.info("[tools] {}", sb);
            }
            PROMPT_LOG.info("==== {} end ====", channel);
        } catch (Exception e) {
            // 日志失败不影响主链路
            log.warn("dumpPromptToLog 失败: {}", e.toString());
        }
    }

    private String formatMessage(ChatMessage m) {
        if (m == null) return "null";
        if (m instanceof dev.langchain4j.data.message.SystemMessage sm) {
            return "SYSTEM: " + sm.text();
        }
        if (m instanceof UserMessage um) {
            // 多模态时 contents 可能是文本+图片，逐项打印（图片只记录 mimeType）
            StringBuilder sb = new StringBuilder("USER: ");
            for (dev.langchain4j.data.message.Content c : um.contents()) {
                if (c instanceof dev.langchain4j.data.message.TextContent tc) {
                    sb.append(tc.text());
                } else if (c instanceof dev.langchain4j.data.message.ImageContent) {
                    sb.append("<image>");
                } else {
                    sb.append("<").append(c.getClass().getSimpleName()).append(">");
                }
            }
            return sb.toString();
        }
        if (m instanceof AiMessage am) {
            String text = am.text() == null ? "" : am.text();
            if (am.hasToolExecutionRequests()) {
                StringBuilder sb = new StringBuilder("AI: ").append(text).append(" | toolCalls=[");
                int i = 0;
                for (var t : am.toolExecutionRequests()) {
                    if (i++ > 0) sb.append(", ");
                    sb.append(t.name()).append("(").append(t.arguments()).append(")");
                }
                sb.append("]");
                return sb.toString();
            }
            return "AI: " + text;
        }
        if (m instanceof ToolExecutionResultMessage tr) {
            return "TOOL_RESULT(" + tr.toolName() + "): " + tr.text();
        }
        return m.getClass().getSimpleName() + ": " + m.toString();
    }

    private int approxLen(ChatMessage m) {
        if (m instanceof dev.langchain4j.data.message.SystemMessage sm) return sm.text() == null ? 0 : sm.text().length();
        if (m instanceof UserMessage um) {
            int n = 0;
            for (dev.langchain4j.data.message.Content c : um.contents()) {
                if (c instanceof dev.langchain4j.data.message.TextContent tc && tc.text() != null) n += tc.text().length();
            }
            return n;
        }
        if (m instanceof AiMessage am) return am.text() == null ? 0 : am.text().length();
        if (m instanceof ToolExecutionResultMessage tr) return tr.text() == null ? 0 : tr.text().length();
        return 0;
    }
}
