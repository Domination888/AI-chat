package org.example.aichat.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式 token -> 句子 切分器（线程不安全；每个 SSE 请求独占一个实例）。
 *
 * 用法：
 *   List<String> sentences = splitter.append(token);   // 返回这次新攒齐的整句
 *   String tail = splitter.flushRemainder();           // 流结束后把剩下的当一句吐出
 *
 * 切分规则：
 *   - 中英文标点：。！？!?；;.\n  → 句末
 *   - 单句最小长度 minLen：太短的不切，避免一两个字就触发 TTS
 *   - 单句最大长度 maxLen：超长强制切（按逗号 ， , 优先）
 */
public class SentenceSplitter {

    private static final String SENTENCE_END = "。！？!?；;…\n";
    private static final String SOFT_BREAK = "，,";

    private final StringBuilder buf = new StringBuilder();
    private final int minLen;
    private final int maxLen;

    public SentenceSplitter() { this(10, 80); }

    public SentenceSplitter(int minLen, int maxLen) {
        this.minLen = minLen;
        this.maxLen = maxLen;
    }

    public List<String> append(String token) {
        List<String> out = new ArrayList<>();
        if (token == null || token.isEmpty()) return out;
        buf.append(token);
        while (true) {
            int hardCut = -1;
            int softCut = -1;
            int len = buf.length();
            for (int i = 0; i < len; i++) {
                char c = buf.charAt(i);
                if (SENTENCE_END.indexOf(c) >= 0) { hardCut = i; break; }
                if (SOFT_BREAK.indexOf(c) >= 0 && i + 1 >= maxLen) { softCut = i; }
            }
            int cut;
            if (hardCut >= 0 && hardCut + 1 >= minLen) {
                cut = hardCut;
            } else if (len >= maxLen && softCut >= 0) {
                cut = softCut;
            } else if (len >= maxLen) {
                cut = maxLen - 1;
            } else {
                break; // 还没攒够一句
            }
            String sentence = buf.substring(0, cut + 1).trim();
            buf.delete(0, cut + 1);
            if (!sentence.isEmpty()) out.add(sentence);
        }
        return out;
    }

    /** 流结束时把残留缓冲一次性吐出（单独成一句） */
    public String flushRemainder() {
        String rest = buf.toString().trim();
        buf.setLength(0);
        return rest.isEmpty() ? null : rest;
    }
}