package org.example.aichat.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.example.aichat.release.ChatApi.EventSink;

final class ChatToolRound {
  private final OpenAiTransport models;
  private final McpRegistry mcp;
  private final ObjectMapper json;
  private final Quota quota;
  private final ChatTurnPrompt.ContextWriter saveContext;

  ChatToolRound(OpenAiTransport models, McpRegistry mcp, ObjectMapper json, Quota quota, ChatTurnPrompt.ContextWriter saveContext) {
    this.models = models;
    this.mcp = mcp;
    this.json = json;
    this.quota = quota;
    this.saveContext = saveContext;
  }

  List<Map<String,Object>> run(int user,ModelConnections.Connection connection,List<Map<String,Object>> messages,List<Map<String,Object>> tools,EventSink sink,Long history,String conversation,Map<String,Object> metadata)throws Exception {
    var calls=new ArrayList<Map<String,Object>>();var planning=new LinkedHashMap<String,Object>();planning.put("available",tools.size());planning.put("phase","planning");metadata.put("toolPlanning",planning);
    try {
      var result=models.tools(connection,messages,tools,(tool,args)->{
        String server=(String)tool.get("serverId"),name=(String)tool.get("name");var started=ToolExecutionTrace.started(tool,args);
        sink.send("tool_status",started);String output;
        try{output=mcp.execute(user,server,name,json.valueToTree(args));}
        catch(RuntimeException failure){
          var record=ToolExecutionTrace.failed(started);calls.add(record);metadata.put("toolCalls",calls);
          try{saveContext.save(history,conversation,metadata);}catch(Exception storage){throw new IllegalStateException("工具执行记录暂时无法保存",storage);}
          sink.send("tool_status",record);throw failure;
        }
        var record=ToolExecutionTrace.succeeded(started,output);calls.add(record);metadata.put("toolCalls",calls);
        try{saveContext.save(history,conversation,metadata);}catch(Exception storage){throw new IllegalStateException("工具执行记录暂时无法保存",storage);}
        sink.send("tool_status",record);return output;
      },usage->quota.record(user,connection.custom(),usage));
      planning.put("phase",calls.isEmpty()?"not_called":"completed");saveContext.save(history,conversation,metadata);
      var event=new LinkedHashMap<>(planning);event.put("kind","plan");sink.send("tool_status",event);return result;
    }catch(Exception failure){
      planning.put("phase","failed");planning.put("message",failure instanceof UpstreamHttpFailure?failure.getMessage():failure instanceof OpenAiTransport.ToolCallLimitException?failure.getMessage():"工具规划或调用未完成，请检查工具配置，或把任务拆分后重试。");
      saveContext.save(history,conversation,metadata);var event=new LinkedHashMap<>(planning);event.put("kind","plan");try{sink.send("tool_status",event);}catch(Exception ignored){}throw failure;
    }
  }
}
