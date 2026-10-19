package com.framehub.video.commerce.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.framehub.video.commerce.config.WechatProperties;
import com.framehub.video.commerce.domain.JsonData;
import com.framehub.video.commerce.mapper.TradeMapper;
import com.framehub.video.common.ApiException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

public class WechatPaymentProvider implements PaymentProvider {
  private final WechatProperties p;
  private final JsonData json;
  private final TradeMapper db;
  private final PrivateKey privateKey;
  private final PublicKey platformKey;
  private final HttpClient client;

  public WechatPaymentProvider(WechatProperties p, JsonData json, TradeMapper db) {
    this.p = p;
    this.json = json;
    this.db = db;
    if (!p.enabled())
      throw new IllegalStateException("WeChat payments require explicit enabled=true");
    if (p.merchantId() == null
        || !p.merchantId().matches("[0-9]{6,32}")
        || p.appId() == null
        || !p.appId().matches("[A-Za-z0-9]{1,32}")
        || p.merchantSerial() == null
        || !p.merchantSerial().matches("[A-Za-z0-9_]{1,128}")
        || p.platformKeyId() == null
        || p.platformKeyId().isBlank()
        || p.apiV3Key() == null
        || p.apiV3Key().getBytes(StandardCharsets.UTF_8).length != 32
        || p.notifyUrl() == null
        || !p.notifyUrl().startsWith("https://"))
      throw new IllegalStateException("WeChat credentials or HTTPS notify URL are incomplete");
    privateKey = WechatCrypto.privateKey(p.privateKeyPath());
    platformKey = WechatCrypto.publicKey(p.platformPublicKeyPath());
    client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public String name() {
    return "WECHAT";
  }

  public Result create(Request r) {
    if (r.amountCent() > Integer.MAX_VALUE) throw new ApiException(400, "金额超过微信支付整数范围");
    try {
      Result old = query(r.paymentId());
      if (!old.status().equals("PENDING")) return old;
    } catch (ApiException e) {
      if (e.status != 404) throw e;
      if (!r.expiresAt().isAfter(Instant.now()))
        return closed(r.paymentId(), r.amountCent(), r.currency());
    }
    Map<String, Object> body =
        Map.of(
            "appid",
            p.appId(),
            "mchid",
            p.merchantId(),
            "description",
            r.description(),
            "out_trade_no",
            WechatCrypto.compact(r.paymentId()),
            "time_expire",
            r.expiresAt().atOffset(ZoneOffset.ofHours(8)).toString(),
            "notify_url",
            p.notifyUrl(),
            "amount",
            Map.of("total", r.amountCent(), "currency", r.currency()));
    JsonNode n = call("POST", "/v3/pay/transactions/native", body);
    String url = required(n, "code_url");
    if (!url.startsWith("weixin://")) throw new ApiException(502, "微信支付返回无效二维码地址");
    return new Result(r.paymentId(), "PENDING", r.amountCent(), r.currency(), null, url, null);
  }

  public Result query(String id) {
    return payment(
        call(
            "GET",
            "/v3/pay/transactions/out-trade-no/"
                + WechatCrypto.compact(id)
                + "?mchid="
                + p.merchantId(),
            null));
  }

  public Result close(String id) {
    try {
      Result before = query(id);
      if (!before.status().equals("PENDING")) return before;
      try {
        call(
            "POST",
            "/v3/pay/transactions/out-trade-no/" + WechatCrypto.compact(id) + "/close",
            Map.of("mchid", p.merchantId()));
      } catch (ApiException e) {
        if (e.status != 409) throw e;
      }
      return query(id);
    } catch (ApiException e) {
      if (e.status != 404) throw e;
      var known = db.payment(id);
      if (known == null) throw e;
      return closed(id, known.amountCent, known.currency);
    }
  }

  private Result closed(String id, long amount, String currency) {
    return new Result(id, "CLOSED", amount, currency, null, null, null);
  }

  public RefundResult refund(String id, String payment, long amount, long total, String reason) {
    try {
      return queryRefund(id);
    } catch (ApiException e) {
      if (e.status != 404) throw e;
    }
    return refundResult(
        call(
            "POST",
            "/v3/refund/domestic/refunds",
            Map.of(
                "out_trade_no",
                WechatCrypto.compact(payment),
                "out_refund_no",
                WechatCrypto.compact(id),
                "reason",
                reason.substring(0, Math.min(80, reason.length())),
                "notify_url",
                p.notifyUrl(),
                "amount",
                Map.of("refund", amount, "total", total, "currency", "CNY"))));
  }

  public RefundResult queryRefund(String id) {
    return refundResult(
        call("GET", "/v3/refund/domestic/refunds/" + WechatCrypto.compact(id), null));
  }

  public Notification notification(Map<String, String> headers, String body) {
    if (body.getBytes(StandardCharsets.UTF_8).length > 1048576)
      throw new ApiException(413, "支付通知过大");
    WechatCrypto.verify(platformKey, p.platformKeyId(), headers, body, Clock.systemUTC());
    JsonNode n = json.read(body), resource = n.path("resource");
    if (!required(resource, "algorithm").equals("AEAD_AES_256_GCM"))
      throw new ApiException(400, "未知支付通知加密算法");
    JsonNode data =
        json.read(
            WechatCrypto.decrypt(
                p.apiV3Key(),
                required(resource, "nonce"),
                resource.path("associated_data").asText(null),
                required(resource, "ciphertext")));
    String type = required(n, "event_type"), id = required(n, "id");
    if (id.length() > 128) throw new ApiException(400, "通知标识过长");
    if (type.equals("TRANSACTION.SUCCESS")) {
      Result result = payment(data);
      if (!result.status().equals("SUCCEEDED")) throw new ApiException(400, "支付通知状态不一致");
      return new Notification(id, json.hash(body), result, null);
    }
    if (Set.of("REFUND.SUCCESS", "REFUND.ABNORMAL", "REFUND.CLOSED").contains(type)) {
      merchant(data, false);
      return new Notification(id, json.hash(body), null, refundResult(data));
    }
    throw new ApiException(400, "不支持的支付通知类型");
  }

  private Result payment(JsonNode n) {
    merchant(n, true);
    String status =
        switch (required(n, "trade_state")) {
          case "SUCCESS" -> "SUCCEEDED";
          case "CLOSED", "REVOKED", "PAYERROR" -> "CLOSED";
          case "NOTPAY", "USERPAYING" -> "PENDING";
          case "REFUND" -> "SUCCEEDED";
          default -> throw new ApiException(502, "未知微信支付状态");
        };
    return new Result(
        WechatCrypto.uuid(required(n, "out_trade_no")),
        status,
        amount(n.path("amount"), "total"),
        required(n.path("amount"), "currency"),
        n.path("transaction_id").asText(null),
        null,
        n.hasNonNull("success_time")
            ? OffsetDateTime.parse(n.path("success_time").asText()).toInstant()
            : null);
  }

  private RefundResult refundResult(JsonNode n) {
    if (n.has("mchid")) merchant(n, false);
    String currency = required(n.path("amount"), "currency");
    if (!currency.equals("CNY")) throw new ApiException(409, "退款币种不一致");
    String state = n.has("refund_status") ? required(n, "refund_status") : required(n, "status");
    String status =
        switch (state) {
          case "SUCCESS" -> "SUCCEEDED";
          case "PROCESSING", "ABNORMAL" -> "PROCESSING";
          case "CLOSED" -> "FAILED";
          default -> throw new ApiException(502, "未知微信退款状态");
        };
    return new RefundResult(
        WechatCrypto.uuid(required(n, "out_refund_no")),
        WechatCrypto.uuid(required(n, "out_trade_no")),
        status,
        amount(n.path("amount"), "refund"),
        required(n, "refund_id"));
  }

  private void merchant(JsonNode n, boolean app) {
    if (!p.merchantId().equals(required(n, "mchid"))
        || app && !p.appId().equals(required(n, "appid")))
      throw new ApiException(401, "微信支付商户或应用不匹配");
  }

  private long amount(JsonNode n, String key) {
    if (!n.path(key).isIntegralNumber()
        || !n.path(key).canConvertToLong()
        || n.path(key).longValue() < 0) throw new ApiException(502, "渠道金额无效");
    return n.path(key).longValue();
  }

  private String required(JsonNode n, String key) {
    if (!n.path(key).isTextual() || n.path(key).asText().isBlank())
      throw new ApiException(502, "渠道缺少字段: " + key);
    return n.path(key).asText();
  }

  private JsonNode call(String method, String path, Object body) {
    var response =
        send(
            method,
            URI.create("https://api.mch.weixin.qq.com" + path),
            body == null ? "" : json.write(body),
            2 * 1024 * 1024);
    String text = new String(response.body, StandardCharsets.UTF_8);
    Map<String, String> headers = new HashMap<>();
    response.headers.map().forEach((k, v) -> headers.put(k, v.getFirst()));
    WechatCrypto.verify(platformKey, p.platformKeyId(), headers, text, Clock.systemUTC());
    if (response.status < 200 || response.status >= 300) {
      JsonNode error = json.read(text);
      String code = error.path("code").asText();
      if (Set.of("ORDER_NOT_EXIST", "RESOURCE_NOT_EXISTS").contains(code))
        throw new ApiException(404, "渠道订单不存在");
      if (Set.of("ORDERPAID", "ORDER_CLOSED").contains(code))
        throw new ApiException(409, "渠道订单状态已变化");
      throw new ApiException(502, "微信支付请求失败: " + code);
    }
    return text.isBlank() ? json.read("{}") : json.read(text);
  }

  private record Response(int status, HttpHeaders headers, byte[] body) {}

  private Response send(String method, URI uri, String body, int max) {
    try {
      if (!uri.getScheme().equals("https")
          || !uri.getHost().equals("api.mch.weixin.qq.com")
          || uri.getPort() != -1) throw new IllegalArgumentException("Unexpected WeChat endpoint");
      String timestamp = Long.toString(Instant.now().getEpochSecond()),
          nonce = UUID.randomUUID().toString().replace("-", "");
      String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
      String signature =
          WechatCrypto.sign(
              privateKey,
              method + "\n" + target + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n");
      String auth =
          "WECHATPAY2-SHA256-RSA2048 mchid=\""
              + p.merchantId()
              + "\",nonce_str=\""
              + nonce
              + "\",signature=\""
              + signature
              + "\",timestamp=\""
              + timestamp
              + "\",serial_no=\""
              + p.merchantSerial()
              + "\"";
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", auth)
              .header("Accept", "application/json")
              .header("Content-Type", "application/json")
              .header("User-Agent", "FrameHub/1.0")
              .method(
                  method,
                  body.isEmpty()
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        var read = new java.util.concurrent.FutureTask<byte[]>(() -> stream.readNBytes(max + 1));
        Thread.ofVirtual().start(read);
        byte[] data;
        try { data = read.get(15, java.util.concurrent.TimeUnit.SECONDS); }
        finally { read.cancel(true); }
        if (data.length > max) throw new ApiException(502, "微信响应超过允许大小");
        return new Response(response.statusCode(), response.headers(), data);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(503, "支付请求中断");
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(502, "微信支付通信失败，请稍后查单");
    }
  }

  public List<BillRow> bill(LocalDate day) {
    JsonNode n = call("GET", "/v3/bill/tradebill?bill_date=" + day + "&bill_type=ALL", null);
    if (!required(n, "hash_type").equals("SHA1")) throw new ApiException(502, "未知账单校验算法");
    var response = send("GET", URI.create(required(n, "download_url")), "", 32 * 1024 * 1024);
    if (response.status != 200) throw new ApiException(502, "账单下载失败");
    try {
      String digest =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(response.body));
      if (!digest.equalsIgnoreCase(required(n, "hash_value")))
        throw new ApiException(502, "账单摘要校验失败");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    return WechatBillParser.parse(new String(response.body, StandardCharsets.UTF_8));
  }
}
