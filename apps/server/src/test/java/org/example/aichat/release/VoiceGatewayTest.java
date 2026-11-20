package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

class VoiceGatewayTest {
 @Test void mixedChineseAndEnglishAreAllowedInBothStreamingAndBatchFallback()throws Exception{
  var json=new ObjectMapper();var streams=new AtomicInteger();var batches=new AtomicInteger();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);byte[] wav=VoiceGateway.floatWav(new byte[40]);
  server.createContext("/api/tts/avatars",e->{byte[] body="[{\"id\":\"Shu_2\",\"name\":\"黍\"}]".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
  server.createContext("/api/tts/predict-stream",e->{assertTrue(e.getRequestURI().getRawQuery().contains("languages=zh&languages=en"));streams.incrementAndGet();e.sendResponseHeaders(500,-1);e.close();});
  server.createContext("/api/tts/predict",e->{var body=json.readTree(e.getRequestBody());assertEquals(json.valueToTree(java.util.List.of("zh","en")),body.path("languages"));assertEquals("Shu_2",body.path("avatarId").asText());batches.incrementAndGet();e.sendResponseHeaders(200,wav.length);e.getResponseBody().write(wav);e.close();});server.start();
  try{var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline").withProperty("story.astra-url","http://127.0.0.1:"+server.getAddress().getPort()));var voice=new VoiceGateway(mode,json);
   assertArrayEquals(wav,voice.speech("AI日报要点：我们一起关注开源模型。","shu"));
   assertArrayEquals(wav,voice.speech("AI日报。","shu"));assertEquals(1,streams.get());assertEquals(2,batches.get());
  }finally{server.stop(0);}
 }
 @Test void offlineDefaultDiscoversTheStandaloneAstraPortWithoutChangingConfiguredOrCloudAddresses()throws Exception{
  assertEquals(java.util.List.of("http://127.0.0.1:5001","http://127.0.0.1:5000"),VoiceGateway.astraBases(false,"http://127.0.0.1:5001"));
  assertEquals(java.util.List.of("http://127.0.0.1:5001"),VoiceGateway.astraBases(true,"http://127.0.0.1:5001"));
  assertEquals(java.util.List.of("http://127.0.0.1:5555"),VoiceGateway.astraBases(false,"http://127.0.0.1:5555"));
  var json=new ObjectMapper();var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline"));
  var voice=org.mockito.Mockito.spy(new VoiceGateway(mode,json));
  org.mockito.Mockito.doThrow(new java.net.ConnectException()).when(voice).loadAvatars("http://127.0.0.1:5001");
  var avatars=json.readTree("[{\"id\":\"Shu_2\",\"name\":\"黍\"}]");
  org.mockito.Mockito.doReturn(avatars).when(voice).loadAvatars("http://127.0.0.1:5000");
  assertEquals(avatars,voice.status());
 }
 @Test void shortSpeechUsesBatchAndStreamFailureFallsBackToTheSameAvatar()throws Exception{
  var json=new ObjectMapper();var batch=new AtomicInteger();var streaming=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);byte[] wav=VoiceGateway.floatWav(new byte[40]);
  server.createContext("/api/tts/avatars",e->{byte[] body="[{\"id\":\"Shu_2\",\"name\":\"黍\"}]".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
  server.createContext("/api/tts/predict-stream",e->{streaming.incrementAndGet();e.sendResponseHeaders(500,-1);e.close();});
  server.createContext("/api/tts/predict",e->{var body=json.readTree(e.getRequestBody());assertEquals("Shu_2",body.path("avatarId").asText());batch.incrementAndGet();e.sendResponseHeaders(200,wav.length);e.getResponseBody().write(wav);e.close();});
  server.start();try{var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline").withProperty("story.astra-url","http://127.0.0.1:"+server.getAddress().getPort()));var voice=new VoiceGateway(mode,json);
   assertArrayEquals(wav,voice.speech("我是黍。","shu"));assertEquals(0,streaming.get());assertArrayEquals(wav,voice.speech("我们一起去看看田里的庄稼，今晚再做一顿好吃的饭吧。","shu"));assertEquals(1,streaming.get());assertEquals(2,batch.get());
  }finally{server.stop(0);}
 }
 @Test void malformedBatchAudioIsRejected()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api/tts/avatars",e->{byte[] body="[{\"id\":\"Shu_2\",\"name\":\"黍\"}]".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.createContext("/api/tts/predict",e->{byte[] body="not audio".getBytes();e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
  try{var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline").withProperty("story.astra-url","http://127.0.0.1:"+server.getAddress().getPort()));assertThrows(java.io.IOException.class,()->new VoiceGateway(mode,new ObjectMapper()).speech("你好。","shu"));}finally{server.stop(0);}
 }
 @Test void unassignedRolesUseTheDefaultVoiceAndExplicitMissingVoicesStillFail()throws Exception{
  var json=new ObjectMapper();var avatars=json.readTree("[{\"id\":\"chenxing\",\"name\":\"晨星\"},{\"id\":\"Shu_2\",\"name\":\"黍\"}]");
  assertEquals("Shu_2",VoiceGateway.resolveAvatar(avatars,"","default"));
  assertEquals("chenxing",VoiceGateway.resolveAvatar(avatars,null,"chenxing"));
  assertEquals("chenxing",VoiceGateway.resolveAvatar(json.readTree("[{\"id\":\"chenxing\"}]"),"","default"));
  assertThrows(IllegalArgumentException.class,()->VoiceGateway.resolveAvatar(avatars,"missing-character-voice","default"));
  assertThrows(IllegalArgumentException.class,()->VoiceGateway.resolveAvatar(json.readTree("[]"),"","default"));
 }

 @Test void packagedVoiceReadsDialogueOnlyAndRejectsNonAudio()throws Exception{
  var json=new ObjectMapper();var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);byte[] wav=VoiceGateway.floatWav(new byte[40]);
  server.createContext("/speech",e->{var request=json.readTree(e.getRequestBody());assertEquals("你好。",request.path("input").asText());byte[] body=calls.incrementAndGet()==1?wav:"not audio".getBytes();e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
  try{var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline").withProperty("story.tts-engine","kokoro").withProperty("story.tts-url","http://127.0.0.1:"+server.getAddress().getPort()+"/speech"));var voice=new VoiceGateway(mode,json);
   assertArrayEquals(wav,voice.speech("（她轻轻点头。）你好。",""));
   assertThrows(java.io.IOException.class,()->voice.speech("你好。",""));
   assertThrows(IllegalArgumentException.class,()->voice.speech("（她轻轻点头。）",""));assertEquals(2,calls.get());
  }finally{server.stop(0);}
 }

}
