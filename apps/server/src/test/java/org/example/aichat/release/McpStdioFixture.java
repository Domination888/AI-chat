package org.example.aichat.release;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;
public class McpStdioFixture {
  public static void main(String[] args)throws Exception{
    var json=new ObjectMapper();var reader=new BufferedReader(new InputStreamReader(System.in));String line;
    while((line=reader.readLine())!=null){var input=json.readTree(line);if(!input.has("id"))continue;String method=input.path("method").asText();Object result;
      switch(method){
        case "initialize"->result=Map.of("protocolVersion",input.path("params").path("protocolVersion").asText("2024-11-05"),"capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","isolated-fixture","version","1.0"));
        case "tools/list"->result=Map.of("tools",List.of(Map.of("name","add","description","Add two fixture numbers","inputSchema",Map.of("type","object","properties",Map.of("a",Map.of("type","integer"),"b",Map.of("type","integer")),"required",List.of("a","b")))));
        case "tools/call"->{var a=input.path("params").path("arguments");result=Map.of("content",List.of(Map.of("type","text","text",String.valueOf(a.path("a").asInt()+a.path("b").asInt()))),"isError",false);}
        default->result=Map.of();
      }
      System.out.println(json.writeValueAsString(Map.of("jsonrpc","2.0","id",json.convertValue(input.get("id"),Object.class),"result",result)));System.out.flush();
    }
  }
}
