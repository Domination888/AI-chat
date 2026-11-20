package org.example.aichat.release;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.Base64;

class SecurityPolicyTest {
 @Test void offlineOptInAcceptsProxyDnsHostnamesWithoutChangingCloudPolicy()throws Exception{
   // .invalid makes this deterministic: local validation must not use DNS to
   // classify a hostname that the user's proxy resolves at connection time.
   assertEquals("api.provider.invalid",NetworkPolicy.offlineExternalHttps("https://api.provider.invalid/v1").getHost());
   assertThrows(IllegalArgumentException.class,()->NetworkPolicy.publicHttps("https://api.provider.invalid/v1"));
   for(String ip:java.util.List.of("198.18.0.124","198.19.255.255","fdfe:dcba:9876::73"))assertFalse(NetworkPolicy.isPublic(InetAddress.getByName(ip)),ip);
   for(String url:java.util.List.of("https://198.18.0.124","https://[fdfe:dcba:9876::73]","https://127.0.0.1","https://10.0.0.1","https://localhost","https://api.localhost","http://api.provider.invalid","https://api.provider.invalid:8443","https://key@api.provider.invalid","https://api.provider.invalid?key=value","https://api.provider.invalid#fragment"))assertThrows(IllegalArgumentException.class,()->NetworkPolicy.offlineExternalHttps(url),url);
 }
 @Test void privateAndReservedAddressesAreRejected()throws Exception{for(String ip:java.util.List.of("127.0.0.1","10.0.0.1","172.16.0.1","192.168.1.1","169.254.169.254","100.64.1.1","::1","fc00::1","fe80::1","0.0.0.0"))assertFalse(NetworkPolicy.isPublic(InetAddress.getByName(ip)),ip);assertThrows(Exception.class,()->NetworkPolicy.publicHttps("http://example.com"));assertThrows(Exception.class,()->NetworkPolicy.publicHttps("https://127.0.0.1"));assertThrows(Exception.class,()->NetworkPolicy.publicHttps("https://key@example.com"));}
 @Test void secretsAreBoundToTheirAccountAndTamperingFails(){byte[] key=Base64.getUrlDecoder().decode(Secrets.token());String cipher=Secrets.encrypt("secret",key,"user:1");assertEquals("secret",Secrets.decrypt(cipher,key,"user:1"));assertThrows(Exception.class,()->Secrets.decrypt(cipher,key,"user:2"));assertThrows(Exception.class,()->Secrets.decrypt(cipher+"broken",key,"user:1"));}
 @Test void vectorEncodingAndCosineAreStable(){float[] a={1,2,3};assertArrayEquals(a,MemoryIndex.decode(MemoryIndex.encode(a)));assertEquals(1,MemoryIndex.cosine(a,a),.00001);assertEquals(0,MemoryIndex.cosine(a,new float[3]),.00001);}
}
