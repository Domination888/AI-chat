package org.example.aichat.release;

import java.security.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.*;
import javax.crypto.spec.*;

public final class Secrets {
  private static final SecureRandom RANDOM=new SecureRandom();
  private Secrets() {}
  public static String token() { byte[] b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
  public static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch(Exception e) { throw new IllegalStateException(e); }
  }
  public static String encrypt(String value,byte[] key,String owner) {
    try {
      byte[] iv=new byte[12];RANDOM.nextBytes(iv);var c=Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));c.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
      byte[] result=c.doFinal(value.getBytes(StandardCharsets.UTF_8));
      return "v1:"+Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(result);
    } catch(Exception e) { throw new IllegalStateException("Unable to encrypt secret",e); }
  }
  public static String decrypt(String value,byte[] key,String owner) {
    try {
      String[] fields=value.split(":");if(fields.length!=3 || !fields[0].equals("v1")) throw new IllegalArgumentException();
      var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(fields[1])));
      c.updateAAD(owner.getBytes(StandardCharsets.UTF_8));return new String(c.doFinal(Base64.getDecoder().decode(fields[2])),StandardCharsets.UTF_8);
    } catch(Exception e) { throw new IllegalStateException("Unable to decrypt secret",e); }
  }
}
