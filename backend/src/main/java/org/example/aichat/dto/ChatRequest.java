package org.example.aichat.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {
    // 必填
    private String userId;

    // 必填：用于区分多个对话
    private String conversationId;

    // 可为空（纯图片时）
    private String message;

    // 可为空（纯文本时）
    private List<String> images;   // base64字符串数组
    
    // 角色 ID
    private Integer roleId;

    // 是否流式
    private Boolean stream = true;

    // 是否启用联网搜索（默认关闭，由前端 toggle 控制）
    private Boolean search = false;

    // 是否启用本地知识库检索（RAG），默认开启
    //   理由：本项目所有角色卡 + 长期记忆都依赖 RAG 召回，关闭等于"忘记角色设定"
    //   前端无需暴露 toggle，强制走 RAG（详见 PLAN-001）
    private Boolean rag = true;

    // 是否允许本地 MCP 工具调用（Agent 模式），默认开启
    //   Gemma4-31B 原生支持 OpenAI tool-call
    //   语音通道在 AudioController 里会显式置 false 以保首包延迟
    private Boolean tools = true;

    // 客户端可配置的模型参数（可选）
    private String modelName;           // 模型名称
    private String modelBaseUrl;        // 模型API地址
    
    // ASR参数（可选）
    private String asrLanguage;         // ASR语言
    private String asrHotwords;         // ASR热词
    
    // TTS参数（可选）
    private String ttsVoiceId;          // TTS音色ID
    private Double ttsSpeedFactor;      // TTS语速
    private Double ttsPitchFactor;      // TTS音调
}
