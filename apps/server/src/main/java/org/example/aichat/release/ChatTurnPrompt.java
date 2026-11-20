package org.example.aichat.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.example.aichat.release.ChatApi.EventSink;
import org.example.aichat.release.ChatApi.Request;
import org.example.aichat.rp.RpPromptBuilder;
import org.example.aichat.rp.RpState;

final class ChatTurnPrompt {
  @FunctionalInterface
  interface ContextWriter {
    void save(Long history, String conversation, Map<String,Object> metadata) throws Exception;
  }

  record Prepared(List<Map<String,Object>> messages, Optional<String> directAnswer,
                  Optional<SkillOutputContract> outputContract, boolean hasImages) {}

  private final StoryStore stories;
  private final OpenAiTransport models;
  private final MemoryIndex memory;
  private final Attachments attachments;
  private final Quota quota;
  private final ObjectMapper json;
  private final RuntimeMode mode;
  private final CompanionService companion;
  private final AiDailyService daily;
  private final SearchRag search;
  private final SkillRuntime skills;
  private final CharacterProfiles characters;
  private final ContextWriter saveContext;

  ChatTurnPrompt(StoryStore stories, OpenAiTransport models, MemoryIndex memory, Attachments attachments, Quota quota, ObjectMapper json, RuntimeMode mode, CompanionService companion, AiDailyService daily, SearchRag search, SkillRuntime skills, CharacterProfiles characters, ContextWriter saveContext) {
    this.stories = stories;
    this.models = models;
    this.memory = memory;
    this.attachments = attachments;
    this.quota = quota;
    this.json = json;
    this.mode = mode;
    this.companion = companion;
    this.daily = daily;
    this.search = search;
    this.skills = skills;
    this.characters = characters;
    this.saveContext = saveContext;
  }

