package org.example.aichat.release.cloud;
import org.example.aichat.release.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Path-style S3, including MinIO; credentials never leave the server. */
@Component @ConditionalOnProperty(name="story.mode",havingValue="cloud")
public class S3Objects implements ObjectStore {
  private final String endpoint,bucket,access,secret,region;
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
  public S3Objects(RuntimeMode mode) {
    endpoint=mode.value("s3-endpoint","").replaceAll("/+$","");bucket=mode.value("s3-bucket","");access=mode.value("s3-access-key","");secret=mode.value("s3-secret-key","");region=mode.value("s3-region","us-east-1");
    if(endpoint.isBlank()||!bucket.matches("[a-z0-9.-]+")||access.isBlank()||secret.isBlank())throw new IllegalStateException("Configure STORY_S3 endpoint, bucket and credentials");
    var u=URI.create(endpoint);if(u.getHost()==null || u.getQuery()!=null || u.getUserInfo()!=null || (!u.getPath().isEmpty()&&!u.getPath().equals("/")))throw new IllegalStateException("S3 endpoint must be an origin URL");
  }
  private byte[] hmac(byte[] key,String text)throws Exception {var m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(text.getBytes(StandardCharsets.UTF_8));}
  private HttpResponse<byte[]> request(String method,String key,byte[] bytes,String type)throws Exception {
    if(!key.matches("[0-9]+/[0-9a-f-]{36}"))throw new IllegalArgumentException("Invalid object key");
    var now=Instant.now();String date=DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(now),stamp=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now);
    URI uri=URI.create(endpoint+"/"+bucket+"/"+key);String host=uri.getRawAuthority(),payload=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    String headers="host:"+host+"\nx-amz-content-sha256:"+payload+"\nx-amz-date:"+stamp+"\n";
    String signed="host;x-amz-content-sha256;x-amz-date",scope=date+"/"+region+"/s3/aws4_request";
    String canonical=method+"\n"+uri.getRawPath()+"\n\n"+headers+"\n"+signed+"\n"+payload;
    byte[] k=hmac(("AWS4"+secret).getBytes(StandardCharsets.UTF_8),date);k=hmac(k,region);k=hmac(k,"s3");k=hmac(k,"aws4_request");
    String stringToSign="AWS4-HMAC-SHA256\n"+stamp+"\n"+scope+"\n"+Secrets.hash(canonical);
    String auth="AWS4-HMAC-SHA256 Credential="+access+"/"+scope+", SignedHeaders="+signed+", Signature="+HexFormat.of().formatHex(hmac(k,stringToSign));
    var r=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("x-amz-content-sha256",payload).header("x-amz-date",stamp).header("Authorization",auth).header("Content-Type",type).method(method,HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
    var response=client.send(r,HttpResponse.BodyHandlers.ofByteArray());
    if(response.statusCode()<200 || response.statusCode()>=300)throw new java.io.IOException("Object storage operation failed");return response;
  }
  public void put(String key,byte[] bytes,String type)throws Exception {request("PUT",key,bytes,type);}
  public byte[] get(String key)throws Exception {return request("GET",key,new byte[0],"application/octet-stream").body();}
  public void delete(String key)throws Exception {request("DELETE",key,new byte[0],"application/octet-stream");}
}
