package org.example.aichat.release;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import jakarta.annotation.PreDestroy;

@Service @DependsOn("releaseMigrations")
public class McpRegistry {
  private final StoryStore stories;private final RuntimeMode mode;private final ObjectMapper json;private final byte[] key;
  private final Map<String,McpClient> clients=new ConcurrentHashMap<>();
  public McpRegistry(StoryStore stories,RuntimeMode mode,ObjectMapper json)throws Exception{
    this.stories=stories;this.mode=mode;this.json=json;
    if(mode.cloud())key=Base64.getDecoder().decode(mode.value("encryption-key",""));
    else{Path file=mode.data().resolve(".mcp-key");if(!Files.exists(file)){Files.writeString(file,Secrets.token(),StandardOpenOption.CREATE_NEW);try{Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}}key=Base64.getUrlDecoder().decode(Files.readString(file));}
  }
  private String owner(int user,String id){return "mcp:"+user+":"+id;}
  private ObjectNode config(int user,String id){var rows=stories.db().queryForList("SELECT config_json FROM companion_mcp WHERE user_id=? AND id=?",user,id);if(rows.isEmpty())throw new IllegalArgumentException("MCP 服务不存在");try{return (ObjectNode)json.readTree(Secrets.decrypt((String)rows.get(0).get("config_json"),key,owner(user,id)));}catch(Exception e){throw new IllegalStateException("MCP 配置无法解密",e);}}
  public List<ObjectNode> list(int user){var result=new ArrayList<ObjectNode>();for(var row:stories.db().queryForList("SELECT id FROM companion_mcp WHERE user_id=? ORDER BY id",user)){var c=config(user,(String)row.get("id"));if(!c.hasNonNull("transport"))c.put("transport","sse");c.put("hasSecrets",!c.path("headers").isEmpty()||!c.path("env").isEmpty());c.remove(List.of("headers","env"));c.put("connected",clients.containsKey(owner(user,c.path("id").asText())));result.add(c);}return result;}
  public synchronized void save(int user,JsonNode input){
    var c=(ObjectNode)input.deepCopy();c.remove("discoveredToolNames");String id=c.path("id").asText(UUID.randomUUID().toString());if(!id.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException("MCP 标识不正确");c.put("id",id);
    if(c.path("name").asText().isBlank()||c.toString().length()>32768)throw new IllegalArgumentException("MCP 名称为空或配置过大");
    boolean exists=stories.db().queryForObject("SELECT COUNT(*) FROM companion_mcp WHERE user_id=? AND id=?",Integer.class,user,id)>0;var old=exists?config(user,id):null;
    // Missing transport is the legacy SSE contract. The new UI explicitly
    // chooses HTTP; loading or editing an old record must not reinterpret it.
    String transport=c.path("transport").asText(old==null?"sse":old.path("transport").asText("sse"));if(transport.equals("streamable-http"))transport="http";c.put("transport",transport);
    if("prime".equals(id)&&c.path("builtin").asBoolean()){transport="builtin";c.put("transport","builtin");c.remove(List.of("command","headers","env"));}
    if("builtin".equals(transport)&&!"prime".equals(id))throw new IllegalArgumentException("内置工具标识不正确");
    if("stdio".equals(transport)){if(mode.cloud())throw new IllegalArgumentException("商业版不允许运行用户提交的服务器命令；请连接 HTTP MCP 服务");if(!c.path("command").isArray()||c.path("command").isEmpty())throw new IllegalArgumentException("stdio 缺少命令数组");}
    else if(Set.of("http","sse").contains(transport)){if(mode.cloud())NetworkPolicy.publicHttps(c.path("url").asText());else{try{NetworkPolicy.requireLoopback(c.path("url").asText());}catch(Exception e){NetworkPolicy.publicHttps(c.path("url").asText());}}}
    else if(!"builtin".equals(transport))throw new IllegalArgumentException("MCP 传输方式支持 HTTP、旧版 SSE 和本机 stdio");
    if(exists){
      String previous=old.path("transport").asText("sse");
      boolean sameRemote=Set.of("http","streamable-http","sse").contains(previous)&&Set.of("http","sse").contains(transport)&&old.path("url").asText().equals(c.path("url").asText());
      boolean sameCommand=previous.equals("stdio")&&transport.equals("stdio")&&old.path("command").equals(c.path("command"));
      if((sameRemote||sameCommand||previous.equals("builtin")&&transport.equals("builtin"))&&old.has("discoveredToolNames"))c.set("discoveredToolNames",old.path("discoveredToolNames"));
      // Blank fields retain secrets only inside the same endpoint/command scope.
      // Changing a URL must never carry Authorization to another MCP service.
      if(!c.has("headers")||c.path("headers").isEmpty()){if(sameRemote)c.set("headers",old.path("headers"));else c.remove("headers");}
      if(!c.has("env")||c.path("env").isEmpty()){if(sameCommand)c.set("env",old.path("env"));else c.remove("env");}
    }
    if(!Set.of("http","sse").contains(transport))c.remove("headers");
    if(!transport.equals("stdio"))c.remove("env");
    if(!c.has("enabled"))c.put("enabled",false);if(!c.has("allowModelCalls"))c.put("allowModelCalls",false);
    stories.db().update("INSERT INTO companion_mcp(user_id,id,config_json) VALUES(?,?,?) ON CONFLICT(user_id,id) DO UPDATE SET config_json=excluded.config_json",user,id,Secrets.encrypt(c.toString(),key,owner(user,id)));disconnect(user,id);
  }
  public void delete(int user,String id){disconnect(user,id);stories.db().update("DELETE FROM companion_mcp WHERE user_id=? AND id=?",user,id);}
  private void disconnect(int user,String id){var client=clients.remove(owner(user,id));if(client!=null)try{client.close();}catch(Exception ignored){}}
  private synchronized McpClient client(int user,String id){
    String owner=owner(user,id);var cfg=config(user,id);if(!cfg.path("enabled").asBoolean())throw new IllegalArgumentException("MCP 服务未启用");
    return clients.computeIfAbsent(owner,unused->{
      McpTransport transport;
      if("stdio".equals(cfg.path("transport").asText())){var cmd=new ArrayList<String>();cfg.path("command").forEach(i->cmd.add(i.asText()));var b=new StdioMcpTransport.Builder().command(cmd).logEvents(false);var env=new HashMap<String,String>();cfg.path("env").properties().forEach(e->env.put(e.getKey(),e.getValue().asText()));if(!env.isEmpty())b.environment(env);transport=b.build();}
      else{var headers=new HashMap<String,String>();cfg.path("headers").properties().forEach(e->headers.put(e.getKey(),e.getValue().asText()));transport="sse".equals(cfg.path("transport").asText("sse"))?new SseMcpTransport(cfg.path("url").asText(),headers,mode,json):new HttpMcpTransport(cfg.path("url").asText(),headers,mode,json);}
      return connect(transport,owner);
    });
  }
  static McpClient connect(McpTransport transport,String owner){
    try{return new DefaultMcpClient.Builder().transport(transport).key(owner).clientName("story-space").initializationTimeout(Duration.ofSeconds(15)).toolExecutionTimeout(Duration.ofSeconds(20)).build();}
    catch(RuntimeException error){
      // Keep remote HTTP failures distinct from this application's login status.
      McpConnectionFailure safe=transport instanceof SseMcpTransport sse?sse.connectionFailure(error):transport instanceof HttpMcpTransport http?http.connectionFailure(error):null;
      try{transport.close();}catch(Exception ignored){}
      if(safe!=null)throw safe;throw error;
    }
  }
  public List<Map<String,Object>> tools(int user,String id){var cfg=config(user,id);if("builtin".equals(cfg.path("transport").asText())){if(!cfg.path("enabled").asBoolean())throw new IllegalArgumentException("MCP 服务未启用");return List.of(Map.of("serverId",id,"name","isPrime","description","判断一个正整数是否是质数","parameters",Map.of("type","object","properties",Map.of("number",Map.of("type","integer")),"required",List.of("number"))));}var discovered=client(user,id).listTools().stream().map(spec->Map.<String,Object>of("serverId",id,"name",spec.name(),"description",Objects.toString(spec.description(),""),"parameters",schema(spec.parameters()))).toList();rememberTools(user,id,cfg,discovered);return discovered;}
  private synchronized void rememberTools(int user,String id,JsonNode source,List<Map<String,Object>> tools){
    var current=config(user,id);
    // A late discovery must not populate a different endpoint after the user edits it.
    for(String field:List.of("transport","url","command"))if(!current.path(field).equals(source.path(field)))return;
    var names=json.createArrayNode();tools.stream().map(t->Objects.toString(t.get("name"),"")).filter(n->!n.isBlank()&&n.length()<=256).distinct().limit(100).forEach(names::add);
    if(names.equals(current.path("discoveredToolNames")))return;
    current.set("discoveredToolNames",names);stories.db().update("UPDATE companion_mcp SET config_json=? WHERE user_id=? AND id=?",Secrets.encrypt(current.toString(),key,owner(user,id)),user,id);
  }
  public List<Map<String,Object>> requestedTools(int user,String message){
    if(!McpInvocationRequest.explicit(message))return List.of();
    var requested=new ArrayList<Map<String,Object>>();
    for(var cfg:list(user)){
      var names=new LinkedHashSet<String>();if("builtin".equals(cfg.path("transport").asText()))names.add("isPrime");
      cfg.path("discoveredToolNames").forEach(n->names.add(n.asText()));
      var mentioned=names.stream().filter(name->McpInvocationRequest.names(message,name)).toList();
      for(String name:mentioned)requested.add(Map.of("serverId",cfg.path("id").asText(),"name",name));
      if(mentioned.isEmpty()&&McpInvocationRequest.names(message,cfg.path("name").asText()))requested.add(Map.of("serverId",cfg.path("id").asText(),"name",""));
    }
    return requested;
  }
  private Map<String,Object> schema(Object value){
    if(value==null)return Map.of("type","object","properties",Map.of());String type=value.getClass().getSimpleName();var out=new LinkedHashMap<String,Object>();
    try{
      if(type.contains("Object")){out.put("type","object");var properties=(Map<?,?>)value.getClass().getMethod("properties").invoke(value);var map=new LinkedHashMap<String,Object>();for(var e:properties.entrySet())map.put(e.getKey().toString(),schema(e.getValue()));out.put("properties",map);out.put("required",value.getClass().getMethod("required").invoke(value));}
      else if(type.contains("Array")){out.put("type","array");out.put("items",schema(value.getClass().getMethod("items").invoke(value)));}
      else{out.put("type",type.contains("Integer")?"integer":type.contains("Number")?"number":type.contains("Boolean")?"boolean":"string");}
    }catch(Exception e){return Map.of("type","object","properties",Map.of());}return out;
  }
  public String execute(int user,String id,String tool,JsonNode arguments){
    McpToolArguments.validate(null,arguments);
    if("builtin".equals(config(user,id).path("transport").asText())){if(!config(user,id).path("enabled").asBoolean()||!"isPrime".equals(tool))throw new IllegalArgumentException("工具不可用");var number=arguments.path("number");if(!number.isIntegralNumber()||!number.canConvertToLong()||number.asLong()<1)throw new IllegalArgumentException("number 必须为正整数（64 位以内）");long value=number.asLong();return value+(java.math.BigInteger.valueOf(value).isProbablePrime(100)?" 是质数":" 不是质数");}
    if(arguments.toString().length()>16000)throw new IllegalArgumentException("MCP 参数过大");
    return executeClient(client(user,id),tool,arguments);
  }
  static String executeClient(McpClient client,String tool,JsonNode arguments){
    McpToolArguments.validate(null,arguments);
    var spec=client.listTools().stream().filter(t->t.name().equals(tool)).findFirst().orElseThrow(()->new IllegalArgumentException("MCP 工具不存在"));
    McpToolArguments.validate(spec.parameters(),arguments);
    try{String output=client.executeTool(ToolExecutionRequest.builder().id(UUID.randomUUID().toString()).name(tool).arguments(arguments.toString()).build()).resultText();return PromptBudget.trim(output,2400);}
    catch(ToolArgumentsException error){throw new IllegalArgumentException("MCP 工具拒绝了参数。请按工具参数说明检查必填字段、类型和值范围后重试。");}
    catch(ToolExecutionException error){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"MCP 工具执行失败。请核对参数与服务权限后重试；若仍失败，请联系该工具服务维护者。");}
    catch(RuntimeException error){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"MCP 工具调用未完成。请重新连接服务、核对参数后重试。");}
  }
  public List<Map<String,Object>> modelTools(int user,String message){var all=new ArrayList<Map<String,Object>>();for(var cfg:list(user))if((!cfg.path("id").asText().equals("prime")||message.matches("(?is).*(质数|素数|isprime|prime).*"))&&cfg.path("enabled").asBoolean()&&cfg.path("allowModelCalls").asBoolean())try{for(var tool:tools(user,cfg.path("id").asText()))if(all.size()<20){var exposed=new LinkedHashMap<>(tool);exposed.put("serverName",cfg.path("name").asText());all.add(exposed);}}catch(Exception ignored){}return all;}
  public void stopUser(int user){for(var cfg:list(user))disconnect(user,cfg.path("id").asText());}
  @PreDestroy public void close(){clients.values().forEach(c->{try{c.close();}catch(Exception ignored){}});clients.clear();}
}
