package com.framehub.video;

import static org.assertj.core.api.Assertions.*;

import com.framehub.video.commerce.provider.*;
import com.framehub.video.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.junit.jupiter.api.Test;

class WechatCryptoTest {
  @Test
  void validSignatureAcceptedAndTamperingRejected() throws Exception {
    var gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    var pair = gen.generateKeyPair();
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    String time = "" + clock.instant().getEpochSecond(), body = "{\"amount\":100}";
    var headers = new HashMap<String, String>();
    headers.put("Wechatpay-Timestamp", time);
    headers.put("Wechatpay-Nonce", "nonce");
    headers.put("Wechatpay-Serial", "PUB_KEY_ID_test");
    headers.put(
        "Wechatpay-Signature",
        WechatCrypto.sign(pair.getPrivate(), time + "\nnonce\n" + body + "\n"));
    WechatCrypto.verify(pair.getPublic(), "PUB_KEY_ID_test", headers, body, clock);
    assertThatThrownBy(
            () ->
                WechatCrypto.verify(
                    pair.getPublic(), "PUB_KEY_ID_test", headers, body + " ", clock))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> WechatCrypto.verify(pair.getPublic(), "wrong-key", headers, body, clock))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                WechatCrypto.verify(
                    pair.getPublic(),
                    "PUB_KEY_ID_test",
                    headers,
                    body,
                    Clock.offset(clock, Duration.ofMinutes(6))))
        .isInstanceOf(ApiException.class);
    headers.remove("Wechatpay-Signature");
    assertThatThrownBy(
            () -> WechatCrypto.verify(pair.getPublic(), "PUB_KEY_ID_test", headers, body, clock))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void aesGcmAuthenticatesCiphertextAndAssociatedData() throws Exception {
    String key = "01234567890123456789012345678901",
        nonce = "123456789012",
        aad = "transaction",
        body = "{\"mchid\":\"test\"}";
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
        new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
    c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
    byte[] cipher = c.doFinal(body.getBytes(StandardCharsets.UTF_8));
    String encoded = Base64.getEncoder().encodeToString(cipher);
    assertThat(WechatCrypto.decrypt(key, nonce, aad, encoded)).isEqualTo(body);
    assertThatThrownBy(() -> WechatCrypto.decrypt(key, nonce, "different", encoded))
        .isInstanceOf(ApiException.class);
    cipher[0] ^= 1;
    assertThatThrownBy(
            () -> WechatCrypto.decrypt(key, nonce, aad, Base64.getEncoder().encodeToString(cipher)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void merchantOrderIdFitsWechat32CharacterLimit() {
    String id = UUID.randomUUID().toString();
    assertThat(WechatCrypto.compact(id)).hasSize(32);
    assertThat(WechatCrypto.uuid(WechatCrypto.compact(id))).isEqualTo(id);
    assertThatThrownBy(() -> WechatCrypto.uuid("bad")).isInstanceOf(ApiException.class);
  }
}
