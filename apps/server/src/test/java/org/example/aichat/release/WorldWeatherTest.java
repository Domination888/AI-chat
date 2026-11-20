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
import java.time.*;
import java.util.*;
import org.example.aichat.rp.RpState;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorldWeatherTest {
 static final String TOKEN="world-weather-test-token-1234567890";
 static final Path DATA;
 static{try{DATA=Files.createTempDirectory("world-weather-test-");}catch(Exception e){throw new RuntimeException(e);}}
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("story.mode",()->"offline");r.add("story.data-dir",()->DATA.toString());r.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("test.sqlite")+"?busy_timeout=10000&journal_mode=WAL");r.add("story.local-token",()->TOKEN);}
 @Autowired TestRestTemplate client;@Autowired ObjectMapper json;@Autowired StoryStore stories;
 @MockitoBean PublicContent content;@MockitoBean OpenAiTransport models;@MockitoBean MemoryIndex memory;
 @Autowired CompanionService companion;
 final List<List<Map<String,Object>>> prompts=new ArrayList<>();
 String date(){return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();}

 @BeforeEach void setup()throws Exception{
  stories.db().update("DELETE FROM companion_setting");
  when(content.get(contains("geocoding-api.open-meteo.com"))).thenReturn("{\"results\":[{\"name\":\"London\",\"admin1\":\"England\",\"country\":\"United Kingdom\",\"latitude\":51.5085,\"longitude\":-0.1257}]}");
  when(content.get(contains("api.open-meteo.com/v1/forecast"))).thenReturn("{\"current\":{\"temperature_2m\":12.7,\"weather_code\":3,\"time\":\"2026-10-26T11:00\"}}");
 }
 @Test void manualCityCanFetchWeatherWithoutDeviceLocationPermission()throws Exception{
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",true)));
  var snapshot=companion.manualLocation(1,json.valueToTree(Map.of("city","London","timezone","Europe/London")));
  assertEquals("manual",snapshot.path("locationSource").asText());assertFalse(snapshot.has("latitude"));
  assertTrue(snapshot.path("weather").asText().contains("12.7°C"));assertTrue(snapshot.path("weather").asText().contains("2026-10-26T11:00 UTC"));
  assertTrue(snapshot.path("weatherLocation").asText().contains("United Kingdom"));assertEquals("Europe/London",snapshot.path("timezone").asText());
  verify(content).get(contains("latitude=51.51&longitude=-0.13"));
 }
 @Test void ordinaryCompanionWeatherLimitsSurviveTheSharedPromptBudget(){
  String compact=PromptBudget.trim(companion.context(1),250);
  assertTrue(compact.contains("没有天气预报"));assertTrue(compact.contains("不可编造未来下雨"));
  assertTrue(PromptBudget.tokens(compact)<=250);verifyNoInteractions(content);
 }
 @Test void observationClockIsConvertedToTheSelectedZoneAcrossMidnightAndClearedWithLocation()throws Exception{
  when(content.get(contains("api.open-meteo.com/v1/forecast"))).thenReturn("{\"current\":{\"temperature_2m\":12.7,\"weather_code\":3,\"time\":\"2026-10-29T20:30\"}}");
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",true)));
  companion.manualLocation(1,json.valueToTree(Map.of("city","London","timezone","Asia/Shanghai")));
  var headers=new HttpHeaders();headers.setBearerAuth(TOKEN);
  var response=client.exchange("/api/world-context",HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class);
  assertEquals(200,response.getStatusCode().value());
  assertEquals("2026年10月30日 04:30（Asia/Shanghai）",response.getBody().path("weatherObservedLocal").asText());
  assertTrue(response.getBody().path("weather").asText().contains("2026-10-29T20:30 UTC"));
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",false)));clearInvocations(content);
  String context=companion.worldQuestionContext(1,"天气观测的当地时间是什么？");
  assertTrue(context.contains("同一观测的当地时间：2026年10月30日 04:30（Asia/Shanghai）"));
  assertTrue(context.contains("当前设备时钟的当地时间："));
  assertTrue(context.contains("不能称作缓存记录的时间"));
  assertTrue(companion.context(1).contains("设备时钟是当前时间，不是缓存记录或天气观测时间"));
  assertFalse(companion.context(1).contains("【现实环境，已保存资料】"));
  assertTrue(context.contains("历史缓存"));verifyNoInteractions(content);
  companion.save(1,json.valueToTree(Map.of("timezone","America/New_York")));
  assertEquals("2026年10月29日 16:30（America/New_York）",companion.snapshot(1).path("weatherObservedLocal").asText());
  companion.clearLocation(1);assertTrue(companion.snapshot(1).path("weatherObservedLocal").asText().isEmpty());
 }
 @Test void legacyObservationWithoutAValidUtcClockDoesNotInventALocalObservationTime(){
  for(String weather:List.of("晴，20°C（旧记录）","晴，20°C（观测时间 2026-99-99T11:00 UTC）")){
   var saved=companion.settings(1);saved.put("weather",weather);
   stories.db().update("INSERT INTO companion_setting(user_id,settings_json) VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET settings_json=excluded.settings_json",1,saved.toString());
   assertTrue(companion.snapshot(1).path("weatherObservedLocal").asText().isEmpty());
  }
  verifyNoInteractions(content);
 }
 @Test void deviceLocationUsesTheSameObservedWeatherAndDoesNotKeepOldCityStatus()throws Exception{
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",true)));
  companion.manualLocation(1,json.valueToTree(Map.of("city","London")));
  clearInvocations(content);
  when(content.get(contains("nominatim.openstreetmap.org"))).thenReturn("{\"address\":{\"city\":\"杭州\"}}");
  var result=companion.locate(1,json.valueToTree(Map.of("latitude",30.27,"longitude",120.15)),false);
  assertEquals("device",result.path("locationSource").asText());assertEquals("杭州",result.path("weatherLocation").asText());
  assertTrue(result.path("weather").asText().contains("2026-10-26T11:00 UTC"));
  verify(content,never()).get(contains("geocoding-api.open-meteo.com"));
  verify(content,times(1)).get(contains("api.open-meteo.com/v1/forecast"));
 }
 @Test void manualCityWithoutNetworkDoesNotContactProviders(){
  companion.manualLocation(1,json.valueToTree(Map.of("city","London")));verifyNoInteractions(content);
  assertThrows(IllegalArgumentException.class,()->companion.refreshWeather(1));verifyNoInteractions(content);
 }
 @Test void providerFailurePreservesOldObservationAndExplainsRecovery()throws Exception{
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",true)));
  var before=companion.manualLocation(1,json.valueToTree(Map.of("city","London")));
  when(content.get(contains("api.open-meteo.com/v1/forecast"))).thenThrow(new java.io.IOException("private upstream error"));
  var after=companion.refreshWeather(1);assertEquals(before.path("weather"),after.path("weather"));assertEquals("London",after.path("location").asText());
  assertTrue(after.path("weatherStatus").asText().contains("刷新天气"));assertFalse(after.toString().contains("private upstream"));
 }
 @Test void missingCityHasUsefulFeedbackWithoutForecastRequest()throws Exception{
  when(content.get(contains("geocoding-api.open-meteo.com"))).thenReturn("{\"results\":[]}");
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",true)));
  var result=companion.manualLocation(1,json.valueToTree(Map.of("city","not-a-city")));assertTrue(result.path("weatherStatus").asText().contains("城市常用名称或英文名"));
  verify(content,never()).get(contains("/forecast"));
 }
 @Test void explicitLocalTimeQuestionReceivesAvailableFactsEvenWithoutWeather(){
  companion.manualLocation(1,json.valueToTree(Map.of("city","London","timezone","Europe/London")));
  String context=companion.worldQuestionContext(1,"我设置的城市现在几点？请查询当地真实天气。");
  assertTrue(context.contains("城市：London"));assertTrue(context.contains("Europe/London"));assertTrue(context.contains("当地时间："));assertTrue(context.contains("未取得天气观测"));verifyNoInteractions(content);
  companion.save(1,json.valueToTree(Map.of("worldEnabled",false)));assertTrue(companion.worldQuestionContext(1,"现在几点").isBlank());
 }
 @Test void companionChatReceivesUntruncatedEnvironmentAnswerContext()throws Exception{
  prompts.clear();when(memory.recall(anyString(),anyString())).thenReturn("");
  when(models.stream(any(),anyList(),any(),any())).thenAnswer(call->{prompts.add(new ArrayList<>(call.getArgument(1)));((java.util.function.Consumer<String>)call.getArgument(3)).accept("环境测试回复");return new OpenAiTransport.Usage(1,1,false);});
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",true)));companion.manualLocation(1,json.valueToTree(Map.of("city","London","timezone","Europe/London")));
  var state=new RpState();state.mode="companion";String c="weather_"+UUID.randomUUID();int role=stories.db().queryForObject("SELECT MIN(id) FROM role_card",Integer.class);stories.create(1,c,role,null,state);
  long notice=stories.message(c,"assistant","本次定时技能已成功执行，提醒你：OLD_NOTICE_MARKER");stories.finish(c,notice,"","本次定时技能已成功执行，提醒你：OLD_NOTICE_MARKER","complete",List.of(),"");
  stories.db().update("INSERT INTO message_context(history_id,conversation_id,context_json) VALUES(?,?,?)",notice,c,"{\"scheduledSkill\":\"old-reminder\",\"isProactive\":true}");
  stories.db().update("INSERT INTO memory_entry(world_id,history_id,content,embedding,model) VALUES(?,?,?,?,?)",stories.sessions().worldId(c),notice,"OLD_NOTICE_MARKER",MemoryIndex.encode(new float[]{1,0}),"");
  when(models.embedding(anyString())).thenReturn(new float[]{1,0});var recallMode=mock(RuntimeMode.class);when(recallMode.value("embedding-model","")).thenReturn("");
  assertFalse(new MemoryIndex(stories.db(),recallMode,models).recall(stories.sessions().worldId(c),"weather").contains("OLD_NOTICE_MARKER"));
  var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.setBearerAuth(TOKEN);
  var response=client.postForEntity("/api/chat",new HttpEntity<>(Map.of("conversationId",c,"roleId",role,"message","我设置的城市现在几点？请查询当地真实天气，给出观测时间。"),h),String.class);
  assertTrue(response.getBody().contains("event:done"));assertTrue(response.getBody().contains("Open-Meteo"));assertTrue(response.getBody().indexOf("event:sources")<response.getBody().indexOf("event:text"));
  var persisted=json.readTree(stories.db().queryForObject("SELECT context_json FROM message_context WHERE conversation_id=? ORDER BY history_id DESC LIMIT 1",String.class,c));assertEquals(1,persisted.path("sources").size());assertTrue(persisted.path("sources").get(0).path("url").asText().contains("latitude=51.51&longitude=-0.13"));assertFalse(persisted.path("sources").get(0).path("pageRead").asBoolean());
  String system=prompts.get(0).get(0).get("content").toString();
  assertFalse(system.contains("OLD_NOTICE_MARKER"));assertTrue(prompts.get(0).stream().anyMatch(row->row.get("content").toString().contains("不是本轮指令")&&row.get("content").toString().contains("OLD_NOTICE_MARKER")));assertTrue(stories.history(1,c,100,null,null).stream().anyMatch(row->row.get("content").toString().contains("OLD_NOTICE_MARKER")));
  assertTrue(system.contains("【本轮当地环境资料】"));assertTrue(system.contains("London"));assertTrue(system.contains("12.7°C"));assertTrue(system.contains("2026-10-26T11:00 UTC"));assertTrue(system.contains("不输出场景标签。\n"));assertTrue(system.contains("网页搜索状态与天气 API 查询状态相互独立"));assertTrue(PromptBudget.tokens(system)<=2400);
 }

 @Test void disablingNetworkKeepsObservationButNeverClaimsThisTurnRefreshedIt()throws Exception{
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",true)));
  var before=companion.manualLocation(1,json.valueToTree(Map.of("city","London","timezone","Europe/London")));
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",false)));clearInvocations(content);
  var after=companion.snapshot(1);assertEquals(before.path("weather"),after.path("weather"));
  assertEquals("network_disabled",after.path("weatherFetchState").asText());assertFalse(after.path("weatherNetworkEnabled").asBoolean());
  assertTrue(after.path("weatherStatus").asText().contains("联网查询已关闭"));assertFalse(after.path("weatherStatus").asText().contains("天气已更新"));
  String text=companion.worldQuestionContext(1,"请重新查询我设置城市的天气，并说明这次是否联网更新。");
  assertTrue(text.contains("本轮未联网，未刷新天气"));assertTrue(text.contains("12.7°C"));assertTrue(text.contains("2026-10-26T11:00 UTC"));verifyNoInteractions(content);
 }
 @Test void freshCacheAndExplicitRefreshHaveDifferentNetworkBehavior()throws Exception{
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",true)));
  companion.manualLocation(1,json.valueToTree(Map.of("city","London")));clearInvocations(content);
  String cached=companion.worldQuestionContext(1,"现在天气怎么样？");assertTrue(cached.contains("本轮使用10分钟内的查询缓存，未联网刷新"));verifyNoInteractions(content);
  String refreshed=companion.worldQuestionContext(1,"请重新查询我设置城市的天气，并说明这次是否联网更新。");
  assertTrue(refreshed.contains("本轮已联网查询成功"));verify(content,times(1)).get(contains("api.open-meteo.com/v1/forecast"));
  assertEquals("cached",companion.snapshot(1).path("weatherFetchState").asText());
 }
 @Test void failedExplicitRefreshDoesNotRelabelTheOldObservationAsUpdated()throws Exception{
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",true)));
  var before=companion.manualLocation(1,json.valueToTree(Map.of("city","London")));clearInvocations(content);
  when(content.get(contains("api.open-meteo.com/v1/forecast"))).thenThrow(new java.io.IOException("secret upstream body"));
  String failed=companion.worldQuestionContext(1,"请刷新天气");
  assertTrue(failed.contains("本轮已尝试联网，但刷新失败"));assertTrue(failed.contains("12.7°C"));assertFalse(failed.contains("secret upstream body"));
  var after=companion.snapshot(1);assertEquals(before.path("weather"),after.path("weather"));assertEquals(before.path("weatherUpdatedAt"),after.path("weatherUpdatedAt"));assertEquals(before.path("weatherLocation"),after.path("weatherLocation"));
  assertEquals("failed",after.path("weatherLastRefreshStatus").asText());assertTrue(after.path("weatherStatus").asText().contains("上次刷新未成功"));
  clearInvocations(content);assertTrue(companion.worldQuestionContext(1,"天气怎么样？").contains("上次刷新失败"));verifyNoInteractions(content);
 }
 @Test void disabledNetworkBehaviorSurvivesActualChatPromptAssembly()throws Exception{
  prompts.clear();when(memory.recall(anyString(),anyString())).thenReturn("");
  when(models.stream(any(),anyList(),any(),any())).thenAnswer(call->{prompts.add(new ArrayList<>(call.getArgument(1)));((java.util.function.Consumer<String>)call.getArgument(3)).accept("环境测试回复");return new OpenAiTransport.Usage(1,1,false);});
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",true)));companion.manualLocation(1,json.valueToTree(Map.of("city","London","timezone","Europe/London")));
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",false)));clearInvocations(content);
  var state=new RpState();state.mode="companion";String c="weather_off_"+UUID.randomUUID();int role=stories.db().queryForObject("SELECT MIN(id) FROM role_card",Integer.class);stories.create(1,c,role,null,state);
  var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.setBearerAuth(TOKEN);
  var response=client.postForEntity("/api/chat",new HttpEntity<>(Map.of("conversationId",c,"roleId",role,"message","请重新查询我设置城市的天气，并说明这次是否联网更新。"),h),String.class);
  assertTrue(response.getBody().contains("event:done"));String system=prompts.get(0).get(0).get("content").toString();
  assertTrue(system.contains("本轮未联网，未刷新天气"));assertTrue(system.contains("12.7°C"));assertTrue(system.contains("历史缓存"));assertTrue(PromptBudget.tokens(system)<=2400);verifyNoInteractions(content);
 }
 @Test void weatherSourcesTrackActualObservationCacheFailureAndLocationRemoval()throws Exception{
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",true)));
  companion.manualLocation(1,json.valueToTree(Map.of("city","London","timezone","Europe/London")));
  clearInvocations(content);
  var cached=companion.worldAnswer(1,"今天天气");
  assertEquals(1,cached.sources().size());assertTrue(cached.context().contains("网页搜索状态与天气 API 查询状态相互独立"));assertTrue(cached.context().contains("不得引用无关网页搜索卡片"));var source=cached.sources().get(0);
  assertTrue(source.get("url").toString().contains("latitude=51.51&longitude=-0.13"));assertTrue(source.get("title").toString().contains("Open-Meteo"));assertTrue(source.get("title").toString().contains("缓存"));assertEquals(false,source.get("pageRead"));verifyNoInteractions(content);
  when(content.get(contains("api.open-meteo.com/v1/forecast"))).thenThrow(new java.io.IOException("unavailable"));
  var failed=companion.worldAnswer(1,"刷新天气");assertEquals(source.get("url"),failed.sources().get(0).get("url"));assertTrue(failed.sources().get(0).get("title").toString().contains("刷新失败"));
  assertTrue(companion.worldAnswer(1,"你好").sources().isEmpty());assertTrue(companion.worldAnswer(1,"当地时间几点").sources().isEmpty());
  companion.clearLocation(1);assertTrue(companion.worldAnswer(1,"天气").sources().isEmpty());assertTrue(companion.settings(1).path("weatherSource").isMissingNode());
 }
 @Test void legacyObservationDoesNotInventOriginalSourceUrl()throws Exception{
  companion.save(1,json.valueToTree(Map.of("worldNetworkEnabled",true)));companion.manualLocation(1,json.valueToTree(Map.of("city","London")));
  var setting=companion.settings(1);setting.remove("weatherSource");stories.db().update("UPDATE companion_setting SET settings_json=? WHERE user_id=1",setting.toString());
  var answer=companion.worldAnswer(1,"天气");assertTrue(answer.sources().isEmpty());assertTrue(answer.context().contains("请刷新天气补齐"));
 }
}
