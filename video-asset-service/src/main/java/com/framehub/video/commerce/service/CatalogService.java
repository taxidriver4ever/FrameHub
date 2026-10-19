package com.framehub.video.commerce.service;

import com.framehub.video.commerce.domain.*;
import com.framehub.video.commerce.domain.CommerceEntities.*;
import com.framehub.video.commerce.dto.CommerceRequests.*;
import com.framehub.video.commerce.dto.CommerceViews.*;
import com.framehub.video.commerce.mapper.CatalogMapper;
import com.framehub.video.common.ApiException;
import com.framehub.video.mapper.TaskMapper;
import com.framehub.video.service.VideoService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
  private final CatalogMapper db;
  private final TaskMapper tasks;
  private final VideoService videos;
  private final JsonData json;

  public CatalogService(CatalogMapper db, TaskMapper tasks, VideoService videos, JsonData json) {
    this.db = db;
    this.tasks = tasks;
    this.videos = videos;
    this.json = json;
  }

  @Transactional
  public MaterialView create(long user, Draft req) {
    var video = videos.owned(user, req.videoId());
    var task = tasks.byVideo(video.videoId);
    if (!video.status.equals("READY") || task == null || !task.status.equals("READY"))
      throw new ApiException(409, "视频尚未完成处理");
    Material m = new Material();
    m.id = UUID.randomUUID().toString();
    m.ownerId = user;
    m.videoId = video.videoId;
    m.currentVersion = 1;
    m.status = "DRAFT";
    db.insert(m);
    License license = license(m, 1, req.license());
    MaterialVersion v = new MaterialVersion();
    v.materialId = m.id;
    v.version = 1;
    v.title = req.title().strip();
    v.description = req.description();
    v.category = req.category().strip();
    v.tags = json.write(req.tags());
    v.priceCent = Money.amount(req.priceCent());
    v.licenseId = license.id;
    v.sourceKey = video.sourceKey;
    v.sourceVersion = task.sourceVersion;
    db.version(v);
    audit(user, "MATERIAL_CREATED", m.id, Map.of("version", 1));
    return view(db.material(m.id));
  }

  @Transactional
  public MaterialView edit(long user, String id, Edit req) {
    Material m = owned(user, id, true);
    if (!Set.of("DRAFT", "OFF_SHELF").contains(m.status))
      throw new ApiException(409, "请先下架素材，再编辑新版本");
    MaterialVersion v = db.versionAt(id, m.currentVersion);
    v.version = ++m.currentVersion;
    if (req.title() != null) {
      if (req.title().isBlank()) throw new ApiException(400, "标题不能为空");
      v.title = req.title().strip();
    }
    if (req.description() != null) v.description = req.description();
    if (req.category() != null) {
      if (req.category().isBlank()) throw new ApiException(400, "分类不能为空");
      v.category = req.category().strip();
    }
    if (req.tags() != null) v.tags = json.write(req.tags());
    if (req.priceCent() != null) v.priceCent = Money.amount(req.priceCent());
    if (req.license() != null) v.licenseId = license(m, v.version, req.license()).id;
    db.version(v);
    db.state(id, v.version, "DRAFT");
    audit(user, "MATERIAL_VERSION_CREATED", id, Map.of("version", v.version));
    return view(db.material(id));
  }

  private License license(Material m, int version, LicenseTerms terms) {
    License l = new License();
    l.id = UUID.randomUUID().toString();
    l.materialId = m.id;
    l.version = version;
    l.terms = json.write(terms);
    db.license(l);
    return l;
  }

  public Material owned(long user, String id, boolean lock) {
    Material m = lock ? db.lock(id) : db.material(id);
    if (m == null) throw new ApiException(404, "素材不存在");
    if (m.ownerId != user) throw new ApiException(403, "无权操作该素材");
    return m;
  }

  public Material published(String id) {
    Material m = db.material(id);
    if (m == null || !m.status.equals("PUBLISHED")) throw new ApiException(404, "素材未上架");
    return m;
  }

  public MaterialView get(long user, String id) {
    return view(owned(user, id, false));
  }

  public MaterialView publicGet(String id) {
    return view(published(id));
  }

  public Object preview(String id) {
    Material m = published(id);
    return videos.playback(m.ownerId, m.videoId);
  }

  public List<MaterialView> mine(long user, int page, int size) {
    page(page, size);
    return db.owned(user, size, (long) (page - 1) * size).stream().map(this::view).toList();
  }

  public List<MaterialView> market(String category, String query, int page, int size) {
    page(page, size);
    if (category != null && category.length() > 64 || query != null && query.length() > 128)
      throw new ApiException(400, "搜索条件过长");
    return db.market(category, query, size, (long) (page - 1) * size).stream()
        .map(this::view)
        .toList();
  }

  public static void page(int page, int size) {
    if (page < 1 || size < 1 || size > 100) throw new ApiException(400, "分页参数不合法");
  }

  @Transactional
  public MaterialView offShelf(long user, String id) {
    Material m = owned(user, id, true);
    if (m.status.equals("PUBLISH_PAYMENT_PENDING")) throw new ApiException(409, "请先取消待支付上架订单");
    db.state(id, m.currentVersion, "OFF_SHELF");
    audit(user, "MATERIAL_OFF_SHELF", id, Map.of());
    return view(db.material(id));
  }

  @Transactional
  public MaterialView moderate(long admin, String id) {
    Material m = db.lock(id);
    if (m == null) throw new ApiException(404, "素材不存在");
    db.state(id, m.currentVersion, "REJECTED");
    audit(admin, "MATERIAL_REJECTED", id, Map.of());
    return view(db.material(id));
  }

  public MaterialView view(Material m) {
    MaterialVersion v = db.versionAt(m.id, m.currentVersion);
    License l = db.licenseAt(v.licenseId);
    return new MaterialView(
        m.id,
        "" + m.ownerId,
        m.videoId,
        v.version,
        m.status,
        v.title,
        v.description,
        v.category,
        json.read(v.tags),
        v.priceCent,
        l.id,
        l.version,
        json.read(l.terms),
        m.createdAt);
  }

  public void audit(long user, String action, String aggregate, Object details) {
    db.audit(UUID.randomUUID().toString(), user, action, aggregate, json.write(details));
  }

  @Transactional
  public Map<String, Object> rule(long admin, Rule req) {
    if (req.commissionBps() + req.channelFeeBps() > 10000)
      throw new ApiException(400, "费率合计不得超过 10000 BPS");
    FeeRule prior = db.lastRule(), r = new FeeRule();
    r.id = UUID.randomUUID().toString();
    r.version = prior.version + 1;
    r.listingFeeCent = req.listingFeeCent();
    r.commissionBps = req.commissionBps();
    r.channelFeeBps = req.channelFeeBps();
    r.minimumWithdrawalCent = req.minimumWithdrawalCent();
    r.freezeSeconds = req.freezeSeconds();
    r.effectiveAt = req.effectiveAt();
    r.createdBy = admin;
    db.ruleInsert(r);
    audit(admin, "FEE_RULE_CREATED", r.id, req);
    return Map.of("version", r.version, "effectiveAt", r.effectiveAt);
  }

  public Object currentRule() {
    var r = db.currentRule();
    return Map.of(
        "version",
        r.version,
        "listingFeeCent",
        r.listingFeeCent,
        "commissionBps",
        r.commissionBps,
        "channelFeeBps",
        r.channelFeeBps,
        "minimumWithdrawalCent",
        r.minimumWithdrawalCent,
        "freezeSeconds",
        r.freezeSeconds);
  }
}
