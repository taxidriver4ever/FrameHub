package com.framehub.video.commerce.provider;

import com.framehub.video.commerce.domain.JsonData;
import com.framehub.video.commerce.mapper.WithdrawalMapper;
import com.framehub.video.commerce.mapper.WithdrawalMapper.MockWithdrawal;
import com.framehub.video.common.ApiException;
import org.springframework.transaction.annotation.*;

@Transactional(propagation = Propagation.REQUIRES_NEW)
public class MockWithdrawalProvider implements WithdrawalProvider {
  private final WithdrawalMapper db;
  private final JsonData json;

  public MockWithdrawalProvider(WithdrawalMapper db, JsonData json) {
    this.db = db;
    this.json = json;
  }

  public String name() {
    return "MOCK";
  }

  public Result submit(Request req) {
    MockWithdrawal w = new MockWithdrawal();
    w.withdrawalId = req.id();
    w.amountCent = req.amountCent();
    w.accountHash = json.hash(req.accountSnapshot());
    w.channelId = "mock-withdraw-" + req.id();
    w.status = "PROCESSING";
    db.mockInsert(w);
    var old = db.mockLock(req.id());
    if (old.amountCent != req.amountCent() || !old.accountHash.equals(w.accountHash))
      throw new ApiException(409, "模拟提现幂等请求不一致");
    return result(old);
  }

  public Result query(String id) {
    var w = db.mockGet(id);
    if (w == null) throw new ApiException(404, "渠道提现单不存在");
    return result(w);
  }

  public Result complete(String id, boolean success) {
    var w = db.mockLock(id);
    if (w == null) throw new ApiException(404, "渠道提现单不存在");
    String state = success ? "SUCCEEDED" : "FAILED";
    if (!w.status.equals("PROCESSING") && !w.status.equals(state))
      throw new ApiException(409, "模拟提现已结束");
    w.status = state;
    db.mockUpdate(w);
    return result(w);
  }

  private Result result(MockWithdrawal w) {
    return new Result(w.withdrawalId, w.amountCent, w.status, w.channelId);
  }
}
