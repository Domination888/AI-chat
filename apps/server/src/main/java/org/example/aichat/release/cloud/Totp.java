package org.example.aichat.release.cloud;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.time.Instant;

public final class Totp {
  private Totp() {}
  static byte[] decode(String text) {
    String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";var out=new java.io.ByteArrayOutputStream();int bits=0,value=0;
    for(char c:text.toUpperCase().replace("=","").replace(" ","").toCharArray()) {int n=alphabet.indexOf(c);if(n<0)throw new IllegalArgumentException("Invalid TOTP secret");value=(value<<5)|n;bits+=5;if(bits>=8){bits-=8;out.write((value>>bits)&255);}}
    return out.toByteArray();
  }
  public static String code(String key,long tick) {
    try {
      var m=Mac.getInstance("HmacSHA1");m.init(new SecretKeySpec(decode(key),"HmacSHA1"));byte[] b=m.doFinal(ByteBuffer.allocate(8).putLong(tick).array());int o=b[19]&15;
      int v=((b[o]&127)<<24)|((b[o+1]&255)<<16)|((b[o+2]&255)<<8)|(b[o+3]&255);return String.format("%06d",v%1000000);
    }catch(Exception e){throw new IllegalStateException(e);}
  }
  public static boolean valid(String key,String value) {
    if(value==null || !value.matches("\\d{6}"))return false;
    long now=Instant.now().getEpochSecond()/30;
    for(long d=-1;d<=1;d++) if(java.security.MessageDigest.isEqual(value.getBytes(),code(key,now+d).getBytes()))return true;
    return false;
  }
}
