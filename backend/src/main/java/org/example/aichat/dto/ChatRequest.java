package org.example.aichat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.example.aichat.util.LatencyTrace;

import java.util.List;
import java.util.function.Consumer;
import org.example.aichat.search.SearchProgress;

@Data
public class ChatRequest {
    // ===== 输入方式 =====
    // text  = 文本输入（message 字段）
    // audio = 语音输入（audioBase64 字段，后端先 ASR 再走 LLM）
    private String inputMode = "text";

    // 必填
    private String userId;

    // 必填：用于区分多个对话
    private String conversationId;

    // 文本输入时必填（纯图片时可为空）
    private String message;

    // 语音输入时必填：base64 编码的音频数据
    private String audioBase64;

    // 音频格式（如 webm、wav），默认 webm
    private String audioFormat = "webm";

    // 可为空（纯文本时）
    private List<String> images;   // base64字符串数组

    // 角色 ID
    private Integer roleId;

    // 是否流式（默认 true，语音/文本都走流式）
    private Boolean stream = true;

    // 是否启用联网搜索（默认开启，语音/文本都支持联网）
    private Boolean search = true;

    // 是否启用本地知识库检索（RAG），默认开启
    //   理由：本项目所有角色卡 + 长期记忆都依赖 RAG 召回，关闭等于"忘记角色设定"
    private Boolean rag = true;

    // 是否允许本地 MCP 工具调用（Agent 模式），默认开启
    //   Gemma4-31B 原生支持 OpenAI tool-call
    private Boolean tools = true;

    // 客户端可配置的模型参数（可选）
    private String modelName;           // 模型名称
    private String modelBaseUrl;        // 模型API地址

    // ASR参数（可选，语音输入时使用）
    private String asrLanguage;         // ASR语言
    private String asrHotwords;         // ASR热词

    // TTS参数（可选，语音/文本都支持 TTS 回播）
    private String ttsVoiceId;          // TTS音色ID
    private Double ttsSpeedFactor;      // TTS语速
    private Double ttsPitchFactor;      // TTS音调

    /** 前端发送时刻（epoch ms），用于全链路 E2E 延迟 */
    private Long clientSentAt;

    /** 服务端延迟追踪（不序列化） */
    @JsonIgnore
    private transient LatencyTrace latencyTrace;

    /** 当前 SSE 流 ID（不序列化），用于区分同一会话内的新旧流。 */
    @JsonIgnore
    private transient String streamId;

    /** 搜索阶段进度回调，由 ChatController 绑定到当前 SSE。 */
    @JsonIgnore
    private transient Consumer<SearchProgress> searchProgressListener;

    /** 主动研究等内部触发：提示只参与本轮推理，不作为用户消息持久化。 */
    @JsonIgnore
    private transient boolean internalTrigger;

    /** 完整助手回复落库后回调，用于绑定主动候选。 */
    @JsonIgnore
    private transient Consumer<String> assistantCompleteListener;
}
