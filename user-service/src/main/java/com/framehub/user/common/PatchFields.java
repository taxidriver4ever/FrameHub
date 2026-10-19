package com.framehub.user.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

public final class PatchFields {
  private PatchFields() {}

  public static void validate(JsonNode patch, String... fields) {
    if (!patch.isObject()) throw new BusinessException(400, "需要JSON对象");
    Set<String> allowed = Set.of(fields);
    patch
        .fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name)) throw new BusinessException(400, "不允许修改字段：" + name);
            });
  }

  public static String text(JsonNode patch, String name, int max, boolean nullable) {
    JsonNode n = patch.get(name);
    if (n.isNull() && nullable) return null;
    if (!n.isTextual()) throw new BusinessException(400, "字段格式不合法");
    return InputRules.text(n.textValue(), max, nullable);
  }
}
