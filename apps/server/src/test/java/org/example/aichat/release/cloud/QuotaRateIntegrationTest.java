package org.example.aichat.release.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.aichat.release.*;
import org.example.aichat.rp.RpState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Full HTTP/security/quota integration against an explicitly disposable PostgreSQL database.
 * Only the model, mail, object-store and indexing boundaries are fixtures. No paid model calls. */
@EnabledIfEnvironmentVariable(named="STORY_QUOTA_TEST_PG",matches=".+")
@ActiveProfiles("cloud")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "story.mode=cloud","story.daily-platform-limit=2","story.daily-custom-limit=3",
 "story.signing-key=quota-test-signing-key-never-used-in-production-1234567890",
 "story.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
 "story.admin-email=quota-admin@test.example","story.admin-password=quota-test-admin-password-1234567890",
 "story.admin-totp-secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
 "story.s3-endpoint=http://127.0.0.1:1","story.s3-access-key=test","story.s3-secret-key=test",
 "story.byok-proxy-host=127.0.0.1","spring.mail.host=127.0.0.1"
})
class QuotaRateIntegrationTest {
 private static final List<Map<String,Object>> evidence=new ArrayList<>();
 private static final Instant started=Instant.now();
 private static Path data;
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r)throws Exception{
  String url=System.getenv("STORY_QUOTA_TEST_PG");
  if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/story_quota_[A-Za-z0-9_]+"))
   throw new IllegalArgumentException("Quota integration requires a loopback disposable story_quota_ database");
  data=Files.createTempDirectory("story-quota-integration-");
  r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"storytest");
  r.add("spring.datasource.password",()->"release-test-only");r.add("story.data-dir",()->data.toString());
 }
 @Autowired TestRestTemplate http;@Autowired StoryStore stories;@Autowired Quota quota;
 @MockitoBean OpenAiTransport models;@MockitoBean MemoryIndex memory;
 @MockitoBean JavaMailSender mail;@MockitoBean ObjectStore objects;
 private record Actor(int id,String token,String conversation,int role){}

 private String session(int id){
  String token=Secrets.token();var now=Instant.now();
  stories.db().update("INSERT INTO auth_session VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,
    Secrets.hash(token),now.plusSeconds(600).toString(),Secrets.hash(Secrets.token()),now.plusSeconds(600).toString(),now.toString());
  return token;
 }
 private Actor actor(String purpose){
  int id=stories.db().queryForObject("INSERT INTO account(email,username,password,created_at) VALUES(?,'额度测试','unused',?) RETURNING id",
    Integer.class,"quota-probe-"+purpose+"-"+UUID.randomUUID()+"@test.example",Instant.now().toString());
  int role=stories.db().queryForObject("SELECT MIN(id) FROM role_card WHERE owner_id IS NULL",Integer.class);
  String conversation="quota_"+UUID.randomUUID();var state=new RpState();state.mode="story";
  stories.create(id,conversation,role,null,state);return new Actor(id,session(id),conversation,role);
 }
 private ResponseEntity<String> call(String path,HttpMethod method,Object payload,String token){
  var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(token);
  return http.exchange(path,method,new HttpEntity<>(payload,headers),String.class);
 }
 private ResponseEntity<String> chat(Actor actor){
  return call("/api/chat",HttpMethod.POST,Map.of("conversationId",actor.conversation(),"roleId",actor.role(),"message","这是合成额度测试，请回复一句问候。"),actor.token());
 }
 private void completed(ResponseEntity<String> response){
  assertEquals(200,response.getStatusCode().value(),response.getBody());
  assertNotNull(response.getBody());assertTrue(response.getBody().contains("event:done"),response.getBody());
  assertTrue(response.getBody().contains("\"status\":\"complete\""),response.getBody());
  assertFalse(response.getBody().contains("event:error"),response.getBody());
 }
 private Map<String,Object> usage(Actor actor,String provider){
  return stories.db().queryForMap("SELECT requests,input_tokens,output_tokens,estimated FROM daily_usage WHERE user_id=? AND day=? AND provider=?",
    actor.id(),LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(),provider);
 }
 private void usageIs(Actor actor,String provider,long requests,long input,long output){
  var row=usage(actor,provider);assertEquals(requests,((Number)row.get("requests")).longValue());
  assertEquals(input,((Number)row.get("input_tokens")).longValue());assertEquals(output,((Number)row.get("output_tokens")).longValue());
  assertEquals(false,row.get("estimated"));
 }
 private long history(Actor actor){return stories.db().queryForObject("SELECT COUNT(*) FROM history WHERE conversation_id=?",Long.class,actor.conversation());}
 private void rejectedWithoutMutation(Actor actor,List<Boolean> calls,int expectedCalls)throws Exception{
  long before=history(actor);var rows=stories.db().queryForList("SELECT provider,requests,input_tokens,output_tokens,estimated FROM daily_usage WHERE user_id=? ORDER BY provider",actor.id());
  long faults=stories.db().queryForObject("SELECT COUNT(*) FROM fault WHERE user_id=?",Long.class,actor.id());
  var response=chat(actor);assertEquals(429,response.getStatusCode().value(),response.getBody());
  assertNotNull(response.getBody());assertTrue(response.getBody().contains("今日试用额度已用完"),response.getBody());
  assertEquals(expectedCalls,calls.size(),"Rejected quota must not invoke the model");assertEquals(before,history(actor));
  assertEquals(rows,stories.db().queryForList("SELECT provider,requests,input_tokens,output_tokens,estimated FROM daily_usage WHERE user_id=? ORDER BY provider",actor.id()));
  assertEquals(faults,stories.db().queryForObject("SELECT COUNT(*) FROM fault WHERE user_id=?",Long.class,actor.id()));
 }
 @FunctionalInterface private interface Checked {Map<String,Object> run()throws Exception;}
 private void audit(String name,Checked body)throws Exception{
  var row=new LinkedHashMap<String,Object>();var begin=Instant.now();row.put("id",name);row.put("startedAt",begin.toString());
  try{row.putAll(body.run());row.put("status","pass");}
  catch(Exception|AssertionError error){row.put("status","fail");row.put("failure",error.getClass().getSimpleName()+": "+Objects.toString(error.getMessage(),""));throw error;}
  finally{row.put("durationMs",Duration.between(begin,Instant.now()).toMillis());evidence.add(row);writeReport();}
 }
 private static void writeReport()throws Exception{
  String target=System.getenv("STORY_QUOTA_REPORT");if(target==null||target.isBlank())return;
  var report=new LinkedHashMap<String,Object>();report.put("schemaVersion",1);report.put("inProgress",evidence.size()<3);
  report.put("startedAt",started.toString());report.put("observedAt",Instant.now().toString());
  report.put("kind","non-UI HTTP and PostgreSQL integration, not a user experience round");
  report.put("isolation","Disposable story_quota_ PostgreSQL database, fresh quota-probe identities and temporary data directory; shared backend/model services untouched");
  report.put("real",List.of("HTTP server and security filters","CloudIdentity sessions","CloudConnections selection and encrypted configuration","Quota transactions and PostgreSQL state","200-request rolling limit and real wall-clock expiry"));
  report.put("fixtures",List.of("OpenAiTransport emits synthetic text and reports exact 7 input / 3 output tokens; no real or paid LLM called","Mail, object store and semantic indexing boundaries mocked","Daily platform/custom limits reduced to 2/3 through isolated test context only","Fresh synthetic accounts/sessions inserted into disposable DB to avoid testing unrelated email signup"));
  report.put("limitations",List.of("Does not test midnight rollover; current quota day is Asia/Shanghai","Rate window recovery is observed after all prior accepted requests exceed 60 seconds, not at an exact nanosecond boundary","Concurrent preallocation exercises real Quota/PG transactions directly, not simultaneous HTTP streams","No promise about unrelated ownership, global stream concurrency, external provider availability or UI presentation","Restart/multi-instance request-rate coordination is not exercised; current ApiLimits map is local to one application process"));
  report.put("steps",evidence);report.put("allPassed",evidence.size()==3&&evidence.stream().allMatch(row->"pass".equals(row.get("status"))));
  Path path=Path.of(target).toAbsolutePath();Files.createDirectories(path.getParent());
  Files.writeString(path,new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n");
 }

 @Test @Order(1) void dailyQuotaRejectsBeforeModelOrAccountingAndProvidersAreIndependent()throws Exception{
  audit("daily-provider-quotas-http",()->{
   var calls=new CopyOnWriteArrayList<Boolean>();when(memory.recall(anyString(),anyString())).thenReturn("");
   when(models.stream(any(),anyList(),any(),any())).thenAnswer(invocation->{
    ModelConnections.Connection connection=invocation.getArgument(0);calls.add(connection.custom());
    Consumer<String> token=invocation.getArgument(3);token.accept("你好，这是合成额度测试回复。");return new OpenAiTransport.Usage(7,3,false);
   });
   var actor=actor("daily");completed(chat(actor));completed(chat(actor));usageIs(actor,"platform",2,14,6);
   rejectedWithoutMutation(actor,calls,2);assertEquals(List.of(false,false),calls);
   var save=call("/api/v1/me/model-connection",HttpMethod.PUT,Map.of("baseUrl","https://1.1.1.1/v1","model","quota-fixture","apiKey","quota-fixture-key-never-sent"),actor.token());
   assertEquals(200,save.getStatusCode().value(),save.getBody());
   for(int i=0;i<3;i++)completed(chat(actor));usageIs(actor,"custom",3,21,9);usageIs(actor,"platform",2,14,6);
   rejectedWithoutMutation(actor,calls,5);assertEquals(List.of(false,false,true,true,true),calls);
   var remove=call("/api/v1/me/model-connection",HttpMethod.DELETE,null,actor.token());assertTrue(remove.getStatusCode().is2xxSuccessful(),remove.getBody());
   rejectedWithoutMutation(actor,calls,5); // Returning to an exhausted provider does not reset its quota.
   var other=actor("independent");completed(chat(other));usageIs(other,"platform",1,7,3);
   assertEquals(10,history(actor));assertEquals(2,history(other));usageIs(actor,"platform",2,14,6);usageIs(actor,"custom",3,21,9);
   verify(models,times(6)).stream(any(),anyList(),any(),any());verify(models,never()).complete(any(),anyList(),anyInt());verify(models,never()).tools(any(),anyList(),anyList(),any(),any());
   return Map.of("httpStatusSequences",Map.of("platform",List.of(200,200,429),"customAfterPlatformExhausted",List.of(200,200,200,429),"returnToPlatform",429,"freshOtherUser",200),
    "modelCalls",6,"rejectedCallsReachedModel",0,"rejectedCallsAddedHistory",0,"rejectedCallsChangedUsage",false,
    "firstUserUsage",Map.of("platform",usage(actor,"platform"),"custom",usage(actor,"custom")),"otherUserUsage",usage(other,"platform"),
    "scope","Real provider selection and HTTP quota gate; upstream generation is a counted fixture. No real upstream token billing claimed.");
  });
 }
 @Test @Order(2) void concurrentReservationsCannotOvershootPostgresDailyLimit()throws Exception{
  audit("concurrent-postgres-reservation",()->{
   var actor=actor("race");int attempts=20;var ready=new CountDownLatch(attempts);var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(attempts);
   int accepted=0,rejected=0;
   try{
    var futures=new ArrayList<Future<Integer>>();
    for(int i=0;i<attempts;i++)futures.add(executor.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("Reservation barrier timed out");
     try{quota.reserve(actor.id(),false);return 200;}catch(ResponseStatusException error){return error.getStatusCode().value();}}));
    assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
    for(var future:futures){int status=future.get(20,TimeUnit.SECONDS);if(status==200)accepted++;else if(status==429)rejected++;else fail("Unexpected reserve result: "+status);}
   }finally{start.countDown();executor.shutdownNow();}
   assertEquals(2,accepted);assertEquals(18,rejected);usageIs(actor,"platform",2,0,0);assertEquals(0,history(actor));verifyNoInteractions(models);
   return Map.of("concurrentAttempts",attempts,"acceptedReservations",accepted,"quotaRejections",rejected,"usage",usage(actor,"platform"),
    "scope","Spring transactional Quota bean and real PostgreSQL; synthetic account. This is not an HTTP stream-concurrency test.");
  });
 }
 @Test @Order(3) @Timeout(95) void authenticatedRateLimitSharesIdentityAndRecoversAfterRealWindow()throws Exception{
  audit("http-rate-limit-window",()->{
   var actor=actor("rate");String secondToken=session(actor.id());var other=actor("rate-other");
   Instant firstAccepted=null,lastAccepted=null;
   for(int i=0;i<200;i++){
    var response=call("/api/roles",HttpMethod.GET,null,i<100?actor.token():secondToken);
    assertEquals(200,response.getStatusCode().value(),"Authenticated request "+(i+1)+": "+response.getBody());
    if(i==0)firstAccepted=Instant.now();lastAccepted=Instant.now();
   }
   var rejectedFirst=call("/api/roles",HttpMethod.GET,null,actor.token());var rejectedSecond=call("/api/roles",HttpMethod.GET,null,secondToken);
   assertEquals(429,rejectedFirst.getStatusCode().value());assertEquals(429,rejectedSecond.getStatusCode().value());
   var feedback=new ObjectMapper().readTree(rejectedFirst.getBody());
   assertEquals("request_rate_limited",feedback.path("code").asText());
   assertTrue(feedback.path("message").asText().contains("操作过于频繁"));assertTrue(feedback.path("message").asText().contains("秒后重试"));
   int retryAfter=Integer.parseInt(Objects.requireNonNull(rejectedFirst.getHeaders().getFirst("Retry-After")));
   assertTrue(retryAfter>=1&&retryAfter<=60);assertEquals(retryAfter,feedback.path("retryAfterSeconds").asInt());
   assertEquals(200,call("/api/roles",HttpMethod.GET,null,other.token()).getStatusCode().value());
   assertEquals(0,stories.db().queryForObject("SELECT COUNT(*) FROM daily_usage WHERE user_id IN (?,?)",Integer.class,actor.id(),other.id()));
   Instant resumeAfter=lastAccepted.plusSeconds(61);long waitMs=Math.max(0,Duration.between(Instant.now(),resumeAfter).toMillis());
   // Deliberately use the real clock: this verifies the deployed filter's complete sliding-window expiry.
   Thread.sleep(waitMs);
   var recovered=call("/api/roles",HttpMethod.GET,null,secondToken);Instant recoveredAt=Instant.now();assertEquals(200,recovered.getStatusCode().value(),recovered.getBody());
   assertEquals(0,stories.db().queryForObject("SELECT COUNT(*) FROM daily_usage WHERE user_id IN (?,?)",Integer.class,actor.id(),other.id()));verifyNoInteractions(models);
   return Map.ofEntries(Map.entry("acceptedRequestsBeforeRejection",200),Map.entry("tokensForSameIdentity",2),Map.entry("request201",429),
    Map.entry("request202OtherSessionSameIdentity",429),Map.entry("differentIdentityAtSaturation",200),Map.entry("firstAcceptedAt",firstAccepted.toString()),
    Map.entry("lastAcceptedAt",lastAccepted.toString()),Map.entry("windowWaitMs",waitMs),Map.entry("recoveredAt",recoveredAt.toString()),Map.entry("afterWindowStatus",200),
    Map.entry("rejectionBody",Objects.toString(rejectedFirst.getBody(),"")),Map.entry("retryAfterPresent",rejectedFirst.getHeaders().containsKey("Retry-After")),
    Map.entry("modelCalls",0),Map.entry("dailyQuotaRows",0),
    Map.entry("scope","200 authenticated GETs across two sessions of the same identity; all accepted timestamps expire before recovery. No exact boundary/multi-instance claim."));
  });
 }
}
