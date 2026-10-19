package com.framehub.video.commerce.provider;

import com.framehub.video.common.ApiException;

public class DisabledWithdrawalProvider implements WithdrawalProvider {
  public String name() {
    return "DISABLED";
  }

  public Result submit(Request r) {
    throw new ApiException(503, "真实提现渠道尚未配置");
  }

  public Result query(String id) {
    throw new ApiException(503, "真实提现渠道尚未配置");
  }
}
