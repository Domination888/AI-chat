package org.example.aichat.rag;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds semantic RAG chunks from curated persona documents.
 *
 * <p>The splitter prefers authored boundaries: story nodes, voice entries,
 * markdown headings, bracketed profile sections, and paragraphs. Character
 * length is used only as an overflow guard for unusually long sections.</p>
 */
public final class RagTextSplitter {

    private static final int SOFT_MAX_CHARS = 900;
    private static final int HARD_MAX_CHARS = 1300;
    private static final Pattern STORY_NODE_HEADING = Pattern.compile("(?m)^##\\s+节点\\s*\\d+[:：].*$");
    private static final Pattern VOICE_HEADING = Pattern.compile("(?m)^###\\s+(.+?)\\s*$");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern BRACKET_HEADING = Pattern.compile("^【[^】]{1,40}】\\s*$");

    private RagTextSplitter() {
    }

    public static List<String> split(String filename, String rawText) {
        String text = normalize(rawText);
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        String normalizedName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith("voice.md") || normalizedName.contains("语音")) {
            return splitVoice(filename, text);
        }
        if (normalizedName.contains("剧情节点")) {
            return splitStoryNodes(filename, text);
        }
        if (normalizedName.endsWith(".md")) {
            return splitMarkdown(filename, text);
        }
        return splitPlainText(filename, text);
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\t', ' ')
                .replaceAll("(?m)[ \\u3000]+$", "")
                .trim();
    }

    private static List<String> splitStoryNodes(String filename, String text) {
        String title = firstMarkdownTitle(text, filename);
        String keywords = firstQuotedLineContaining(text, "核心关键词");
        String prefix = joinNonBlank(" / ", title, stripQuoteMarker(keywords));

        List<Section> nodes = sectionsByPattern(text, STORY_NODE_HEADING);
        if (nodes.isEmpty()) {
            return splitMarkdown(filename, text);
        }

        List<String> chunks = new ArrayList<>();
        for (Section node : nodes) {
            String chunk = withHeader(prefix, node.heading(), node.body());
            addOverflowAware(chunks, chunk, prefix + " / " + node.heading());
        }
        return chunks;
    }

    private static List<String> splitVoice(String filename, String text) {
        String title = firstMarkdownTitle(text, filename);
        List<Section> entries = sectionsByPattern(text, VOICE_HEADING);
        if (entries.isEmpty()) {
            return splitMarkdown(filename, text);
        }

        List<String> chunks = new ArrayList<>();
        for (Section entry : entries) {
            String body = entry.body().lines()
                    .filter(line -> !line.startsWith("#widget:"))
                    .filter(line -> !line.startsWith("=="))
                    .reduce("", (a, b) -> a.isBlank() ? b : a + "\n" + b)
                    .trim();
            if (!StringUtils.hasText(body)) {
                continue;
            }
            String scene = entry.heading().replaceFirst("^###\\s*", "").trim();
            String chunk = withHeader(title, scene, body);
            addOverflowAware(chunks, chunk, title + " / " + scene);
        }
        return chunks;
    }

    private static List<String> splitMarkdown(String filename, String text) {
        List<Section> sections = markdownSections(text, filename);
        List<String> chunks = new ArrayList<>();
        for (Section section : sections) {
            String body = section.body();
            if (!StringUtils.hasText(body)) {
                continue;
            }
            String chunk = withHeader(section.path(), null, body);
            addOverflowAware(chunks, chunk, section.path());
        }
        return chunks;
    }

    private static List<String> splitPlainText(String filename, String text) {
        List<String> chunks = new ArrayList<>();
        for (String paragraph : splitParagraphs(text)) {
            addOverflowAware(chunks, withHeader(filename, null, paragraph), filename);
        }
        return chunks;
    }

    private static List<Section> markdownSections(String text, String filename) {
        String docTitle = firstMarkdownTitle(text, filename);
        List<HeadingHit> headings = markdownHeadingHits(text);
        if (headings.size() > 1) {
            return markdownHeadingSections(text, headings, docTitle);
        }
        return bracketAndParagraphSections(text, docTitle);
    }

    private static List<Section> markdownHeadingSections(String text, List<HeadingHit> headings, String docTitle) {
        List<Section> sections = new ArrayList<>();
        List<String> path = new ArrayList<>();

        for (int i = 0; i < headings.size(); i++) {
            HeadingHit current = headings.get(i);
            int nextStart = i + 1 < headings.size() ? headings.get(i + 1).start() : text.length();
            while (path.size() >= current.level()) {
                path.remove(path.size() - 1);
            }
            path.add(current.title());

            String body = text.substring(current.end(), nextStart).trim();
            if (!StringUtils.hasText(body)) {
                continue;
            }

            String sectionPath = String.join(" / ", path);
            if (!sectionPath.startsWith(docTitle)) {
                sectionPath = joinNonBlank(" / ", docTitle, sectionPath);
            }
            for (String paragraphGroup : groupParagraphs(splitParagraphs(body))) {
                sections.add(new Section(sectionPath, null, paragraphGroup));
            }
        }
        return sections;
    }

    private static List<Section> bracketAndParagraphSections(String text, String docTitle) {
        List<Section> sections = new ArrayList<>();
        String currentHeading = docTitle;
        StringBuilder body = new StringBuilder();

        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            if (BRACKET_HEADING.matcher(trimmed).matches()) {
                flushGroupedSection(sections, currentHeading, body.toString());
                body.setLength(0);
                currentHeading = joinNonBlank(" / ", docTitle, trimmed);
                continue;
            }
            body.append(line).append('\n');
        }
        flushGroupedSection(sections, currentHeading, body.toString());
        return sections;
    }

    private static void flushGroupedSection(List<Section> sections, String heading, String body) {
        for (String paragraphGroup : groupParagraphs(splitParagraphs(body))) {
            sections.add(new Section(heading, null, paragraphGroup));
        }
    }

    private static List<String> groupParagraphs(List<String> paragraphs) {
        List<String> groups = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (!StringUtils.hasText(paragraph)) {
                continue;
            }
            if (!current.isEmpty() && current.length() + paragraph.length() + 2 > SOFT_MAX_CHARS) {
                groups.add(current.toString().trim());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(paragraph);
        }
        if (!current.isEmpty()) {
            groups.add(current.toString().trim());
        }
        return groups;
    }

    private static List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String part : text.split("\\n\\s*\\n")) {
            String paragraph = part.trim();
            if (StringUtils.hasText(paragraph)) {
                paragraphs.add(paragraph);
            }
        }
        return paragraphs;
    }

    private static void addOverflowAware(List<String> chunks, String chunk, String path) {
        String normalized = normalize(chunk);
        if (!StringUtils.hasText(normalized)) {
            return;
        }
        if (normalized.length() <= HARD_MAX_CHARS) {
            chunks.add(normalized);
            return;
        }

        String body = stripLeadingSourceLines(normalized);
        for (String part : splitSentencesForOverflow(body)) {
            chunks.add(withHeader(path, null, part));
        }
    }

    private static List<String> splitSentencesForOverflow(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : text.split("(?<=[。！？!?；;])")) {
            String trimmed = sentence.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            if (!current.isEmpty() && current.length() + trimmed.length() > SOFT_MAX_CHARS) {
                result.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(trimmed);
        }
        if (!current.isEmpty()) {
            result.add(current.toString().trim());
        }
        if (result.isEmpty()) {
            result.add(text.trim());
        }
        return result;
    }

    private static String stripLeadingSourceLines(String text) {
        return text.replaceFirst("(?s)^来源：.*?\\n内容：\\n", "").trim();
    }

    private static String withHeader(String path, String heading, String body) {
        String source = joinNonBlank(" / ", path, heading);
        return "来源：" + source + "\n内容：\n" + body.trim();
    }

    private static List<Section> sectionsByPattern(String text, Pattern headingPattern) {
        Matcher matcher = headingPattern.matcher(text);
        List<HeadingHit> hits = new ArrayList<>();
        while (matcher.find()) {
            String heading = matcher.group().trim();
            hits.add(new HeadingHit(1, heading, matcher.start(), matcher.end()));
        }

        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            HeadingHit current = hits.get(i);
            int nextStart = i + 1 < hits.size() ? hits.get(i + 1).start() : text.length();
            String body = text.substring(current.end(), nextStart).trim();
            sections.add(new Section(current.heading(), current.heading(), body));
        }
        return sections;
    }

    private static List<HeadingHit> markdownHeadingHits(String text) {
        Matcher matcher = MARKDOWN_HEADING.matcher(text);
        List<HeadingHit> hits = new ArrayList<>();
        while (matcher.find()) {
            int level = matcher.group(1).length();
            String title = matcher.group(2).trim();
            hits.add(new HeadingHit(level, title, matcher.start(), matcher.end()));
        }
        return hits;
    }

    private static String firstMarkdownTitle(String text, String fallback) {
        Matcher matcher = MARKDOWN_HEADING.matcher(text);
        if (matcher.find()) {
            return matcher.group(2).trim();
        }
        return fallback == null ? "unknown" : fallback;
    }

    private static String firstQuotedLineContaining(String text, String needle) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.contains(needle)) {
                return trimmed;
            }
        }
        return "";
    }

    private static String stripQuoteMarker(String line) {
        return line == null ? "" : line.replaceFirst("^>\\s*", "").trim();
    }

    private static String joinNonBlank(String delimiter, String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (StringUtils.hasText(part)) {
                kept.add(part.trim());
            }
        }
        return String.join(delimiter, kept);
    }

    private record Section(String path, String heading, String body) {
    }

    private record HeadingHit(int level, String title, int start, int end) {
        String heading() {
            return title;
        }
    }
}
