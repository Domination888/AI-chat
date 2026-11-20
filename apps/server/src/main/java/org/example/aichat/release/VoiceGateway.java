package org.example.aichat.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.io.*;

/** Local model pack and platform speech both expose OpenAI-compatible audio routes. */
@Service
public class VoiceGateway {
  private final RuntimeMode mode;private final ObjectMapper json;
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  public VoiceGateway(RuntimeMode mode,ObjectMapper json){this.mode=mode;this.json=json;}
  private URI endpoint(String key){String url=mode.value(key,"");if(!mode.cloud())NetworkPolicy.requireLoopback(url);return URI.create(url);}
  public String transcribe(String data,String format)throws Exception {
    return transcribe(data,format,"");
  }
  public String transcribe(String data,String format,String hotwords)throws Exception {
    if(data==null || data.length()>28*1024*1024)throw new IllegalArgumentException("录音不超过 20MB");byte[] audio=Base64.getDecoder().decode(data);
    if(audio.length>20*1024*1024)throw new IllegalArgumentException("录音过大");if(format==null || !Set.of("webm","wav","mp4","ogg").contains(format))format="webm";
    String boundary="story-"+UUID.randomUUID();var body=new ByteArrayOutputStream();
    body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"+mode.value("asr-model","SenseVoiceSmall")+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"hotwords\"\r\n\r\n"+hotwords.replaceAll("[\\r\\n]","")+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording."+format+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));body.write(audio);body.write(("\r\n--"+boundary+"--\r\n").getBytes());
    var r=client.send(HttpRequest.newBuilder(endpoint("asr-url")).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+mode.value("voice-key", "")).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),HttpResponse.BodyHandlers.ofByteArray());
    if(r.statusCode()!=200)throw new IOException("语音识别暂不可用");String text=json.readTree(r.body()).path("text").asText("");if(text.isBlank())throw new IllegalArgumentException("没有识别到清晰语音");return text;
  }
  public byte[] speech(String text)throws Exception {return speech(text,mode.value("tts-voice","shu"));}
  public byte[] speech(String text,String voiceId)throws Exception {
    if(text==null || text.length()>4000)throw new IllegalArgumentException("朗读文本不超过 4000 字符");
    text=text.replaceAll("（[^）]*）|\\([^)]*\\)","").strip();
    if(text.isBlank())throw new IllegalArgumentException("这段内容没有可朗读的台词");
    if("astra".equals(mode.value("tts-engine","astra")))return astra(text,voiceId);
    var payload=Map.of("model",mode.value("tts-model","Kokoro"),"input",text,"voice",mode.value("tts-voice","default"),"response_format","wav");
    var r=client.send(HttpRequest.newBuilder(endpoint("tts-url")).timeout(Duration.ofSeconds(60)).header("Authorization","Bearer "+mode.value("voice-key", "")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build(),HttpResponse.BodyHandlers.ofByteArray());
    if(r.statusCode()!=200 || !validWav(r.body()))throw new IOException("语音合成暂不可用或返回的音频不完整");return r.body();
  }
  private static boolean validWav(byte[] bytes){return bytes.length>=44&&bytes.length<=20*1024*1024&&new String(bytes,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")&&new String(bytes,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WAVE");}
  private volatile JsonNodeCache avatarCache;
  private record JsonNodeCache(com.fasterxml.jackson.databind.JsonNode avatars,long expires,String base){}
  static List<String> astraBases(boolean cloud,String configured){
    // The standalone Astra desktop app also uses port 5000. Only probe that
    // local compatibility address when the standard offline address is absent.
    return !cloud && "http://127.0.0.1:5001".equals(configured)
      ? List.of(configured,"http://127.0.0.1:5000") : List.of(configured);
  }
  public Object status()throws Exception {
    IOException failure=null;
    for(String base:astraBases(mode.cloud(),mode.value("astra-url","http://127.0.0.1:5001"))){
      if(!mode.cloud())NetworkPolicy.requireLoopback(base);
      try{var avatars=loadAvatars(base);avatarCache=new JsonNodeCache(avatars,System.currentTimeMillis()+600000,base);return avatars;}
      catch(IOException e){failure=e;}
    }
    throw new IOException("AstraTTS 未就绪，请启动本机语音服务",failure);
  }
  com.fasterxml.jackson.databind.JsonNode loadAvatars(String base)throws Exception {
    var r=client.send(HttpRequest.newBuilder(URI.create(base+"/api/tts/avatars")).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
    try(var body=r.body()){if(r.statusCode()!=200)throw new IOException("AstraTTS 未就绪（HTTP "+r.statusCode()+"）");var avatars=json.readTree(body.readNBytes(1024*1024));if(avatars==null||!avatars.isArray()||avatars.isEmpty())throw new IOException("AstraTTS 没有提供可用音色");return avatars;}
  }
  private byte[] astra(String text,String voiceId)throws Exception {
    if(text==null||text.length()>4000)throw new IllegalArgumentException("朗读文本不超过 4000 字");text=text.replaceAll("（[^）]*）|\\([^)]*\\)","").strip();if(text.isBlank())throw new IllegalArgumentException("这段内容没有可朗读的台词");
    if(avatarCache==null||avatarCache.expires()<System.currentTimeMillis())status();
    String avatar=resolveAvatar(avatarCache.avatars(),voiceId,mode.value("tts-voice","shu"));
    String base=avatarCache.base();if(!mode.cloud())NetworkPolicy.requireLoopback(base);
    if(text.codePointCount(0,text.length())<12)return astraWav(base,text,avatar);
    String languages=astraLanguages(text).stream().map(language->"&languages="+language).collect(java.util.stream.Collectors.joining());
    String query="/api/tts/predict-stream?text="+java.net.URLEncoder.encode(text,java.nio.charset.StandardCharsets.UTF_8)+"&avatarId="+java.net.URLEncoder.encode(avatar,java.nio.charset.StandardCharsets.UTF_8)+languages+"&chunkSize=22";
    try{var r=client.send(HttpRequest.newBuilder(URI.create(base+query)).timeout(Duration.ofSeconds(90)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());byte[] raw;
      try(var body=r.body()){if(r.statusCode()!=200)throw new IOException("AstraTTS 合成失败（HTTP "+r.statusCode()+"）");raw=body.readNBytes(40*1024*1024+1);if(raw.length==0||raw.length>40*1024*1024||raw.length%4!=0)throw new IOException("AstraTTS 返回的 PCM 音频不完整");}return floatWav(raw);
    }catch(IOException interruptedStream){return astraWav(base,text,avatar);}
  }
  static String resolveAvatar(com.fasterxml.jackson.databind.JsonNode avatars,String voiceId,String defaultVoice){
    boolean unassigned=voiceId==null||voiceId.isBlank()||"default".equals(voiceId);
    String requested=unassigned?Objects.toString(defaultVoice,"shu"):voiceId;
    if(requested.isBlank()||"default".equals(requested))requested="shu";
    for(var item:avatars)if(item.path("id").asText().equals(requested)||("shu".equals(requested)&&(item.path("name").asText().contains("黍")||item.path("id").asText().toLowerCase(Locale.ROOT).contains("shu"))))return item.path("id").asText();
    // Original and newly created roles have no assigned voice. Let them use an
    // available service voice; a deliberately assigned missing voice is an error.
    if(unassigned)for(var item:avatars)if(!item.path("id").asText().isBlank())return item.path("id").asText();
    throw new IllegalArgumentException("AstraTTS 中未找到角色音色："+requested+"，请检查本机音色配置");
  }
  private byte[] astraWav(String base,String text,String avatar)throws Exception{
    var request=HttpRequest.newBuilder(URI.create(base+"/api/tts/predict")).timeout(Duration.ofSeconds(90)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("text",text,"avatarId",avatar,"languages",astraLanguages(text))))).build();
    var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());try(var body=response.body()){byte[] wav=body.readNBytes(20*1024*1024+1);if(response.statusCode()!=200||wav.length<44||wav.length>20*1024*1024||!new String(wav,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")||!new String(wav,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WAVE"))throw new IOException("AstraTTS WAV 合成失败（HTTP "+response.statusCode()+"）");return wav;}
  }
  static List<String> astraLanguages(String text){return text.matches("(?s).*[A-Za-z].*")?List.of("zh","en"):List.of("zh");}
  static byte[] floatWav(byte[] raw){
    int samples=raw.length/4;var output=java.nio.ByteBuffer.allocate(44+samples*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    output.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36+samples*2).put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(32000).putInt(64000).putShort((short)2).putShort((short)16).put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(samples*2);
    var input=java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);while(input.hasRemaining()){float value=input.getFloat();if(!Float.isFinite(value))value=0;output.putShort((short)Math.round(Math.max(-1,Math.min(1,value))*32767));}return output.array();
  }

}
