package org.example.aichat.release;

import com.fasterxml.jackson.databind.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class CapabilityApi {
  private final SearchRag search;private final McpRegistry mcp;private final SkillRuntime skills;private final CharacterProfiles characters;
  public CapabilityApi(SearchRag search,McpRegistry mcp,SkillRuntime skills,CharacterProfiles characters){this.search=search;this.mcp=mcp;this.skills=skills;this.characters=characters;}
  private int user(){return SecurityConfiguration.current().id();}
  @PostMapping("/api/v1/characters/shu/import") public Object character(@RequestBody JsonNode input){return characters.importShu(user(),input);}
  @PostMapping("/api/search") public Object search(@RequestBody JsonNode input)throws Exception{return search.search(user(),input.path("query").asText(),List.of(),p->{});}
  @GetMapping("/api/skills") public Object skills(){return skills.list(user());}
  @PostMapping("/api/skills") public void skill(@RequestBody JsonNode input){skills.save(user(),input);}
  @PutMapping("/api/skills/{name}") public void skill(@PathVariable String name,@RequestBody JsonNode input){((com.fasterxml.jackson.databind.node.ObjectNode)input).put("name",name);skills.save(user(),input);}
  @DeleteMapping("/api/skills/{name}") public void deleteSkill(@PathVariable String name){skills.delete(user(),name);}
  @PutMapping("/api/skills/{name}/toggle") public void toggle(@PathVariable String name){var skill=(com.fasterxml.jackson.databind.node.ObjectNode)skills.list(user()).stream().filter(s->s.path("name").asText().equals(name)).findFirst().orElseThrow();skill.put("enabled",!skill.path("enabled").asBoolean());skills.save(user(),skill);}
  @GetMapping("/api/mcp/servers") public Object servers(){return mcp.list(user());}
  @PostMapping("/api/mcp/servers") public void server(@RequestBody JsonNode input){mcp.save(user(),input);}
  @PutMapping("/api/mcp/servers/{id}") public void server(@PathVariable String id,@RequestBody JsonNode input){((com.fasterxml.jackson.databind.node.ObjectNode)input).put("id",id);mcp.save(user(),input);}
  @DeleteMapping("/api/mcp/servers/{id}") public void deleteServer(@PathVariable String id){mcp.delete(user(),id);}
  @GetMapping("/api/mcp/servers/{id}/tools") public Object tools(@PathVariable String id){return mcp.tools(user(),id);}
  @PostMapping("/api/mcp/servers/{id}/tools/{tool}") public Object execute(@PathVariable String id,@PathVariable String tool,@RequestBody JsonNode input){return Map.of("result",mcp.execute(user(),id,tool,input));}
  @PostMapping("/api/mcp/reload") public Object reload(){mcp.stopUser(user());return mcp.list(user());}
}
