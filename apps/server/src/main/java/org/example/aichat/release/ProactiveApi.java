package org.example.aichat.release;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
@RestController
public class ProactiveApi {
  private final ProactiveService service;public ProactiveApi(ProactiveService service){this.service=service;}
  private int user(){return SecurityConfiguration.current().id();}
  @PostMapping("/api/chat/proactive") public void register(@RequestBody Map<String,String> body){service.register(user(),body.get("conversationId"));}
  @DeleteMapping("/api/chat/proactive") public void unregister(@RequestBody Map<String,String> body){service.unregister(user(),body.get("conversationId"));}
  @GetMapping(value="/api/chat/proactive/stream",produces="text/event-stream") public SseEmitter subscribe(@RequestParam String conversationId){return service.subscribe(user(),conversationId);}
  @PostMapping("/api/chat/proactive/activity") public void touch(@RequestBody Map<String,String> body){service.touch(user(),body.get("conversationId"));}
  @PostMapping("/api/chat/proactive/trigger") public Object trigger(@RequestBody Map<String,String> body){return service.trigger(user(),body.get("conversationId"));}
  @PostMapping("/api/proactive-research/actions/run-now") public Object research(@RequestBody Map<String,String> body)throws Exception{return service.research(user(),body.get("conversationId"));}
  @GetMapping("/api/proactive-research/conversation/{id}") public Object candidates(@PathVariable String id){return service.candidates(user(),id);}
  @PostMapping("/api/proactive-research/candidates/{id}/feedback") public Object feedback(@PathVariable String id,@RequestBody Map<String,String> body){service.feedback(user(),id,body.get("feedback"));return Map.of("ok",true);}
}
