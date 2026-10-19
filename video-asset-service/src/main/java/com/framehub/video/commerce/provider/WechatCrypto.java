package com.framehub.video.commerce.provider;

import com.framehub.video.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public final class WechatCrypto {
  private WechatCrypto() {}

  public static PrivateKey privateKey(Path p) {
    try {
      return KeyFactory.getInstance("RSA")
          .generatePrivate(new PKCS8EncodedKeySpec(pem(p, "PRIVATE KEY")));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot load merchant PKCS8 private key", e);
    }
  }

  public static PublicKey publicKey(Path p) {
    try {
      return KeyFactory.getInstance("RSA")
          .generatePublic(new X509EncodedKeySpec(pem(p, "PUBLIC KEY")));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot load WeChat platform public key", e);
    }
  }

  private static byte[] pem(Path p, String type) throws Exception {
    return Base64.getDecoder()
        .decode(
            Files.readString(p)
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", ""));
  }

  public static String sign(PrivateKey key, String data) {
    try {
      Signature s = Signature.getInstance("SHA256withRSA");
      s.initSign(key);
      s.update(data.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(s.sign());
    } catch (Exception e) {
      throw new IllegalStateException("Cannot sign WeChat request", e);
    }
  }

  public static void verify(
      PublicKey key, String expectedKeyId, Map<String, String> headers, String body, Clock clock) {
    try {
      Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      h.putAll(headers);
      String timestamp = h.get("Wechatpay-Timestamp"),
          nonce = h.get("Wechatpay-Nonce"),
          signature = h.get("Wechatpay-Signature");
      long seconds = Long.parseLong(timestamp);
      if (!Objects.equals(expectedKeyId, h.get("Wechatpay-Serial"))
          || nonce == null
          || nonce.isBlank()
          || seconds < clock.instant().getEpochSecond() - 300
          || seconds > clock.instant().getEpochSecond() + 300) throw new IllegalArgumentException();
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(key);
      verifier.update(
          (timestamp + "\n" + nonce + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
      if (!verifier.verify(Base64.getDecoder().decode(signature)))
        throw new IllegalArgumentException();
    } catch (Exception e) {
      throw new ApiException(401, "微信支付签名校验失败");
    }
  }

  public static String decrypt(String key, String nonce, String associated, String ciphertext) {
    try {
      byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
      if (bytes.length != 32) throw new IllegalArgumentException();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(bytes, "AES"),
          new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
      if (associated != null) cipher.updateAAD(associated.getBytes(StandardCharsets.UTF_8));
      return new String(
          cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new ApiException(401, "微信支付通知解密失败");
    }
  }

  public static String compact(String uuid) {
    return UUID.fromString(uuid).toString().replace("-", "");
  }

  public static String uuid(String compact) {
    if (compact == null || !compact.matches("[0-9a-fA-F]{32}"))
      throw new ApiException(400, "支付单号格式错误");
    return UUID.fromString(
            compact.substring(0, 8)
                + "-"
                + compact.substring(8, 12)
                + "-"
                + compact.substring(12, 16)
                + "-"
                + compact.substring(16, 20)
                + "-"
                + compact.substring(20))
        .toString();
  }
}
