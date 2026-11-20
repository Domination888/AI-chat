package org.example.aichat.release;

import java.util.*;

/** Display-only records; only a real execution callback can create an outcome. */
final class ToolExecutionTrace {
  private ToolExecutionTrace() {}
  static Map<String,Object> started(Map<String,Object> tool,Map<String,Object> arguments) {
    var row=new LinkedHashMap<String,Object>();row.put("id",UUID.randomUUID().toString());
    row.put("name",Objects.toString(tool.get("name"),""));row.put("serverId",Objects.toString(tool.get("serverId"),""));row.put("serverName",Objects.toString(tool.getOrDefault("serverName",tool.get("serverId")),""));
    row.put("phase","running");row.put("arguments",safe(arguments,0));return row;
  }
  static Map<String,Object> succeeded(Map<String,Object> started,String result) {
    var row=new LinkedHashMap<>(started);row.put("phase","succeeded");row.put("result",PromptBudget.trim(Objects.toString(result,""),600));return row;
  }
  static Map<String,Object> failed(Map<String,Object> started) {
    var row=new LinkedHashMap<>(started);row.put("phase","failed");row.put("error","工具执行失败。请核对参数、连接和服务权限后重试。");return row;
  }
  static List<Map<String,Object>> constrain(List<Map<String,Object>> messages,Map<String,Object> metadata) {
    var calls=(List<?>)metadata.getOrDefault("toolCalls",List.of());
    String facts=calls.isEmpty()?"本轮没有实际执行任何 MCP 工具。":calls.stream().map(c->{var row=(Map<?,?>)c;return Objects.toString(row.get("name"),"")+"："+("succeeded".equals(row.get("phase"))?"执行成功":"执行失败");}).reduce((a,b)->a+"；"+b).orElse("");
    var result=new ArrayList<>(messages);result.add(Map.of("role","system","content","【本轮 MCP 执行事实】"+facts+"只有本轮实际执行成功且提供了结果的工具才可声称已调用；历史对话中的工具调用不属于本轮。没有实际执行时必须明确说未执行，不得把自己的推算或猜测说成工具结果。"));return result;
  }
  private static Object safe(Object value,int depth) {
    if(depth>4)return "[内容过深，已省略]";
    if(value instanceof Map<?,?> map){var out=new LinkedHashMap<String,Object>();int count=0;for(var entry:map.entrySet()){
      if(++count>24){out.put("…","其余参数已省略");break;}String key=entry.getKey().toString();
      out.put(key,key.matches("(?i).*(password|passwd|secret|token|authorization|api.?key|cookie|credential).*" )?"[已隐藏]":safe(entry.getValue(),depth+1));
    }return out;}
    if(value instanceof Collection<?> list){var out=new ArrayList<>();int count=0;for(var item:list){if(++count>24){out.add("[其余参数已省略]");break;}out.add(safe(item,depth+1));}return out;}
    if(value instanceof String text)return PromptBudget.trim(text,160);
    return value;
  }
}
