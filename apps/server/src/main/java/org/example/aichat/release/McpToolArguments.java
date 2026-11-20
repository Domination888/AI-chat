package org.example.aichat.release;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.chat.request.json.*;
import java.util.*;

/** Validates the supported structural part of a discovered tool schema before sending arguments. */
final class McpToolArguments {
  private McpToolArguments(){}
  static void validate(JsonSchemaElement schema,JsonNode arguments){
    if(arguments==null||!arguments.isObject())throw new IllegalArgumentException("工具参数必须是 JSON 对象，例如 {\"字段名\": 值}。");
    validate(schema,arguments,"参数",0);
  }
  private static void validate(JsonSchemaElement schema,JsonNode value,String path,int depth){
    if(schema==null)return;
    if(depth>20)throw new IllegalArgumentException("工具参数结构过深，请简化后重试。");
    if(schema instanceof JsonAnyOfSchema alternatives){
      for(var choice:alternatives.anyOf())try{validate(choice,value,path,depth+1);return;}catch(IllegalArgumentException ignored){}
      throw new IllegalArgumentException("工具参数“"+path+"”不符合允许的类型，请查看工具参数说明。");
    }
    String type=type(schema);boolean valid=switch(type){case "对象(object)"->value.isObject();case "数组(array)"->value.isArray();case "字符串(string)"->value.isTextual();case "整数(integer)"->value.isIntegralNumber();case "数字(number)"->value.isNumber();case "布尔值(boolean)"->value.isBoolean();case "空值(null)"->value.isNull();default->true;};
    if(!valid)throw new IllegalArgumentException("工具参数“"+path+"”应为"+type+"。");
    if(schema instanceof JsonObjectSchema object){
      var properties=object.properties()==null?Map.<String,JsonSchemaElement>of():object.properties();
      for(String required:object.required()==null?List.<String>of():object.required())if(!value.has(required))throw new IllegalArgumentException("工具参数缺少必填字段“"+field(path,required)+"”（期望"+type(properties.get(required))+"），请补充后执行。");
      for(var entry:properties.entrySet())if(value.has(entry.getKey()))validate(entry.getValue(),value.get(entry.getKey()),field(path,entry.getKey()),depth+1);
    }else if(schema instanceof JsonArraySchema array)for(int index=0;index<value.size();index++)validate(array.items(),value.get(index),path+"["+index+"]",depth+1);
    // Reference/raw schemas remain the service's responsibility; do not guess a type or reject valid extensions.
  }
  private static String field(String parent,String name){String clean=name.replaceAll("[\\p{Cntrl}]","?");if(clean.length()>80)clean=clean.substring(0,80)+"…";return parent.equals("参数")?clean:parent+"."+clean;}
  private static String type(JsonSchemaElement schema){
    if(schema instanceof JsonObjectSchema)return "对象(object)";if(schema instanceof JsonArraySchema)return "数组(array)";if(schema instanceof JsonStringSchema||schema instanceof JsonEnumSchema)return "字符串(string)";if(schema instanceof JsonIntegerSchema)return "整数(integer)";if(schema instanceof JsonNumberSchema)return "数字(number)";if(schema instanceof JsonBooleanSchema)return "布尔值(boolean)";if(schema instanceof JsonNullSchema)return "空值(null)";return "工具声明的值";
  }
}
