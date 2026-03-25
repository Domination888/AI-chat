package org.example.primemcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 本地 MCP Server —— 质数判断工具
 *
 * 通过 stdio 与 MCP Client 通信（JSON-RPC 2.0，换行分隔）。
 * 注意：所有日志输出到 stderr，stdout 专用于 JSON-RPC 协议消息。
 *
 * 提供工具：isPrime(number: integer) → 判断是否为质数
 */
public class PrimeMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        // 强制 stdout/stderr 使用 UTF-8，防止 Windows 平台默认 GBK 导致中文乱码
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        System.err.println("[PrimeMcpServer] 启动，等待 MCP Client 连接...");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            try {
                JsonNode msg = MAPPER.readTree(line);
                String response = handle(msg);
                if (response != null) {
                    System.out.println(response);
                    System.out.flush();
                }
            } catch (Exception e) {
                System.err.println("[PrimeMcpServer] 处理消息出错: " + e.getMessage());
            }
        }
        System.err.println("[PrimeMcpServer] 连接关闭，退出。");
    }

    private static String handle(JsonNode msg) throws Exception {
        // 通知消息（无 id）—— 不需要响应
        if (!msg.has("id") || msg.get("id").isNull()) {
            return null;
        }

        JsonNode id = msg.get("id");
        String method = msg.has("method") ? msg.get("method").asText("") : "";
        JsonNode params = msg.has("params") ? msg.get("params") : MAPPER.createObjectNode();

        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);

        switch (method) {
            case "initialize" -> {
                ObjectNode result = MAPPER.createObjectNode();
                result.put("protocolVersion", "2024-11-05");
                ObjectNode caps = MAPPER.createObjectNode();
                caps.set("tools", MAPPER.createObjectNode());
                result.set("capabilities", caps);
                ObjectNode info = MAPPER.createObjectNode();
                info.put("name", "prime-checker");
                info.put("version", "1.0.0");
                result.set("serverInfo", info);
                response.set("result", result);
                System.err.println("[PrimeMcpServer] 初始化完成");
            }
            case "tools/list" -> {
                // 构建 isPrime 工具的 JSON Schema
                ObjectNode schema = MAPPER.createObjectNode();
                schema.put("type", "object");
                ObjectNode props = MAPPER.createObjectNode();
                ObjectNode numProp = MAPPER.createObjectNode();
                numProp.put("type", "integer");
                numProp.put("description", "要判断的正整数");
                props.set("number", numProp);
                schema.set("properties", props);
                ArrayNode required = MAPPER.createArrayNode();
                required.add("number");
                schema.set("required", required);
                schema.put("additionalProperties", false);

                ObjectNode tool = MAPPER.createObjectNode();
                tool.put("name", "isPrime");
                tool.put("description", "判断一个正整数是否是质数");
                tool.set("inputSchema", schema);

                ObjectNode result = MAPPER.createObjectNode();
                result.set("tools", MAPPER.createArrayNode().add(tool));
                response.set("result", result);
            }
            case "tools/call" -> {
                String toolName = params.has("name") ? params.get("name").asText() : "";
                JsonNode arguments = params.has("arguments") ? params.get("arguments") : MAPPER.createObjectNode();

                ObjectNode result = MAPPER.createObjectNode();
                ArrayNode content = MAPPER.createArrayNode();

                if ("isPrime".equals(toolName)) {
                    long number = arguments.has("number") ? arguments.get("number").asLong() : 0L;
                    boolean prime = isPrime(number);
                    String text = number + (prime ? " 是质数" : " 不是质数");
                    System.err.println("[PrimeMcpServer] 工具调用: isPrime(" + number + ") = " + prime);
                    content.add(MAPPER.createObjectNode().put("type", "text").put("text", text));
                    result.put("isError", false);
                } else {
                    content.add(MAPPER.createObjectNode().put("type", "text").put("text", "未知工具: " + toolName));
                    result.put("isError", true);
                }

                result.set("content", content);
                response.set("result", result);
            }
            case "ping" -> response.set("result", MAPPER.createObjectNode());
            default -> {
                ObjectNode error = MAPPER.createObjectNode();
                error.put("code", -32601);
                error.put("message", "Method not found: " + method);
                response.set("error", error);
            }
        }

        return MAPPER.writeValueAsString(response);
    }

    private static boolean isPrime(long n) {
        if (n < 2) return false;
        if (n == 2) return true;
        if (n % 2 == 0) return false;
        for (long i = 3; i * i <= n; i += 2) {
            if (n % i == 0) return false;
        }
        return true;
    }
}