  Prepared prepare(Request req, String message, int user, Streams.Active active, EventSink out,
                   boolean proactive, boolean scheduledSkill, String extra, Map<String,Object> metadata,
                   ChatContinuation.Context continuation, Map<String,Object> role,
                   List<Map<String,Object>> previous, Set<Long> scheduledNotices, Long assistantId,
                   List<String> refs, ModelConnections.Connection connection) throws Exception {
    String conversation = req.conversationId();
    RpState currentState=stories.sessions().state(conversation);
    RpState compact=stories.sessions().decode(stories.sessions().encode(currentState));
    for(var field:RpState.class.getFields())if(field.getType()==String.class){String value=(String)field.get(compact);field.set(compact,PromptBudget.trim(value,field.getName().equals("directorNote")?300:100));}
    compact.events=compact.events.stream().skip(Math.max(0,compact.events.size()-6)).map(e->PromptBudget.trim(e,60)).toList();compact.commitments=compact.commitments.stream().limit(6).map(e->PromptBudget.trim(e,60)).toList();
    String persona=characters.context(((Number)role.get("id")).intValue(),message);
    String core="你正在演绎有独立性格的角色，不替用户行动；不输出后台规划。自然回应，不反复自报姓名、复述人设或口头禅。角色："+role.get("name")+"\n"+(persona.isBlank()?"人物背景："+PromptBudget.trim(Objects.toString(role.get("background"),""),250)+"\n性格："+PromptBudget.trim(Objects.toString(role.get("personality"),""),180):PromptBudget.trim(persona,620))+"\n可在情绪自然变化时使用 <开心> <生气> <难过> <惊讶> <害羞> <俏皮> <平静> 标签，标签会转为表情，不要密集重复。\n";
    // Hidden memory also excludes the automatic cross-chapter recap. The
    // shared RP layer receives IDs, without depending on release memory tables.
    var hiddenHistory=req.search()?new HashSet<Long>():new HashSet<>(stories.db().queryForList("SELECT history_id FROM memory_feedback WHERE world_id=? AND hidden=TRUE",Long.class,active.world()));
    hiddenHistory.addAll(scheduledNotices);
    hiddenHistory.addAll(MissingPastAnswer.unavailableIds(stories.db(),active.world()));
    String scene=RpPromptBuilder.build(compact,stories.sessions().encode(compact),req.search()?"":PromptBudget.trim(stories.sessions().recentEvents(conversation,hiddenHistory),200));
    String companionContext="";if("companion".equals(compact.mode)){if(!proactive)companion.collect(user,conversation,message);companionContext=companion.context(user);}
    String sourceContext=continuation==null?"":"\n【此前保存的来源摘录】\n"+PromptBudget.trim(continuation.metadata().path("sources").toString(),1800);boolean dailyGrounded=continuation!=null&&continuation.metadata().has("aiDaily");SearchRag.Result searchResult=null;Optional<String> dailyIdentity=Optional.empty();
    WebFollowupContext.Resolved webFollowup=null;boolean webSourcesReused=false;
    // A selected daily already supplies its own original evidence. Generic
    // search snippets and read-failure notices must not displace that evidence.
    if(continuation==null&&(skills.enabled(user,"ai-daily-juya")||(req.dailyIssueDate()!=null&&!req.dailyIssueDate().isBlank()))){
      var context=daily.grounding(user,message,previous,650,req.dailyIssueDate());
      if(!context.text().isBlank()){
        sourceContext=context.text();dailyGrounded=true;
        dailyIdentity=context.userNotice().isBlank()?AiDailyService.issueIdentityAnswer(message,context):Optional.of(context.userNotice());
        if(!context.sources().isEmpty()){mergeSources(metadata,context.sources());metadata.put("aiDaily",context.digest());metadata.put("aiDailyItem",context.itemIndex());}
        previous=List.of();
      }
    }
    if(req.search()&&dailyGrounded&&continuation==null&&dailyIdentity.isEmpty()&&!SearchTerms.explicitUrls(message).isEmpty()){
      // Explicit extra pages are still read when requested. Their availability
      // is separate from the already available daily's original text.
      try{
        var extraPages=search.search(user,message,List.of(),p->out.send("search_status",p));
        mergeSources(metadata,extraPages.sources());
        sourceContext+="\n【用户另外指定的网页；以下读取状态不适用于上面的日报原文】\n"+PromptBudget.trim(extraPages.contextText(),500);
        if(!extraPages.reason().isBlank())out.send("warning",Map.of("message",extraPages.reason()));
      }catch(Exception unavailable){
        sourceContext+="\n用户另外指定的网页本轮读取未成功；上面的日报资料仍可引用，不能补写额外网页的内容。\n";
        out.send("warning",Map.of("message","额外指定的网页本轮未能读取，将保留日报原文与来源，不补写网页内容。"));
      }
    }
    if(req.search()&&!dailyGrounded){
      webFollowup=continuation==null?WebFollowupContext.resolve(message,proactive?List.of():previous,json,
          (current,original,sources)->WebFollowupContext.classifyReference(current,original,sources,models,connection,json,usage->quota.record(user,connection.custom(),usage))):null;
      String retrievalQuestion=continuation!=null?continuation.question():webFollowup.retrievalQuestion();
      try{
        var result=search.search(user,retrievalQuestion,previous,p->out.send("search_status",p));searchResult=result;
        if(continuation!=null&&!result.sources().isEmpty())metadata.remove("sources");
        mergeSources(metadata,result.sources());sourceContext=continuation!=null&&result.sources().isEmpty()?result.contextText()+"\n本轮未取得新的资料，以下为上次保存的来源摘录（不是本轮重新读取）：\n"+sourceContext:result.contextText();
        if(!result.sources().isEmpty())metadata.put("webQuestion",continuation!=null?continuation.question():webFollowup.inherited()?webFollowup.originalQuestion():message);
        else if(webFollowup!=null&&webFollowup.inherited()){
          sourceContext=result.contextText()+webFollowup.fallbackContext();mergeSources(metadata,webFollowup.priorSources());webSourcesReused=true;
        }
        if(!result.reason().isBlank())out.send("warning",Map.of("message",result.reason()));
      }catch(Exception refreshFailure){
        if(webFollowup!=null&&webFollowup.inherited()){
          sourceContext=webFollowup.fallbackContext();mergeSources(metadata,webFollowup.priorSources());webSourcesReused=true;
        }else{
          if(continuation==null||!continuation.metadata().path("sources").isArray()||continuation.metadata().path("sources").isEmpty())throw refreshFailure;
          sourceContext="本轮重读来源未成功；仅使用此前保存的来源摘录，不得声称刚刚联网核实。\n"+sourceContext;
        }
        out.send("warning",Map.of("message","本轮来源重读未成功，将依据前次保存的摘录继续，并保留未核实限制。"));
      }
      if(webSourcesReused){metadata.put("webQuestion",webFollowup.originalQuestion());metadata.put("webSourcesReused",true);}
    }
    if(req.search())previous=List.of(); // Query and daily references were resolved before isolating unverified prior answers.
    if(continuation!=null&&dailyGrounded)sourceContext=continuation.dailyEvidence();
    var directAnswer=dailyGrounded?dailyIdentity:SearchDirectoryAnswer.answer(message,searchResult);
    if(!scheduledSkill)sourceContext+=extra;
    boolean researched=proactive&&!scheduledSkill&&!dailyGrounded&&metadata.containsKey("candidateId")
        &&!sourceContext.isBlank()&&json.valueToTree(metadata.getOrDefault("sources",List.of())).size()>0;
    if(researched)previous=List.of();
    var worldAnswer="companion".equals(compact.mode)&&skills.enabled(user,"weather-lookup")?companion.worldAnswer(user,message):new CompanionService.WorldAnswer("",List.of());
    if(!worldAnswer.sources().isEmpty())mergeSources(metadata,worldAnswer.sources());
    if(metadata.containsKey("sources")){
      // Persist before emitting so an immediate history reload retains the same source cards.
      saveContext.save(assistantId,conversation,metadata);
      out.send("sources",Map.of("sources",metadata.get("sources")));
    }
    boolean continuingGrounded=continuation!=null&&continuation.metadata().path("sources").isArray()&&!continuation.metadata().path("sources").isEmpty();
    boolean ordinaryConversation=!dailyGrounded&&!req.search()&&!scheduledSkill&&!researched&&!continuingGrounded;
    var matchedTasks=ordinaryConversation?skills.matchingTasks(user,message,proactive):List.<com.fasterxml.jackson.databind.JsonNode>of();
    var outputContract=SkillOutputContract.from(matchedTasks,message);
    String memoryContext="\n【相关记忆】\n", savedCorrections="", firstExchange="";
    if(ordinaryConversation){
      if(continuation==null&&stories.db().queryForObject("SELECT COUNT(*) FROM rp_turn WHERE world_id=? AND status='complete' AND user_text<>''",Integer.class,active.world())==0)firstExchange=ConversationPrompt.firstExchange();
      var feedback=stories.db().queryForList("SELECT f.correction,t.user_text,t.assistant_text,t.history_id AS assistant_history_id,(SELECT MAX(h.id) FROM history h WHERE h.conversation_id=t.conversation_id AND h.sender='user' AND h.id<t.history_id AND h.content=t.user_text) AS user_history_id FROM memory_feedback f JOIN rp_turn t ON t.history_id=f.history_id AND t.world_id=f.world_id WHERE f.world_id=? AND f.hidden=FALSE AND f.correction<>'' AND t.id=(SELECT MAX(x.id) FROM rp_turn x WHERE x.history_id=f.history_id AND x.world_id=f.world_id) ORDER BY f.history_id DESC",active.world());
      var evidenceHistory=MissingPastAnswer.withoutReplacedUserFacts(previous,feedback);
      if(continuation==null&&!proactive&&refs.isEmpty()&&matchedTasks.isEmpty()&&worldAnswer.context().isBlank()){
        var grounded=PastFactAnswer.answer(message,PastFactAnswer.withScene(PastFactAnswer.withVisible(PastFactAnswer.facts(stories.db(),active.world()),evidenceHistory),stories.sessions().state(conversation)),models,connection,json,usage->quota.record(user,connection.custom(),usage));
        if(grounded.isPresent()){directAnswer=Optional.of(grounded.get().text());metadata.put("memoryAnswer",Map.of("kind","record_quote","historyIds",grounded.get().historyIds(),"sourceTypes",grounded.get().sourceTypes()));if(grounded.get().historyIds().isEmpty()&&grounded.get().sourceTypes().isEmpty())metadata.put("memoryUnavailable",true);saveContext.save(assistantId,conversation,metadata);}
      }
      if(directAnswer.isEmpty()&&!proactive&&refs.isEmpty()&&matchedTasks.isEmpty())directAnswer=MissingPastAnswer.profileAnswer(message,feedback,evidenceHistory);
      feedback=ConversationPrompt.boundedCorrections(feedback.stream().limit(4).toList());
      savedCorrections=ConversationPrompt.savedCorrections(feedback);
      // Project edits only into the model context; original dialogue and user-visible records stay intact.
      if(!savedCorrections.isEmpty()){
        previous=ConversationPrompt.correctedHistory(previous,feedback);
        scene=ConversationPrompt.correctedScene(scene,feedback);
      }
      core=ConversationPrompt.core(PromptBudget.trim(core,780),previous);
      scene=ConversationPrompt.scene(PromptBudget.trim(scene,400),previous);
      memoryContext="\n【历史记忆摘录，可能与当前资料冲突】\n"+ConversationPrompt.memory(PromptBudget.trim(memory.recall(active.world(),message),180));
    }
    // A scheduled task contains quoted instructions, not a new user utterance:
    // its own keyword examples must not activate unrelated answer formats.
    String skillContext=scheduledSkill?"":PromptBudget.trim(skills.context(user,message,proactive),600);
    String system=PromptBudget.trim(core,780)+PromptBudget.trim(sourceContext,650)+PromptBudget.trim(scene,400)+memoryContext+PromptBudget.trim(companionContext,250);
    if(dailyGrounded)system=PromptBudget.trim(sourceContext,1200)+PromptBudget.trim(core,200)+PromptBudget.trim(scene,200);
    boolean factualSearch=(req.search()||researched||continuingGrounded)&&!dailyGrounded;
    if(factualSearch)system=SearchFactPrompt.context(role.get("name"),sourceContext);
    if(scheduledSkill)system=ScheduledSkillPrompt.context(role.get("name"),sourceContext,skillContext);
    String taskInstruction=ordinaryConversation?ConversationPrompt.instruction()+firstExchange+ConversationPrompt.currentScene(currentState)+ConversationPrompt.currentBackground(message)+savedCorrections+ConversationPrompt.latestCorrection(previous,message):"";
    if(factualSearch)taskInstruction+=SearchFactPrompt.instruction(message);
    if(req.search()&&!dailyGrounded)taskInstruction+=SearchDirectoryAnswer.readingState(searchResult);
    if(webSourcesReused)taskInstruction+="\n本轮网页未重新读取成功，使用的是此前保存的摘录。回答应明确这个限制，不能把之前的读取状态说成本轮刚刚核实。\n";
    // Reserve room at the end so grounded answer requirements survive the prompt budget.
    if(dailyGrounded)taskInstruction+="\n【本轮日报任务】\n用用户提问的语言直接回答，不输出JSON或场景标签。只依据上面的日报资料回答用户刚才的问题，必须包含三个简短部分：①已知改动：从摘要或正文转述直接相关事实，保留动作的对象与范围，区分已发生的变化与未来预告；②资料局限：只指出资料确实缺失的信息，不能把已说明的对象、动作或范围说成未知。具体机制或实施细节没有给出，就说资料未提供，不推测目的、效果或技术实现；③来源：只列对应报道标题，准确链接已在本次回复的来源卡片中，不在正文重新拼写URL。角色语气最多一句，不转移到旧话题，不添加场景描写。\n";
    String worldQuestion=worldAnswer.context();taskInstruction+=worldQuestion;
    if(ordinaryConversation)taskInstruction+=ConversationPrompt.creativeTask(continuation==null?message:continuation.question());
    if(scheduledSkill)taskInstruction+=ScheduledSkillPrompt.instruction();
    if(continuation!=null)taskInstruction+=continuation.instruction();
    String priorityInstructions=(factualSearch?SearchFactPrompt.skills(skillContext):skillContext)+taskInstruction;
    int systemBudget=factualSearch?2800:2400;
    if(continuation!=null)systemBudget=Math.min(systemBudget,Math.max(800,5000-PromptBudget.tokens(continuation.question())-Math.min(1000,PromptBudget.tokens(continuation.partial()))-PromptBudget.tokens(message)));
    boolean vision=connection.custom() || Boolean.parseBoolean(mode.value("llm-vision","false"));var modelImages=vision?attachments.forModel(user,refs):List.<String>of();
    if(!matchedTasks.isEmpty()){
      system=SkillTaskPrompt.context(role.get("name"),SkillTaskPrompt.records(compact,memoryContext,previous),savedCorrections+ConversationPrompt.latestCorrection(previous,message)+worldQuestion,skillContext);
      if(modelImages.isEmpty())previous=SkillTaskPrompt.history(previous);
    }
    else if(!priorityInstructions.isEmpty())system=PromptBudget.trim(system,Math.max(0,systemBudget-PromptBudget.tokens(priorityInstructions)-80))+priorityInstructions;
    if(!modelImages.isEmpty()){
      system=VisualTaskPrompt.context(role.get("name"),core,SkillTaskPrompt.records(compact,memoryContext,previous),sourceContext,priorityInstructions);
      previous=SkillTaskPrompt.history(previous);
    }
    if(!vision&&!refs.isEmpty()){
      // A text-only model has no evidence about the pixels. Do not ask it to
      // invent a caption or a claim about whether storage succeeded.
      directAnswer=Optional.of("照片已保存到共同相册。当前模型不能识别图片内容；你可以描述画面，或切换到支持看图的模型后再问我。");
      out.send("warning",Map.of("message","照片已保存，当前模型不能识图"));
    }
    Object current=message;if(!modelImages.isEmpty()){var parts=new ArrayList<Object>();for(String image:modelImages)parts.add(Map.of("type","image_url","image_url",Map.of("url",image)));parts.add(Map.of("type","text","text",message));current=parts;}
    if(continuation!=null)previous=List.of(Map.of("sender","user","content",continuation.question()),Map.of("sender","assistant","content",continuation.partialExcerpt()));
    else if(webFollowup!=null&&webFollowup.inherited())previous=List.of(Map.of("sender","user","content",webFollowup.originalQuestion()));
    var messages=PromptBudget.assemble(system,previous,message,current,systemBudget);
    return new Prepared(messages, directAnswer, outputContract, !modelImages.isEmpty());
  }

  private void mergeSources(Map<String,Object> metadata,Object added){
    var merged=new LinkedHashMap<String,com.fasterxml.jackson.databind.JsonNode>();
    for(Object value:List.of(metadata.getOrDefault("sources",List.of()),added))for(var source:json.valueToTree(value)){
      String url=source.path("url").asText();if(!url.isBlank())merged.putIfAbsent(url,source);
    }
    metadata.put("sources",List.copyOf(merged.values()));
  }
}
