package com.framehub.video.commerce.service;

import com.framehub.video.commerce.config.CommerceProperties;
import com.framehub.video.commerce.domain.CommerceEntities.Entitlement;
import com.framehub.video.commerce.domain.JsonData;
import com.framehub.video.commerce.mapper.*;
import com.framehub.video.common.ApiException;
import com.framehub.video.storage.ObjectStorage;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class EntitlementService {
  private final EntitlementMapper db;
  private final TradeMapper trades;
  private final ObjectStorage storage;
  private final CommerceProperties p;
  private final JsonData json;
  private final CatalogService audit;

  public EntitlementService(
      EntitlementMapper db,
      TradeMapper trades,
      ObjectStorage storage,
      CommerceProperties p,
      JsonData json,
      CatalogService audit) {
    this.db = db;
    this.trades = trades;
    this.storage = storage;
    this.p = p;
    this.json = json;
    this.audit = audit;
  }

  public record View(
      String id,
      String orderId,
      String materialId,
      int materialVersion,
      String status,
      com.fasterxml.jackson.databind.JsonNode license,
      Instant expiresAt,
      Instant createdAt) {}

  public List<View> mine(long user, int page, int size) {
    CatalogService.page(page, size);
    return db.mine(user, size, (long) (page - 1) * size).stream().map(this::view).toList();
  }

  private View view(Entitlement e) {
    String status = e.status;
    if (status.equals("ACTIVE") && e.expiresAt != null && !e.expiresAt.isAfter(Instant.now()))
      status = "EXPIRED";
    return new View(
        e.id,
        e.orderId,
        e.materialId,
        e.materialVersion,
        status,
        json.read(e.licenseSnapshot),
        e.expiresAt,
        e.createdAt);
  }

  public Map<String, Object> download(long user, String id) {
    Entitlement e = db.get(id);
    if (e == null) throw new ApiException(404, "授权不存在");
    if (e.userId != user) throw new ApiException(403, "没有此素材下载权限");
    var o = trades.order(e.orderId);
    if (!e.status.equals("ACTIVE")
        || !o.status.equals("PAID")
        || e.expiresAt != null && !e.expiresAt.isAfter(Instant.now()))
      throw new ApiException(403, "授权已过期、退款中或已撤销");
    if (storage.head(e.sourceKey) == null) throw new ApiException(503, "原始素材暂不可用");
    Duration ttl = p.downloadTtl();
    if (e.expiresAt != null && Duration.between(Instant.now(), e.expiresAt).compareTo(ttl) < 0)
      ttl = Duration.between(Instant.now(), e.expiresAt);
    if (ttl.isNegative() || ttl.isZero()) throw new ApiException(403, "授权已到期");
    String url = storage.signedGet(e.sourceKey, ttl);
    audit.audit(user, "DOWNLOAD_URL_ISSUED", e.id, Map.of("expiresIn", ttl.toSeconds()));
    return Map.of(
        "url", url, "expiresIn", ttl.toSeconds(), "license", json.read(e.licenseSnapshot));
  }
}
