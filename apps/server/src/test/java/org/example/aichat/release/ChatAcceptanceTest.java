package org.example.aichat.release;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
class ChatAcceptanceTest {
 @Test void attachmentRejection()throws Exception{check(false,false);}
 @Test void failureAfterInputCommit()throws Exception{check(true,false);}
 @Test void proactiveFailure()throws Exception{check(false,true);}
 void check(boolean saved,boolean proactive)throws Exception{
  var stories=mock(StoryStore.class);var files=mock(Attachments.class);var streams=mock(Streams.class);var mode=mock(RuntimeMode.class);
  when(stories.db()).thenReturn(mock(JdbcTemplate.class));when(stories.owned(1,"c")).thenReturn(Map.of("role_id",2));when(stories.role(1,2)).thenReturn(Map.of("id",2));when(streams.lock("w")).thenReturn(new ReentrantLock());
  if(saved){when(files.store(1,"c",List.of("bad"))).thenReturn(List.of());when(stories.message("c","user","hi")).thenReturn(10L);}else when(files.store(1,"c",List.of("bad"))).thenThrow(new IllegalArgumentException("image rejected"));
  when(stories.message("c","assistant","")).thenThrow(new IllegalStateException("downstream failed"));
  var api=new ChatApi(stories,mock(ModelConnections.class),mock(OpenAiTransport.class),streams,null,files,null,null,mode,null,null,null,null,null,null,null,null);
  Map<String,Object> error=new HashMap<>();boolean[] done={false};var sink=new ChatApi.EventSink(){public void send(String event,Object value){if(event.equals("error"))error.putAll((Map<String,Object>)value);}public void complete(){done[0]=true;}};
  var run=ChatApi.class.getDeclaredMethod("run",ChatApi.Request.class,String.class,int.class,Streams.Active.class,ChatApi.EventSink.class,boolean.class,String.class,Map.class);run.setAccessible(true);
  run.invoke(api,new ChatApi.Request("c",2,"hi",List.of("bad"),"text",null,null,false,null),"hi",1,new Streams.Active(1,"w",new OpenAiTransport.Control(),new CompletableFuture<>()),sink,proactive,"",new LinkedHashMap<>());
  assertEquals(saved||proactive,error.get("accepted"));assertNotNull(error.get("message"));assertTrue(done[0]);if(saved)verify(stories).message("c","user","hi");else verify(stories,never()).message("c","user","hi");
 }
}
