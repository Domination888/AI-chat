package org.example.aichat.release;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class CompanionApi {
  private final CompanionService companion;private final AiDailyService daily;private final InterestInference inference;
  public CompanionApi(CompanionService companion,AiDailyService daily,InterestInference inference){this.companion=companion;this.daily=daily;this.inference=inference;}
  private int user(){return SecurityConfiguration.current().id();}
  @GetMapping("/api/v1/me/companion") public Object settings(){return companion.settings(user());}
  @PutMapping("/api/v1/me/companion") public Object save(@RequestBody JsonNode input){return companion.save(user(),input);}
  @GetMapping("/api/world-context") public Object world(){return companion.snapshot(user());}
  @PostMapping("/api/world-context/location") public Object locate(@RequestBody JsonNode input)throws Exception{return companion.locate(user(),input,false);}
  @PostMapping("/api/world-context/location/ip") public Object ip()throws Exception{return companion.locate(user(),new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),true);}
  @PutMapping("/api/world-context/location") public Object manual(@RequestBody JsonNode input){return companion.manualLocation(user(),input);}
  @PostMapping("/api/world-context/weather") public Object weather(){return companion.refreshWeather(user());}
  @DeleteMapping("/api/world-context/location") public void clear(){companion.clearLocation(user());}
  @GetMapping("/api/proactive-research/interests") public Object interests(){return companion.interests(user());}
  @PostMapping("/api/proactive-research/interests") public Object add(@RequestBody JsonNode input){return companion.add(user(),input,"manual");}
  @PutMapping("/api/proactive-research/interests/{id}") public void update(@PathVariable String id,@RequestBody JsonNode input){companion.updateInterest(user(),id,input);}
  @DeleteMapping("/api/proactive-research/interests/{id}") public void delete(@PathVariable String id){companion.deleteInterest(user(),id);}
  @PostMapping("/api/proactive-research/actions/refresh-interests") public Object refresh()throws Exception{return Map.of("added",inference.refresh(user()));}
  @GetMapping("/api/v1/me/ai-daily") public Object daily(){return daily.current(user());}
  @PostMapping("/api/v1/me/ai-daily/refresh") public Object refreshDaily()throws Exception{return daily.refresh(user());}
  @PostMapping("/api/v1/me/ai-daily/read") public void read(){daily.read(user());}
}
