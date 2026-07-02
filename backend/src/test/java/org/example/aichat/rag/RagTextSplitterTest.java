package org.example.aichat.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagTextSplitterTest {

    @Test
    void splitsStoryNodeFilesByNodeHeading() {
        String text = """
                # HS-1 赴大荒 · 剧情节点摘要

                > 用途：RAG 语料。
                > 核心关键词：黍、左乐、大荒城

                ## 节点 1：黍的“算命”其实是劝人顺其自然

                黍给职农看手相时并不强调神通，而是把命数解释成给已发生的事找理由。

                ## 节点 2：左乐被司岁台派来监管黍

                左乐以司岁台秉烛人的身份来到大荒城，职责是监管岁兽相关事务。
                """;

        List<String> chunks = RagTextSplitter.split("hs-1_赴大荒_剧情节点.md", text);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).contains("核心关键词：黍、左乐、大荒城");
        assertThat(chunks.get(0)).contains("节点 1");
        assertThat(chunks.get(0)).doesNotContain("节点 2");
        assertThat(chunks.get(1)).contains("节点 2");
    }

    @Test
    void splitsVoiceFilesByVoiceEntry() {
        String text = """
                # 黍/语音记录

                ==语音记录==
                #widget:VoiceTable

                ### 任命助理
                博士，阳光与水分都是生长的必要条件。

                ### 干员报到
                年应该已经和你说了我的名字，“黍”。
                """;

        List<String> chunks = RagTextSplitter.split("voice.md", text);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).contains("任命助理");
        assertThat(chunks.get(0)).doesNotContain("干员报到");
        assertThat(chunks.get(0)).doesNotContain("#widget");
        assertThat(chunks.get(1)).contains("干员报到");
    }

    @Test
    void splitsProfileFilesByBracketedSectionsAndParagraphs() {
        String text = """
                # 黍 · 干员档案

                【代号】黍
                【性别】女

                【矿石病感染情况】
                参照医学检测报告，确认为非感染者。

                【权限记录】
                在大荒城发生那场意外的同一天，京城司岁台的藏物阁中，一株水稻枯萎了。
                """;

        List<String> chunks = RagTextSplitter.split("profile.md", text);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).contains("【代号】黍");
        assertThat(chunks.get(1)).contains("【矿石病感染情况】");
        assertThat(chunks.get(2)).contains("【权限记录】");
    }
}
