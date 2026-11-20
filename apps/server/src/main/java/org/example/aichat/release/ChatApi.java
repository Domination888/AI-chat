package org.example.aichat.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.aichat.rp.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.time.Instant;

@RestController
public class ChatApi {
  private final StoryStore stories;private final ModelConnections connections;private final OpenAiTransport models;private final Streams streams;private final Attachments attachments;private final Quota quota;private final ObjectMapper json;private final RuntimeMode mode;private final VoiceGateway voice;private final AiDailyService daily;private final SkillRuntime skills;private final InterestInference inference;private final McpRegistry mcp;
  private final ChatTurnPrompt prompt;
  private final ChatToolRound toolRound;
  public ChatApi(StoryStore stories,ModelConnections connections,OpenAiTransport models,Streams streams,MemoryIndex memory,Attachments attachments,Quota quota,ObjectMapper json,RuntimeMode mode,VoiceGateway voice,CompanionService companion,AiDailyService daily,SearchRag search,SkillRuntime skills,CharacterProfiles characters,InterestInference inference,McpRegistry mcp){this.stories=stories;this.connections=connections;this.models=models;this.streams=streams;this.attachments=attachments;this.quota=quota;this.json=json;this.mode=mode;this.voice=voice;this.daily=daily;this.skills=skills;this.inference=inference;this.mcp=mcp;
    this.prompt=new ChatTurnPrompt(stories,models,memory,attachments,quota,json,mode,companion,daily,search,skills,characters,this::saveContext);
    this.toolRound=new ChatToolRound(models,mcp,json,quota,this::saveContext);
  }
  public record Request(String conversationId,int roleId,String message,List<String> images,String inputMode,String audioBase64,String audioFormat,boolean search,String ttsVoiceId,String dailyIssueDate,Long continuationHistoryId){
    public Request(String conversationId,int roleId,String message,List<String> images,String inputMode,String audioBase64,String audioFormat,boolean search,String ttsVoiceId,String dailyIssueDate){this(conversationId,roleId,message,images,inputMode,audioBase64,audioFormat,search,ttsVoiceId,dailyIssueDate,null);}
    public Request(String conversationId,int roleId,String message,List<String> images,String inputMode,String audioBase64,String audioFormat,boolean search,String ttsVoiceId){this(conversationId,roleId,message,images,inputMode,audioBase64,audioFormat,search,ttsVoiceId,null,null);}
  }
  public interface EventSink {void send(String event,Object value);void complete();}
  public record UserActivity(int user,String conversation){}
  @org.springframework.beans.factory.annotation.Autowired private org.springframework.context.ApplicationEventPublisher events;
  @PostMapping(value="/api/chat",produces="text/event-stream") public SseEmitter chat(@RequestBody Request req)throws Exception {
    int user=SecurityConfiguration.current().id();String message=Objects.toString(req.message(),"");
    if(stories.db().queryForObject("SELECT COUNT(*) FROM conversation WHERE id=?",Integer.class,req.conversationId())==0)stories.create(user,req.conversationId(),req.roleId(),null,new RpState());
    stories.owned(user,req.conversationId());events.publishEvent(new UserActivity(user,req.conversationId()));
    if("audio".equals(req.inputMode()))message=voice.transcribe(req.audioBase64(),req.audioFormat(),Objects.toString(stories.role(user,((Number)stories.owned(user,req.conversationId()).get("role_id")).intValue()).get("name"),""));
    if(PromptBudget.tokens(message)>3000 || message.length()>6000 || (message.isBlank() && (req.images()==null || req.images().isEmpty())))throw new IllegalArgumentException("请输入内容，单次不超过 6000 字符");
    String requestedIssue=AiDailyService.explicitIssueDate(message).orElse(req.dailyIssueDate());
    if(req.continuationHistoryId()!=null){
      var prior=ChatContinuation.load(stories,json,user,req.conversationId(),req.continuationHistoryId());
      if(requestedIssue!=null&&!requestedIssue.isBlank()&&!requestedIssue.equals(prior.metadata().path("aiDaily").path("date").asText()))throw new IllegalArgumentException("继续回复的原刊期与所选刊期不一致，请取消继续或所选刊期后重试。");
    }
    if(req.continuationHistoryId()==null&&requestedIssue!=null&&!requestedIssue.isBlank()){
      try{if(!requestedIssue.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();java.time.LocalDate.parse(requestedIssue);}
      catch(IllegalArgumentException|java.time.format.DateTimeParseException invalid){throw new IllegalArgumentException("所选日报期次无效，请使用有效的年-月-日。");}
      var selected=daily.current(user);
      if(!selected.path("enabled").asBoolean())throw new IllegalArgumentException("请先开启 AI 日报读取，或取消输入框上的所选刊期。");
    }
    var active=begin(user,req.conversationId());var emitter=new SseEmitter(180000L);
    emitter.onTimeout(()->active.control().stop(CancelCause.REQUEST_TIMEOUT));emitter.onError(e->active.control().stop(CancelCause.CLIENT_DISCONNECTED));emitter.onCompletion(()->{if(!active.completion().isDone())active.control().stop(CancelCause.CLIENT_DISCONNECTED);});
    var sink=new EventSink(){public void send(String event,Object value){try{emitter.send(SseEmitter.event().name(event).data(value));}catch(Exception e){active.control().stop(CancelCause.CLIENT_DISCONNECTED);throw new IllegalStateException("Client disconnected");}}public void complete(){emitter.complete();}};
    String prompt=message;streams.execute(()->run(req,prompt,user,active,sink,false,"",new LinkedHashMap<>()));return emitter;
  }
  private Streams.Active begin(int user,String conversation){
    var owned=stories.owned(user,conversation);stories.role(user,((Number)owned.get("role_id")).intValue());
    var active=streams.start(user,conversation,stories.sessions().worldId(conversation));try{quota.reserve(user,connections.forUser(user).custom());}catch(Exception e){streams.finish(conversation,active);throw e;}return active;
  }
  public void proactive(int user,String conversation,String instruction,Map<String,Object> metadata,EventSink sink){
    int role=((Number)stories.owned(user,conversation).get("role_id")).intValue();
    var req=new Request(conversation,role,"",List.of(),"text",null,null,false,null);
    String request=metadata.containsKey("candidateId")?"现在由你主动向用户聊聊“"+metadata.getOrDefault("reason","准备的话题")+"”。请从提供的资料中选一条，明确提到该条的具体标题或事实，再用角色口吻说一两句感想或提问。不要只延续旧话题，不重复上一条回复。":"请根据最近对话和当前场景主动问候或闲聊，不重复上一条回复。不要自行引入新闻、日报、天气预报或来源；只有本轮明确提供外部资料时才能谈论这些事实。";
    var preparedMetadata=new LinkedHashMap<>(metadata);
    if(metadata.containsKey("candidateId")){
      var candidates=stories.db().queryForList("SELECT topic,fingerprint,summary FROM proactive_candidate WHERE id=? AND user_id=? AND conversation_id=?",metadata.get("candidateId"),user,conversation);
      if(!candidates.isEmpty()){
        var scheduled=ScheduledSkillPrompt.fromCandidate(candidates.get(0));
        if(scheduled.isPresent()){
          if(!ScheduledSkillPrompt.enabled(scheduled.get().name(),skills.list(user))){cancelScheduled(user,conversation,metadata,sink);sink.complete();return;}
          request=scheduled.get().request();preparedMetadata.put("scheduledSkill",scheduled.get().name());
        }
      }
    }
    String currentTask=request;var active=begin(user,conversation);
    streams.execute(()->run(req,currentTask,user,active,sink,true,instruction,preparedMetadata));
  }
  private void run(Request req,String message,int user,Streams.Active active,EventSink out,boolean proactive,String extra,Map<String,Object> metadata){
    String conversation=req.conversationId();var lock=streams.lock(active.world());lock.lock();var parser=new RpOutputParser();var emotion=new EmotionStream(value->{metadata.put("emotion",value);out.send("emotion",Map.of("emotion",value));});var visible=new StringBuilder();Long assistantId=null;List<String> refs=List.of();String status="failed",failureMessage=null;boolean persisted=false,inputAccepted=proactive;
    var connection=connections.forUser(user);
    boolean scheduledSkill=proactive&&metadata.containsKey("scheduledSkill");
    try {
      if(active.control().stopped.get())return;
      if(scheduledSkill&&!ScheduledSkillPrompt.enabled(metadata.get("scheduledSkill").toString(),skills.list(user))){cancelScheduled(user,conversation,metadata,out);return;}
      var continuation=req.continuationHistoryId()==null?null:ChatContinuation.load(stories,json,user,conversation,req.continuationHistoryId());
      if(continuation!=null){metadata.put("continuationHistoryId",continuation.id());metadata.put("continuationQuestion",continuation.question());for(String key:List.of("sources","aiDaily","aiDailyItem"))if(continuation.metadata().has(key))metadata.put(key,continuation.metadata().get(key));}
      var role=stories.role(user,((Number)stories.owned(user,conversation).get("role_id")).intValue());
      var previous=stories.db().queryForList("SELECT h.id,h.sender,h.content,c.context_json FROM history h LEFT JOIN message_context c ON c.history_id=h.id WHERE h.conversation_id=? ORDER BY h.id DESC LIMIT 16",conversation);Collections.reverse(previous);
      var scheduledNotices=ScheduledNoticeContext.ids(stories.db(),active.world());previous=ScheduledNoticeContext.history(previous,scheduledNotices);
      if(scheduledSkill)previous=List.of();
      if(proactive){metadata.put("isProactive",true);out.send("proactive",metadata);}else{
        refs=attachments.store(user,conversation,req.images());long inputId=stories.message(conversation,"user",message);inputAccepted=true;
        if(!refs.isEmpty())stories.db().update("INSERT INTO rp_attachment VALUES(?,?,?)",inputId,conversation,json.writeValueAsString(refs));
      }
      assistantId=stories.message(conversation,"assistant","");
      var prepared=prompt.prepare(req,message,user,active,out,proactive,scheduledSkill,extra,metadata,
          continuation,role,previous,scheduledNotices,assistantId,refs,connection);
      var messages=prepared.messages();
      var directAnswer=prepared.directAnswer();
      var outputContract=prepared.outputContract();
      // Only explicitly enabled MCP servers may be exposed to the model.
      var tools=directAnswer.isPresent()?List.<Map<String,Object>>of():skills.filterTools(user,message,mcp.modelTools(user,message),proactive,scheduledSkill?metadata.get("scheduledSkill").toString():null);
      var requested=proactive||directAnswer.isPresent()?List.<Map<String,Object>>of():mcp.requestedTools(user,message);
      boolean unavailable=requested.stream().anyMatch(r->tools.stream().noneMatch(t->Objects.equals(r.get("serverId"),t.get("serverId"))&&(r.get("name").equals("")||Objects.equals(r.get("name"),t.get("name")))));
      if(unavailable){
        String notice="本轮未执行 MCP 工具：请求的工具当前未授权、未启用、暂不可用，或未满足技能关键词条件。请在“工具与技能”中检查 MCP 服务及“允许模型调用”，并核对相关技能的启用状态和关键词，再重新发送。没有实际调用结果，因此不能把推算当作工具结果。";
        directAnswer=Optional.of(notice);metadata.put("toolPlanning",Map.of("phase","not_called","available",tools.size(),"message",notice));
        out.send("tool_status",Map.of("kind","plan","phase","not_called","available",tools.size(),"message",notice));
      }else if(!tools.isEmpty()){
        messages=toolRound.run(user,connection,messages,tools,out,assistantId,conversation,metadata);
        if(!requested.isEmpty()&&!metadata.containsKey("toolCalls"))directAnswer=Optional.of("本轮未执行 MCP 工具：模型没有发起实际调用，因此没有可报告的工具结果。可以简化为一个明确的工具调用后再试；也可在“工具与技能”中手动执行并查看结果。");
      }
      if(!tools.isEmpty()||!requested.isEmpty())messages=ToolExecutionTrace.constrain(messages,metadata);
      if(outputContract.isPresent()&&directAnswer.isEmpty()){
        messages=new ArrayList<>(messages);messages.add(Map.of("role","system","content",outputContract.get().instruction(prepared.hasImages())));
      }
      if("audio".equals(req.inputMode()))out.send("asr",Map.of("text",message));out.send("meta",Map.of("streamId",UUID.randomUUID().toString(),"conversationId",conversation));
      OpenAiTransport.Usage usage;boolean usageRecorded=false;
      if(directAnswer.isPresent()){
        // Verified extracts and capability notices use the regular persistence/event lifecycle.
        if(!active.control().stopped.get()){visible.append(directAnswer.get());out.send("text",Map.of("text",directAnswer.get()));}
        usage=new OpenAiTransport.Usage(0,0,false);
      }else if(outputContract.isPresent()){
        var raw=new StringBuilder();try{usage=models.stream(connection,messages,active.control(),raw::append,outputContract.get().responseFormat());}
        catch(Exception failure){if(active.control().observedUsage!=null)quota.record(user,connection.custom(),active.control().observedUsage);throw failure;}
        // The provider already spent these tokens even when local validation fails.
        quota.record(user,connection.custom(),usage);usageRecorded=true;
        if(!active.control().stopped.get()){
          if("length".equals(active.control().finishReason))throw SkillOutputContract.invalid();
          String answer;try{answer=outputContract.get().render(json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw.toString()));}catch(java.io.IOException invalidJson){throw SkillOutputContract.invalid();}
          visible.append(answer);out.send("text",Map.of("text",answer));
        }
      }else usage=models.stream(connection,messages,active.control(),token->{String text=emotion.append(parser.append(token));if(!text.isEmpty()){visible.append(text);out.send("text",Map.of("text",text));}});
      String tail=emotion.append(parser.finish())+emotion.finish();if(!tail.isEmpty()){visible.append(tail);out.send("text",Map.of("text",tail));}
      if(visible.toString().isBlank()&&!active.control().stopped.get()){
        String reason=Objects.toString(active.control().finishReason,"unknown");if(!Set.of("length","stop","content_filter","tool_calls","function_call").contains(reason))reason="unknown";
        metadata.put("modelResponse",Map.of("phase","empty_visible","finishReason",reason,"visibleCharacters",0,"inputTokens",usage.input(),"outputTokens",usage.output(),"estimated",usage.estimated()));
        if(!usageRecorded){quota.record(user,connection.custom(),usage);usageRecorded=true;}
        throw new java.io.IOException("模型没有返回可见内容");
      }
      boolean lengthLimited=!active.control().stopped.get()&&"length".equals(active.control().finishReason);
      String lengthNotice="回复达到长度上限，内容未完成。";
      if(lengthLimited){metadata.put("completion",Map.of("reason","length","incomplete",true));out.send("warning",Map.of("message",lengthNotice,"reason","length","incomplete",true));}
      status=active.control().stopped.get()||lengthLimited?"interrupted":"complete";stories.finish(conversation,assistantId,proactive?"":message,visible.toString(),status,refs,status.equals("complete")&&!scheduledSkill?parser.stateJson():"");persisted=true;if(!usageRecorded)quota.record(user,connection.custom(),usage);
      saveContext(assistantId,conversation,metadata);
      out.send("done",lengthLimited?Map.of("status",status,"historyId",assistantId,"reason","length","incomplete",true,"message",lengthNotice):Map.of("status",status,"historyId",assistantId));
      if(!proactive&&status.equals("complete"))inference.afterTurn(user,conversation);
    }catch(Exception e){
      status=active.control().stopped.get()?"interrupted":"failed";
      if(mode.cloud())try{stories.db().update("INSERT INTO fault(user_id,category,created_at) VALUES(?,?,?)",user,ServiceFailureCategory.classify(e,active.control()),Instant.now().toString());}catch(Exception ignored){}
      failureMessage=status.equals("interrupted")?"回复已停止":Objects.toString(e.getMessage(),"模型或存储暂不可用");
    }finally{
      if(assistantId!=null&&!persisted)try{visible.append(emotion.append(parser.finish())).append(emotion.finish());stories.finish(conversation,assistantId,proactive?"":message,visible.toString(),status,refs,"");}catch(Exception ignored){}
      if(assistantId!=null)try{saveContext(assistantId,conversation,metadata);}catch(Exception ignored){}
      if(failureMessage!=null)try{out.send("error",Map.of("message",failureMessage,"accepted",inputAccepted));}catch(Exception ignored){}
      lock.unlock();streams.finish(conversation,active);out.complete();
    }
  }
  private void cancelScheduled(int user,String conversation,Map<String,Object> metadata,EventSink sink){
    stories.db().update("UPDATE proactive_candidate SET status='cancelled' WHERE id=? AND user_id=? AND conversation_id=? AND status='pending'",metadata.get("candidateId"),user,conversation);
    sink.send("proactive_status",Map.of("phase","cancelled","message","定时技能已停用，本次任务已取消。"));
  }
  private void saveContext(Long history,String conversation,Map<String,Object> metadata)throws Exception {
    if(history!=null&&!metadata.isEmpty())stories.db().update("INSERT INTO message_context(history_id,conversation_id,context_json) VALUES(?,?,?) ON CONFLICT(history_id) DO UPDATE SET context_json=excluded.context_json",history,conversation,json.writeValueAsString(metadata));
  }
  @PostMapping("/api/chat/interrupt") public void interrupt(@RequestBody Map<String,String> request){String c=request.get("conversationId");stories.owned(SecurityConfiguration.current().id(),c);if(!streams.stopAndAwait(c,3000))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"停止请求已发送，回复记录仍在保存，请稍后重试");}
}
