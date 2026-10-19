package com.framehub.video.commerce.provider;

import com.framehub.video.common.ApiException;
import java.math.BigDecimal;
import java.util.*;

/** WeChat ALL bill CSV: validate the header rather than depending on column offsets. */
public final class WechatBillParser {
  private WechatBillParser() {}

  public static List<PaymentProvider.BillRow> parse(String csv) {
    String[] lines = csv.replace("\ufeff", "").split("\r?\n");
    if (lines.length < 2) throw new ApiException(502, "微信账单内容为空");
    List<String> header = row(lines[0]);
    for (String key :
        List.of("商户订单号", "微信支付订单号", "交易状态", "订单金额", "商户退款单号", "微信退款单号", "申请退款金额", "退款状态", "手续费"))
      if (!header.contains(key)) throw new ApiException(502, "微信账单列不受支持: " + key);
    List<PaymentProvider.BillRow> out = new ArrayList<>();
    for (int i = 1; i < lines.length; i++) {
      if (lines[i].isBlank()) continue;
      if (lines[i].startsWith("总交易单数")) break;
      List<String> values = row(lines[i]);
      if (values.size() != header.size()) throw new ApiException(502, "微信账单行列数异常");
      Map<String, String> m = new HashMap<>();
      for (int j = 0; j < header.size(); j++) m.put(header.get(j), values.get(j));
      String status = m.get("交易状态");
      if (status.equals("SUCCESS"))
        out.add(
            new PaymentProvider.BillRow(
                "PAYMENT",
                WechatCrypto.uuid(m.get("商户订单号")),
                m.get("微信支付订单号"),
                cents(m.get("订单金额")),
                "SUCCEEDED",
                cents(m.get("手续费"))));
      else if (status.equals("REFUND")) {
        if (m.get("退款状态").equals("SUCCESS"))
          out.add(
              new PaymentProvider.BillRow(
                  "REFUND",
                  WechatCrypto.uuid(m.get("商户退款单号")),
                  m.get("微信退款单号"),
                  cents(m.get("申请退款金额")),
                  "SUCCEEDED",
                  null));
      } else throw new ApiException(502, "未知微信账单交易状态");
      if (out.size() > 100000) throw new ApiException(502, "账单超过单批处理上限");
    }
    return out;
  }

  private static long cents(String s) {
    try {
      return new BigDecimal(s).movePointRight(2).longValueExact();
    } catch (Exception e) {
      throw new ApiException(502, "微信账单金额无效");
    }
  }

  private static List<String> row(String line) {
    List<String> cells = new ArrayList<>();
    StringBuilder b = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          b.append(c);
          i++;
        } else quoted = !quoted;
      } else if (c == ',' && !quoted) {
        cells.add(clean(b.toString()));
        b.setLength(0);
      } else b.append(c);
    }
    if (quoted) throw new ApiException(502, "微信账单 CSV 引号不完整");
    cells.add(clean(b.toString()));
    return cells;
  }

  private static String clean(String s) {
    s = s.strip();
    return s.startsWith("`") ? s.substring(1) : s;
  }
}
