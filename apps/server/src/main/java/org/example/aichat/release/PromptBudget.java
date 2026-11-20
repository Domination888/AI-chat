package org.example.aichat.release;

import java.util.*;

/** Conservative text budget for the standard 8K local pack; the newest user input is never cut. */
public final class PromptBudget {
  private PromptBudget() {}
  public static int tokens(String value){int count=0;for(int cp:value.codePoints().toArray())count+=cp>255?2:1;return (count+1)/2;}
  public static String trim(String text,int budget){var out=new StringBuilder();int count=0;for(int cp:text.codePoints().toArray()){int next=cp>255?2:1;if(count+next>budget*2)break;out.appendCodePoint(cp);count+=next;}return out.toString();}
  public static List<Map<String,Object>> assemble(String system,List<Map<String,Object>> previous,String message,Object current){
    return assemble(system,previous,message,current,2400);
  }
  public static List<Map<String,Object>> assemble(String system,List<Map<String,Object>> previous,String message,Object current,int systemBudget){
    if(tokens(message)>3000)throw new IllegalArgumentException("单次内容过长，请拆成几段发送");
    String instruction=trim(system,Math.max(0,Math.min(2800,systemBudget)));int available=6000-tokens(instruction)-tokens(message)-300;
    var kept=new ArrayList<Map<String,Object>>();
    for(int i=previous.size()-1;i>=0;i--){var row=previous.get(i);String text=Objects.toString(row.get("content"),"");int size=tokens(text)+20;if(size>available)break;available-=size;kept.add(Map.of("role",row.get("sender"),"content",text));}
    Collections.reverse(kept);var result=new ArrayList<Map<String,Object>>();result.add(Map.of("role","system","content",instruction));result.addAll(kept);result.add(Map.of("role","user","content",current));return result;
  }
}
