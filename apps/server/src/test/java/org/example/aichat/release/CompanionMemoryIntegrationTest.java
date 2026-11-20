package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.time.*;
import org.example.aichat.rp.RpState;

class CompanionMemoryIntegrationTest extends CompanionIntegrationFixture {

  @Test void memoryFeedbackIsWorldScopedAndDoesNotRewriteConversation()throws Exception{
    String conv=chapter("companion");long id=stories.message(conv,"assistant","我们喜欢蓝色。");stories.finish(conv,id,"我喜欢蓝色","我们喜欢蓝色。","complete",List.of(),"");
    result(call("/api/memory/"+id,HttpMethod.PUT,Map.of("conversationId",conv,"correction","用户现在喜欢绿色","hidden",false)));
    assertEquals("我们喜欢蓝色。",stories.db().queryForObject("SELECT content FROM history WHERE id=?",String.class,id));
    String other=chapter("story");assertEquals(404,call("/api/memory/"+id,HttpMethod.PUT,Map.of("conversationId",other,"correction","越界修改")).getStatusCode().value());
  }

  @Test void pendingAndFailedIndexesStillAllowReviewAndCorrectionOfCompleteTurns()throws Exception{
    String conv=chapter("story");long id=stories.message(conv,"assistant","我们在书店喝桂花茶。");
    stories.finish(conv,id,"我喜欢桂花茶","我们在书店喝桂花茶。","complete",List.of(),"");
    assertEquals(0,stories.db().queryForObject("SELECT COUNT(*) FROM memory_entry WHERE history_id=?",Integer.class,id));
    var pending=result(call("/api/memory?conversationId="+conv,HttpMethod.GET,null));
    assertEquals(1,pending.size());assertEquals(id,pending.get(0).path("history_id").asLong());
    assertTrue(pending.get(0).path("content").asText().contains("我喜欢桂花茶"));assertEquals("pending",pending.get(0).path("index_status").asText());
    stories.db().update("UPDATE index_queue SET status='failed' WHERE history_id=?",id);
    assertEquals("failed",result(call("/api/memory?conversationId="+conv,HttpMethod.GET,null)).get(0).path("index_status").asText());
    result(call("/api/memory/"+id,HttpMethod.PUT,Map.of("conversationId",conv,"correction","现在喜欢茉莉茶","hidden",true)));
    var corrected=result(call("/api/memory?conversationId="+conv,HttpMethod.GET,null)).get(0);
    assertEquals("现在喜欢茉莉茶",corrected.path("correction").asText());assertTrue(corrected.path("hidden").asBoolean());
    String other=chapter("story");assertTrue(result(call("/api/memory?conversationId="+other,HttpMethod.GET,null)).isEmpty());
    assertEquals("我们在书店喝桂花茶。",stories.db().queryForObject("SELECT content FROM history WHERE id=?",String.class,id));
  }

  @Test void textOnlyPhotosAreArchivedWithoutAskingTheModelToInventTheirContents()throws Exception{
    String conv=chapter("story");int role=((Number)stories.owned(1,conv).get("role_id")).intValue();
    String image="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2dS8AAAAASUVORK5CYII=";
    var response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",conv,"roleId",role,"message","这张照片有哪些颜色？","images",List.of(image)));
    assertTrue(response.getBody().contains("照片已保存到共同相册"));assertTrue(response.getBody().contains("当前模型不能识别图片内容"));
    assertTrue(response.getBody().contains("event:done"));assertFalse(response.getBody().contains("event:error"));
    verifyNoInteractions(models);
    var album=result(call("/api/rp/"+conv+"/album",HttpMethod.GET,null));assertEquals(1,album.size());assertFalse(album.get(0).path("images_json").asText().equals("[]"));
    var history=result(call("/api/conversation/"+conv+"/history",HttpMethod.GET,null));assertTrue(history.toString().contains("当前模型不能识别图片内容"));
  }

