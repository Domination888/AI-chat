package org.example.aichat.release;

import java.util.regex.Pattern;

/** A narrow recognizer for direct execution requests, not mentions in stories or code questions. */
final class McpInvocationRequest {
  private McpInvocationRequest() {}
  static boolean explicit(String message) {
    String text=message.strip();
    if(text.matches("(?is).*(代码|伪代码|示例|如何|怎么|解释|讲解|实现|定义|假设|假如|故事|小说|台词|模拟|不要实际|不要真的).*"))return false;
    return text.matches("(?is)^(?:请(?:你)?|麻烦(?:你)?|帮我)?\\s*(?:(?:现在|实际|直接|真正|务必|再次|再)\\s*)*(?:调用|使用|执行|运行|用)\\s*.+")
        ||text.matches("(?is)^(?:please\\s+)?(?:call|invoke|execute|run|use)\\s+.+");
  }
  static boolean names(String message,String name) {
    if(name.isBlank())return false;
    return Pattern.compile("(?i)(?<![a-zA-Z0-9_])"+Pattern.quote(name)+"(?![a-zA-Z0-9_])").matcher(message).find();
  }
}
