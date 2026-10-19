package com.framehub.video.commerce.service;

import com.framehub.video.commerce.config.CommerceProperties;
import com.framehub.video.commerce.domain.*;
import com.framehub.video.commerce.domain.CommerceEntities.*;
import com.framehub.video.commerce.dto.CommerceRequests.*;
import com.framehub.video.commerce.dto.CommerceViews;
import com.framehub.video.commerce.mapper.*;
import com.framehub.video.common.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
  private final TradeMapper db;
  private final CatalogMapper catalog;
  private final CatalogService materials;
  private final JsonData json;
  private final CommerceProperties p;

  public OrderService(
      TradeMapper db,
      CatalogMapper catalog,
      CatalogService materials,
      JsonData json,
      CommerceProperties p) {
    this.db = db;
    this.catalog = catalog;
    this.materials = materials;
    this.json = json;
    this.p = p;
  }

  @Transactional
  public CommerceViews.OrderView purchase(long user, Purchase req) {
    lockUser(user);
    String hash =
        json.hash(
            List.of(
                "PURCHASE",
                req.materialId(),
                req.materialVersion(),
                req.licenseId(),
                req.acceptLicense()));
    Order old = existing(user, req.idempotencyKey(), hash);
    if (old != null) return view(old);
    Material m = catalog.lock(req.materialId());
    if (m == null || !m.status.equals("PUBLISHED")) throw new ApiException(409, "素材未上架");
    if (m.ownerId == user) throw new ApiException(400, "不能购买自己的素材");
    MaterialVersion v = catalog.versionAt(m.id, m.currentVersion);
    if (v.version != req.materialVersion()
        || !v.licenseId.equals(req.licenseId())
        || !req.acceptLicense()) throw new ApiException(409, "素材或协议版本已变化，请重新确认");
    return view(create(user, m, v, "PURCHASE", req.idempotencyKey(), hash));
  }

  @Transactional
  public CommerceViews.OrderView publish(long user, String id, Idempotency req) {
    lockUser(user);
    String hash = json.hash(List.of("LISTING", id));
    Order old = existing(user, req.idempotencyKey(), hash);
    if (old != null) return view(old);
    Material m = materials.owned(user, id, true);
    if (m.status.equals("REJECTED")) throw new ApiException(409, "素材审核未通过");
    Order previous = db.listing(id, m.currentVersion);
    if (previous != null) {
      if (previous.status.equals("PAID")) {
        catalog.state(id, m.currentVersion, "PUBLISHED");
        materials.audit(user, "MATERIAL_REPUBLISHED", id, Map.of("orderId", previous.id));
      }
      return view(previous);
    }
    MaterialVersion v = catalog.versionAt(id, m.currentVersion);
    Order o = create(user, m, v, "LISTING", req.idempotencyKey(), hash);
    catalog.state(id, m.currentVersion, "PUBLISH_PAYMENT_PENDING");
    return view(o);
  }

  private Order create(
      long user, Material m, MaterialVersion v, String type, String key, String hash) {
    if (db.pending(user) >= p.maxPendingOrders()) throw new ApiException(429, "待支付订单已达上限");
    FeeRule rule = catalog.currentRule();
    Money.Fees f =
        Money.fees(
            type.equals("LISTING") ? rule.listingFeeCent : v.priceCent,
            rule,
            type.equals("LISTING"));
    License l = catalog.licenseAt(v.licenseId);
    Order o = new Order();
    o.id = UUID.randomUUID().toString();
    o.userId = user;
    o.creatorId = m.ownerId;
    o.orderType = type;
    o.materialId = m.id;
    o.materialVersion = v.version;
    o.status = "PENDING";
    o.idempotencyKey = key;
    o.requestHash = hash;
    o.amountCent = f.grossCent();
    o.commissionCent = f.commissionCent();
    o.channelFeeCent = f.channelFeeCent();
    o.creatorCent = f.creatorCent();
    o.ruleVersion = rule.version;
    o.feeSnapshot = json.write(f);
    o.licenseSnapshot =
        json.write(Map.of("id", l.id, "version", l.version, "terms", json.read(l.terms)));
    o.licenseConfirmedAt = Instant.now();
    o.expiresAt = o.licenseConfirmedAt.plus(p.orderTtl());
    db.insert(o);
    materials.audit(user, "ORDER_CREATED", o.id, Map.of("type", type, "amountCent", o.amountCent));
    return o;
  }

  public UserGuard lockUser(long user) {
    db.ensureUser(user);
    return db.lockUser(user);
  }

  private Order existing(long user, String key, String hash) {
    Order o = db.idempotent(user, key);
    if (o != null && !o.requestHash.equals(hash)) throw new ApiException(409, "幂等键已用于其他请求");
    return o;
  }

  public Order owned(long user, String id) {
    Order o = db.order(id);
    if (o == null) throw new ApiException(404, "订单不存在");
    if (o.userId != user) throw new ApiException(403, "无权查看该订单");
    return o;
  }

  public CommerceViews.OrderView get(long user, String id) {
    return view(owned(user, id));
  }

  public List<CommerceViews.OrderView> mine(long user, int page, int size) {
    CatalogService.page(page, size);
    return db.mine(user, size, (long) (page - 1) * size).stream().map(this::view).toList();
  }

  public List<CommerceViews.OrderView> all(int page, int size) {
    CatalogService.page(page, size);
    return db.all(size, (long) (page - 1) * size).stream().map(this::view).toList();
  }

  public CommerceViews.OrderView view(Order o) {
    return CommerceViews.order(o, json);
  }
}
