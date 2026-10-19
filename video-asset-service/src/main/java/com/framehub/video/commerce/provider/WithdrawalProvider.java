package com.framehub.video.commerce.provider;

public interface WithdrawalProvider {
  String name();

  record Request(String id, long amountCent, String accountSnapshot) {}

  record Result(String id, long amountCent, String status, String channelId) {}

  Result submit(Request request);

  Result query(String id);
}