  @Test void firstExchangeBoundaryUsesThisWorldsRealUserTurnsNotGreetingsOrOtherWorlds()throws Exception{
    String first=chapter("companion");int role=((Number)stories.owned(1,first).get("role_id")).intValue();
    result(call("/api/rp/"+first+"/greeting",HttpMethod.POST,Map.of()));
    var capture=org.mockito.ArgumentCaptor.forClass(List.class);
    var response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",first,"roleId",role,"message","今天晚霞很好看。"));assertTrue(response.getBody().contains("event:done"));
    verify(models).stream(any(),capture.capture(),any(),any());assertTrue(capture.getValue().toString().contains("【本世界线的对话进度】"));
    clearInvocations(models);response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",first,"roleId",role,"message","继续聊聊。"));assertTrue(response.getBody().contains("event:done"));
    verify(models).stream(any(),capture.capture(),any(),any());assertFalse(capture.getValue().toString().contains("【本世界线的对话进度】"));
    clearInvocations(models);String separate=chapter("companion");response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",separate,"roleId",role,"message","第一次来这里。"));assertTrue(response.getBody().contains("event:done"));
    verify(models).stream(any(),capture.capture(),any(),any());assertTrue(capture.getValue().toString().contains("【本世界线的对话进度】"));
  }

  @Test void providedBackgroundIsPresentInTheActualModelPromptAfterAnEarlierCompletedTurn()throws Exception {
    String conv=chapter("story");int role=((Number)stories.owned(1,conv).get("role_id")).intValue();
    assertTrue(call("/api/chat",HttpMethod.POST,Map.of("conversationId",conv,"roleId",role,"message","你好。")).getBody().contains("event:done"));
    clearInvocations(models);
    String facts="上次我们在河口修好了木钟，今天我想给它换蓝色钟针，你觉得怎么样？";
    assertTrue(call("/api/chat",HttpMethod.POST,Map.of("conversationId",conv,"roleId",role,"message",facts)).getBody().contains("event:done"));
    var capture=org.mockito.ArgumentCaptor.forClass(List.class);
    verify(models).stream(any(),capture.capture(),any(),any());
    String prompt=capture.getValue().toString();
    assertTrue(prompt.contains("【本条用户给出的背景资料】"));
    assertTrue(prompt.contains(facts));
    assertFalse(prompt.contains("【本世界线的对话进度】"));
  }

  @Test void uiMemoryCorrectionReachesChatPromptBeforeReindexAndPreservesOriginalHistory()throws Exception {
    String conv=chapter("story"), oldUser="我喜欢桂花乌龙，也收藏《雾岸手记》。", oldReply="你喜欢桂花乌龙，收藏《雾岸手记》。";
    stories.message(conv,"user",oldUser);long id=stories.message(conv,"assistant",oldReply);
    stories.finish(conv,id,oldUser,oldReply,"complete",List.of(),"");
    String correction="收藏《雾岸手记》。现在喜欢茉莉白茶，不再喜欢桂花乌龙。";
    result(call("/api/memory/"+id,HttpMethod.PUT,Map.of("conversationId",conv,"correction",correction,"hidden",false)));
    int role=((Number)stories.owned(1,conv).get("role_id")).intValue();
    var response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",conv,"roleId",role,"message","我喜欢什么茶，收藏什么书？"));
    assertTrue(response.getBody().contains("event:done"),response.getBody());
    var capture=org.mockito.ArgumentCaptor.forClass(List.class);verify(models).stream(any(),capture.capture(),any(),any());
    String system=((Map<?,?>)capture.getValue().get(0)).get("content").toString();
    assertTrue(system.contains("【用户在记忆与纠错中保存的更正】"));assertTrue(system.contains(correction));
    for(Object value:capture.getValue()){var row=(Map<?,?>)value;assertNotEquals(oldUser,row.get("content"));assertNotEquals(oldReply,row.get("content"));}
    assertEquals(oldReply,stories.db().queryForObject("SELECT content FROM history WHERE id=?",String.class,id));
    // A different world's edits never leak into this new chapter.
    clearInvocations(models);response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",chapter("story"),"roleId",role,"message","我喜欢什么茶？"));
    assertTrue(response.getBody().contains("event:done"));verify(models).stream(any(),capture.capture(),any(),any());
    assertFalse(capture.getValue().toString().contains(correction));
    // A hidden correction is not a model fact.
    result(call("/api/memory/"+id,HttpMethod.PUT,Map.of("conversationId",conv,"correction",correction,"hidden",true)));
    clearInvocations(models);response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",conv,"roleId",role,"message","继续聊书。"));
    assertTrue(response.getBody().contains("event:done"));verify(models).stream(any(),capture.capture(),any(),any());
    assertFalse(((Map<?,?>)capture.getValue().get(0)).get("content").toString().contains("【用户在记忆与纠错中保存的更正】"));
  }

