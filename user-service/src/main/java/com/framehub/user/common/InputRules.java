package com.framehub.user.common;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class InputRules {
  private InputRules() {}

  public static String email(String email) {
    String s = email.strip().toLowerCase(Locale.ROOT);
    if (!StandardCharsets.US_ASCII.newEncoder().canEncode(s))
      throw new BusinessException(400, "邮箱格式不合法");
    return s;
  }

  public static void password(String value) {
    if (value == null
        || value.length() < 10
        || value.getBytes(StandardCharsets.UTF_8).length > 72
        || !value.matches(".*[A-Za-z].*")
        || !value.matches(".*[0-9].*"))
      throw new BusinessException(400, "密码需包含字母和数字，至少10字符且不超过72字节");
  }

  public static String text(String value, int max, boolean blank) {
    if (value == null
        || value.length() > max
        || (!blank && value.isBlank())
        || value.chars().anyMatch(c -> Character.isISOControl(c) || c == '<' || c == '>'))
      throw new BusinessException(400, "文本字段不合法");
    return value.strip();
  }
}
