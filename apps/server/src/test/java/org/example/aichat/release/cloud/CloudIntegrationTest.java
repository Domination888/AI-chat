package org.example.aichat.release.cloud;

import org.example.aichat.release.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.Instant;

@EnabledIfEnvironmentVariable(named="STORY_TEST_PG",matches=".+")
@ActiveProfiles("cloud")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "story.mode=cloud","story.signing-key=test-signing-key-not-a-real-secret-1234567890","story.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
 "story.admin-email=admin@test.example","story.admin-password=test-only-admin-password-1234567890","story.admin-totp-secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
 "story.s3-endpoint=http://127.0.0.1:1","story.s3-access-key=test","story.s3-secret-key=test","story.byok-proxy-host=127.0.0.1","spring.mail.host=127.0.0.1"
})
class CloudIntegrationTest {
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("STORY_TEST_PG"));r.add("spring.datasource.username",()->"storytest");r.add("spring.datasource.password",()->"release-test-only");}
 @Autowired Streams cancellationStreams;
 @Autowired TestRestTemplate client;@Autowired ObjectMapper json;@Autowired StoryStore stories;@Autowired CloudIdentity identities;
 @MockitoBean JavaMailSender mail;@MockitoBean ObjectStore objects;@MockitoBean OpenAiTransport models;@MockitoBean MemoryIndex memory;
 ResponseEntity<String> call(String path,HttpMethod method,Object payload,String token){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(token!=null)h.setBearerAuth(token);return client.exchange(path,method,new HttpEntity<>(payload,h),String.class);}
 JsonNode result(ResponseEntity<String> response)throws Exception{assertTrue(response.getStatusCode().is2xxSuccessful(),response.getBody());return response.getBody()==null?null:json.readTree(response.getBody());}
 String admin()throws Exception{return result(call("/api/v1/auth/admin-login",HttpMethod.POST,Map.of("email","admin@test.example","password","test-only-admin-password-1234567890","otp",Totp.code("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",Instant.now().getEpochSecond()/30)),null)).path("accessToken").asText();}
 JsonNode register(String admin,String email)throws Exception{
  String invite=result(call("/api/v1/admin/invitations",HttpMethod.POST,Map.of(),admin)).path("code").asText();
  result(call("/api/v1/auth/email-code",HttpMethod.POST,Map.of("email",email,"invitation",invite),null));var capture=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(mail,atLeastOnce()).send(capture.capture());String text=capture.getValue().getText();String code=text.replaceAll(".*?(\\d{6}).*","$1");
  return result(call("/api/v1/auth/register",HttpMethod.POST,Map.of("email",email,"password","test-user-password","username","新创作者","invitation",invite,"code",code),null));
 }
 @Test void accountsOwnershipByokAndRotatingSessions()throws Exception{
  String admin=admin(),email="one-"+UUID.randomUUID()+"@test.example";JsonNode a=register(admin,email),b=register(admin,"two-"+UUID.randomUUID()+"@test.example");String first=a.path("accessToken").asText(),second=b.path("accessToken").asText();
  int role=result(call("/api/roles",HttpMethod.GET,null,first)).get(0).path("id").asInt();String conv="conv_"+UUID.randomUUID();result(call("/api/rp/sessions",HttpMethod.POST,Map.of("conversationId",conv,"roleId",role,"userId",b.path("user").path("id").asInt(),"state",Map.of("location","厨房")),first));
  assertEquals(a.path("user").path("id").asInt(),stories.db().queryForObject("SELECT user_id FROM conversation WHERE id=?",Integer.class,conv));
  for(String endpoint:List.of("/api/rp/"+conv,"/api/conversation/"+conv+"/history","/api/rp/"+conv+"/album"))assertEquals(404,call(endpoint,HttpMethod.GET,null,second).getStatusCode().value());
  assertEquals(403,call("/api/v1/admin/users",HttpMethod.GET,null,first).getStatusCode().value());
  result(call("/api/v1/me/model-connection",HttpMethod.PUT,Map.of("baseUrl","https://1.1.1.1/v1","model","custom","apiKey","tenant-secret-one"),first));assertFalse(call("/api/v1/me/model-connection",HttpMethod.GET,null,first).getBody().contains("tenant-secret-one"));assertFalse(result(call("/api/v1/me/model-connection",HttpMethod.GET,null,second)).path("custom").asBoolean());
  assertEquals(400,call("/api/v1/me/model-connection",HttpMethod.PUT,Map.of("baseUrl","https://127.0.0.1/v1","model","x","apiKey","x"),first).getStatusCode().value());
  String refresh=a.path("refreshToken").asText();JsonNode rotated=result(call("/api/v1/auth/refresh",HttpMethod.POST,Map.of("refreshToken",refresh),null));assertEquals(401,call("/api/v1/auth/refresh",HttpMethod.POST,Map.of("refreshToken",refresh),null).getStatusCode().value());assertEquals(401,call("/api/v1/me",HttpMethod.GET,null,first).getStatusCode().value());
  result(call("/api/v1/admin/users/"+a.path("user").path("id").asInt()+"/status",HttpMethod.PUT,Map.of("status","suspended"),admin));assertEquals(401,call("/api/v1/me",HttpMethod.GET,null,rotated.path("accessToken").asText()).getStatusCode().value());
 }
 @Test void twentyUsersCanStreamWithoutCrossingWorlds()throws Exception{
  when(memory.recall(anyString(),anyString())).thenReturn("");when(models.stream(any(),anyList(),any(),any())).thenAnswer(call->{@SuppressWarnings("unchecked")var consumer=(java.util.function.Consumer<String>)call.getArgument(3);Thread.sleep(150);consumer.accept("你好<scene>{\"location\":\"庭院\"}</scene>");return new OpenAiTransport.Usage(10,10,false);});
  int role=stories.db().queryForObject("SELECT MIN(id) FROM role_card",Integer.class);var executor=Executors.newFixedThreadPool(20);var futures=new ArrayList<Future<ResponseEntity<String>>>();
  try{for(int i=0;i<20;i++){
   String email="load-"+UUID.randomUUID()+"@test.example";int id=stories.db().queryForObject("INSERT INTO account(email,username,password,created_at) VALUES(?,'压测','unused',?) RETURNING id",Integer.class,email,Instant.now().toString());String token=Secrets.token();stories.db().update("INSERT INTO auth_session VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,Secrets.hash(token),Instant.now().plusSeconds(600).toString(),Secrets.hash(Secrets.token()),Instant.now().plusSeconds(600).toString(),Instant.now().toString());
   String conv="load_"+UUID.randomUUID();stories.create(id,conv,role,null,new org.example.aichat.rp.RpState());futures.add(executor.submit(()->call("/api/chat",HttpMethod.POST,Map.of("conversationId",conv,"roleId",role,"message","你好"),token)));
  }
  for(var f:futures){var r=f.get(20,TimeUnit.SECONDS);assertEquals(200,r.getStatusCode().value(),r.getBody());assertTrue(r.getBody().contains("event:done"),r.getBody());assertFalse(r.getBody().contains("<scene>"));}
  }finally{executor.shutdownNow();}
 }
 @Test void adminRequiresMfa(){assertEquals(401,call("/api/v1/auth/admin-login",HttpMethod.POST,Map.of("email","admin@test.example","password","test-only-admin-password-1234567890","otp","000000"),null).getStatusCode().value());}
 @Test void shuCompanionCapabilitiesUsePostgresAndRespectTenantBoundaries()throws Exception{
  String admin=admin();var a=register(admin,"companion-"+UUID.randomUUID()+"@test.example");var b=register(admin,"companion-other-"+UUID.randomUUID()+"@test.example");String first=a.path("accessToken").asText(),second=b.path("accessToken").asText();
  result(call("/api/v1/me/companion",HttpMethod.PUT,Map.of("timezone","Asia/Tokyo","proactiveEnabled",true,"proactiveIdleSeconds",10,"searchEnabled",true),first));
  assertEquals("Asia/Tokyo",result(call("/api/v1/me/companion",HttpMethod.GET,null,first)).path("timezone").asText());assertEquals("Asia/Shanghai",result(call("/api/v1/me/companion",HttpMethod.GET,null,second)).path("timezone").asText());
  var world=result(call("/api/world-context/location",HttpMethod.POST,Map.of("latitude",35.1,"longitude",139.1,"timezone","Asia/Tokyo"),first));assertFalse(world.path("lunarDate").asText().isBlank());assertEquals(400,call("/api/world-context/location/ip",HttpMethod.POST,Map.of(),first).getStatusCode().value());
  int role=result(call("/api/v1/characters/shu/import",HttpMethod.POST,Map.of("card",Map.of("name","黍","persona",Map.of("identity","岁兽之一","speech","温和从容")),"greeting","回来了？"),first)).path("roleId").asInt();
  assertFalse(result(call("/api/roles",HttpMethod.GET,null,second)).toString().contains("岁兽之一"));
  result(call("/api/skills",HttpMethod.POST,Map.of("name","private-rule","instructions","私有指令","enabled",true),first));assertFalse(result(call("/api/skills",HttpMethod.GET,null,second)).toString().contains("private-rule"));
  assertEquals(400,call("/api/mcp/servers",HttpMethod.POST,Map.of("id","no-shell","name","server-command","transport","stdio","enabled",true,"command",List.of("sh","-c","true")),first).getStatusCode().value());
  result(call("/api/mcp/servers",HttpMethod.POST,Map.of("id","prime","name","原质数工具","builtin",true,"transport","stdio","command",List.of("ignored"),"enabled",true),first));
  assertEquals("97 是质数",result(call("/api/mcp/servers/prime/tools/isPrime",HttpMethod.POST,Map.of("number",97),first)).path("result").asText());
  String conv="shu_pg_"+UUID.randomUUID();result(call("/api/rp/sessions",HttpMethod.POST,Map.of("conversationId",conv,"roleId",role,"state",Map.of("mode","companion")),first));long history=stories.message(conv,"assistant","旧记忆");stories.finish(conv,history,"用户原话","旧记忆","complete",List.of(),"");
  result(call("/api/memory/"+history,HttpMethod.PUT,Map.of("conversationId",conv,"correction","已纠正的记忆","hidden",false),first));assertEquals(404,call("/api/memory/"+history,HttpMethod.PUT,Map.of("conversationId",conv,"correction","越权"),second).getStatusCode().value());
  assertEquals(404,call("/api/chat/proactive/trigger",HttpMethod.POST,Map.of("conversationId",conv),second).getStatusCode().value());
 }

 @Test void accountDeletionRemovesOnlyItsOwnFaultRecords()throws Exception{
  String operator=admin();var doomed=register(operator,"delete-fault-"+UUID.randomUUID()+"@test.example");var other=register(operator,"keep-fault-"+UUID.randomUUID()+"@test.example");int deleted=doomed.path("user").path("id").asInt(),kept=other.path("user").path("id").asInt();
  stories.db().update("INSERT INTO fault(user_id,category,created_at) VALUES(?,?,?)",deleted,"model_empty_output",Instant.now().toString());stories.db().update("INSERT INTO fault(user_id,category,created_at) VALUES(?,?,?)",kept,"network_timeout",Instant.now().toString());
  result(call("/api/v1/me/data",HttpMethod.DELETE,null,doomed.path("accessToken").asText()));
  assertEquals(0,stories.db().queryForObject("SELECT COUNT(*) FROM fault WHERE user_id=?",Integer.class,deleted));assertEquals(1,stories.db().queryForObject("SELECT COUNT(*) FROM fault WHERE user_id=?",Integer.class,kept));assertEquals(1,stories.db().queryForObject("SELECT COUNT(*) FROM account WHERE id=?",Integer.class,kept));assertEquals(401,call("/api/v1/me",HttpMethod.GET,null,doomed.path("accessToken").asText()).getStatusCode().value());
 }

 @Test void administratorSuspensionPreservesAdministrativeCancellationCause()throws Exception{
  String adminToken=admin();var actor=register(adminToken,"cancel-"+UUID.randomUUID()+"@test.example");int id=actor.path("user").path("id").asInt();String conv="cancel_"+UUID.randomUUID();
  var active=cancellationStreams.start(id,conv,"cancel-world-"+UUID.randomUUID());
  try{
   result(call("/api/v1/admin/users/"+id+"/status",HttpMethod.PUT,Map.of("status","suspended"),adminToken));
   assertTrue(active.control().stopped.get());assertEquals(CancelCause.ACCOUNT_SUSPENDED,active.control().cancellationCause());
   active.control().stop(CancelCause.CLIENT_DISCONNECTED);assertEquals("account_suspended",ServiceFailureCategory.classify(new java.io.IOException(),active.control()));
   assertEquals(401,call("/api/v1/me",HttpMethod.GET,null,actor.path("accessToken").asText()).getStatusCode().value());
  }finally{cancellationStreams.finish(conv,active);}
 }
}