  @Test void knownUserEventIsQuotedAcrossChaptersWithoutCopyingOldAssistantProse()throws Exception{
    String old=chapter("companion");String fact="我把玻璃罗盘放在了河畔仓库的白色木柜里。";stories.message(old,"user",fact);long id=stories.message(old,"assistant","DISTRACTOR_MODEL_TEXT");stories.finish(old,id,fact,"DISTRACTOR_MODEL_TEXT","complete",List.of(),"");
    String next="fact_"+UUID.randomUUID();int role=((Number)stories.owned(1,old).get("role_id")).intValue();stories.create(1,next,role,stories.sessions().worldId(old),new RpState());
    when(models.complete(any(),anyList(),anyInt())).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"shared_past_question\",\"evidence\":[1]}",new OpenAiTransport.Usage(3,2,false)));clearInvocations(models);
    var response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",next,"roleId",role,"message","我之前把那个罗盘放在哪里了？"));assertTrue(response.getBody().contains("河畔仓库的白色木柜"));assertFalse(response.getBody().contains("DISTRACTOR_MODEL_TEXT"));verify(models,never()).stream(any(),anyList(),any(),any());
    var capture=org.mockito.ArgumentCaptor.forClass(List.class);verify(models).complete(any(),capture.capture(),anyInt());assertFalse(capture.getValue().toString().contains("DISTRACTOR_MODEL_TEXT"));
    var metadata=json.readTree(stories.db().queryForObject("SELECT context_json FROM message_context WHERE conversation_id=? ORDER BY history_id DESC LIMIT 1",String.class,next));assertEquals(id,metadata.path("memoryAnswer").path("historyIds").get(0).asLong());
  }

  @Test void unknownPastAfterAnOrdinaryGreetingUsesHonestFallback()throws Exception{
    String c=chapter("companion");long id=stories.message(c,"assistant","hello");stories.finish(c,id,"你好，今天过得怎么样？","hello","complete",List.of(),"");
    when(models.complete(any(),anyList(),anyInt())).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"shared_past_question\"}",new OpenAiTransport.Usage(3,2,false)));clearInvocations(models);
    int role=((Number)stories.owned(1,c).get("role_id")).intValue();var response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",c,"roleId",role,"message","你记得我们上次薄雾节去了哪里交换什么吗？"));assertTrue(response.getBody().contains(MissingPastAnswer.NOTICE));verify(models,never()).stream(any(),anyList(),any(),any());
  }

  @Test void emptyVisibleResponsePersistsSafeFinishAndUsageWithoutPretendingCompletion()throws Exception{
    String c=chapter("story");int role=((Number)stories.owned(1,c).get("role_id")).intValue();
    doAnswer(call->{((OpenAiTransport.Control)call.getArgument(2)).finishReason="length";return new OpenAiTransport.Usage(15,1200,false);}).when(models).stream(any(),anyList(),any(),any());
    var response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",c,"roleId",role,"message","描述一下今天的光线"));assertTrue(response.getBody().contains("模型没有返回可见内容"));assertFalse(response.getBody().contains("event:done"));
    var row=stories.db().queryForMap("SELECT t.status,mc.context_json FROM rp_turn t JOIN message_context mc ON mc.history_id=t.history_id WHERE t.conversation_id=?",c);assertEquals("failed",row.get("status"));var metadata=json.readTree(row.get("context_json").toString());assertEquals("length",metadata.path("modelResponse").path("finishReason").asText());assertEquals(1200,metadata.path("modelResponse").path("outputTokens").asInt());assertFalse(metadata.has("completion"));
  }

  @Test void unrelatedExistingFactsDoNotReopenUnknownSharedPastAndSavedSceneCanAnswer()throws Exception{
    String c=chapter("companion");long id=stories.message(c,"assistant","old answer");stories.finish(c,id,"我把罗盘放在了河畔白色木柜里。","old answer","complete",List.of(),"");
    when(models.complete(any(),anyList(),anyInt())).thenReturn(new OpenAiTransport.Completion("{\"kind\":\"shared_past_question\",\"evidence\":[1]}",new OpenAiTransport.Usage(3,2,false)));clearInvocations(models);
    int role=((Number)stories.owned(1,c).get("role_id")).intValue();var response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",c,"roleId",role,"message","你记得我们上次银灯节去了哪里交换什么吗？"));assertTrue(response.getBody().contains(PastFactAnswer.UNMATCHED));assertFalse(response.getBody().contains("old answer"));verify(models,never()).stream(any(),anyList(),any(),any());
    String sceneChapter=chapter("companion");var state=stories.sessions().state(sceneChapter);state.commitments=List.of("待履行：周日到海边观测灯塔");stories.sessions().editScene(sceneChapter,state);response=call("/api/chat",HttpMethod.POST,Map.of("conversationId",sceneChapter,"roleId",role,"message","我们上次约好什么时候去哪里做什么？"));assertTrue(response.getBody().contains("周日到海边观测灯塔"));
  }

  @Test void emotionTagsAndFloatPcmRemainWellFormed(){
    var tags=new ArrayList<String>();var emotion=new EmotionStream(tags::add);assertEquals("",emotion.append("<开"));assertEquals("你好",emotion.append("心>你好"));assertEquals(List.of("开心"),tags);
    var input=java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(.5f).putFloat(-2f).putFloat(Float.NaN).array();byte[] wav=VoiceGateway.floatWav(input);assertEquals("RIFF",new String(wav,0,4,java.nio.charset.StandardCharsets.US_ASCII));assertEquals(50,wav.length);
  }

  @Test void semanticInterestsUseUserEvidenceAndRespectDisabledTopics()throws Exception{
    companion.save(1,json.valueToTree(Map.of("semanticInterests",true)));
    String conv=chapter("companion"),quote="每到周末我都会背着相机去拍风景";long id=stories.message(conv,"assistant","听起来很惬意。");stories.finish(conv,id,quote,"听起来很惬意。","complete",List.of(),"");
    when(models.complete(any(),anyList(),anyInt())).thenReturn(new OpenAiTransport.Completion("{\"interests\":[{\"topic\":\"摄影\",\"sentiment\":\"like\",\"evidence\":\""+quote+"\"},{\"topic\":\"没有证据\",\"evidence\":\"伪造的原话\"}]}",new OpenAiTransport.Usage(10,10,false)));
    assertEquals(1,inference.refresh(1));assertEquals("摄影",companion.interests(1).get(0).get("topic"));
    companion.updateInterest(1,companion.interests(1).get(0).get("id").toString(),json.valueToTree(Map.of("enabled",false)));inference.refresh(1);assertFalse(companion.context(1).contains("喜欢：摄影"));
  }
}
