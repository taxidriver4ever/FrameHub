package com.framehub.video;

import static org.assertj.core.api.Assertions.*;

import com.framehub.video.commerce.domain.*;
import com.framehub.video.commerce.dto.CommerceRequests.*;
import com.framehub.video.commerce.mapper.*;
import com.framehub.video.commerce.provider.*;
import com.framehub.video.commerce.service.*;
import com.framehub.video.common.ApiException;
import com.framehub.video.entity.*;
import com.framehub.video.mapper.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CommerceIntegrationTest {
  @Container
  static MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.4").withCommand("--log-bin-trust-function-creators=1");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", mysql::getJdbcUrl);
    r.add("spring.datasource.username", mysql::getUsername);
    r.add("spring.datasource.password", mysql::getPassword);
    r.add("video.endpoint", () -> "http://localhost:9000");
    r.add("video.public-endpoint", () -> "http://localhost:9000");
    r.add("video.access-key", () -> "test-access");
    r.add("video.secret-key", () -> "test-secret");
    r.add("video.consumer-enabled", () -> false);
    r.add("media.worker.enabled", () -> false);
    r.add("video.cleanup-enabled", () -> false);
    r.add("commerce.payment-provider", () -> "MOCK");
    r.add("commerce.withdrawal-provider", () -> "MOCK");
    r.add("commerce.jobs-enabled", () -> false);
    r.add("springdoc.api-docs.enabled", () -> true);
  }

  @MockitoBean com.framehub.video.storage.ObjectStorage storage;
  @MockitoBean com.framehub.video.security.IdentityClient identity;
  @Autowired com.framehub.video.commerce.consumer.CommerceEvents commerceEvents;
  @Autowired LedgerService ledger;
  @Autowired SettlementService settlements;
  @Autowired EntitlementMapper grants;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
  @Autowired WithdrawalService withdrawals;
  @Autowired WithdrawalProvider withdrawalProvider;
  @Autowired ReconciliationService reconciliation;
  @Autowired EntitlementService delivery;
  @Autowired CatalogService catalog;
  @Autowired CatalogMapper catalogDb;
  @Autowired OrderService orders;
  @Autowired PaymentService payments;
  @Autowired PaymentTransactions paymentTx;
  @Autowired PaymentProvider provider;
  @Autowired TradeMapper trades;
  @Autowired VideoMapper videos;
  @Autowired TaskMapper tasks;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  long creator, buyer;
  String material;

  @Test
  void concurrentChannelCreationUsesOnePayment() throws Exception {
    String id = UUID.randomUUID().toString();
    var request =
        new PaymentProvider.Request(
            id, 100, "CNY", "Concurrent create", java.time.Instant.now().plusSeconds(900));
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var calls = new ArrayList<Future<PaymentProvider.Result>>();
      for (int i = 0; i < 4; i++)
        calls.add(
            pool.submit(
                () -> {
                  start.await();
                  return provider.create(request);
                }));
      start.countDown();
      for (var call : calls)
        assertThat(call.get(15, TimeUnit.SECONDS).channelId()).isEqualTo("mock-" + id);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM mock_channel_payment WHERE payment_id=?", Integer.class, id))
        .isEqualTo(1);
  }

  @Test
  void openApiDocumentsBusinessRoutes() throws Exception {
    var response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/v3/api-docs"))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var document = json.readTree(response);
    for (String path :
        List.of(
            "/orders",
            "/materials",
            "/entitlements/{id}/download",
            "/withdrawals",
            "/admin/ledger/verify")) {
      assertThat(document.path("paths").has(path)).as(path).isTrue();
    }
    String export = System.getProperty("framehub.export-openapi");
    if (export != null)
      java.nio.file.Files.writeString(
          java.nio.file.Path.of(export),
          json.writerWithDefaultPrettyPrinter().writeValueAsString(document));
  }

  @BeforeEach
  void setup() {
    creator = (UUID.randomUUID().getMostSignificantBits() >>> 2) + 1;
    buyer = creator + 1;
    var v = new VideoAsset();
    v.videoId = UUID.randomUUID().toString();
    v.creatorId = "creator";
    v.userId = creator;
    v.title = "test";
    v.sourceKey = "private/" + v.videoId;
    v.sourceSize = 10;
    videos.insertVideo(v);
    videos.status(v.videoId, "READY", null);
    var t = new TranscodeTask();
    t.taskId = UUID.randomUUID().toString();
    t.videoId = v.videoId;
    t.eventId = UUID.randomUUID().toString();
    t.sourceVersion = "a".repeat(64);
    t.configVersion = "b".repeat(64);
    tasks.create(t);
    jdbc.update("UPDATE video_transcode_task SET status='READY' WHERE task_id=?", t.taskId);
    material =
        catalog
            .create(
                creator,
                new Draft(
                    v.videoId,
                    "Test film",
                    "Description",
                    "nature",
                    List.of("test"),
                    10000,
                    new LicenseTerms(true, "worldwide", 0, false, true, "One buyer license")))
            .id();
  }

  String listing() {
    return orders.publish(creator, material, new Idempotency(UUID.randomUUID().toString())).id();
  }

  String purchase() {
    jdbc.update("UPDATE material SET status='PUBLISHED' WHERE id=?", material);
    var m = catalog.get(creator, material);
    return orders
        .purchase(
            buyer,
            new Purchase(material, m.version(), m.licenseId(), true, UUID.randomUUID().toString()))
        .id();
  }

  @Test
  void immutableVersionsAndOwnership() {
    var first = catalog.get(creator, material);
    var second =
        catalog.edit(
            creator,
            material,
            new Edit(
                "Changed",
                null,
                null,
                null,
                20000L,
                new LicenseTerms(false, "local", 30, false, false, "New terms")));
    assertThat(second.version()).isEqualTo(2);
    assertThat(catalogDb.versionAt(material, 1).priceCent).isEqualTo(10000);
    assertThat(catalogDb.licenseAt(first.licenseId()).terms).contains("worldwide");
    assertThatThrownBy(() -> catalog.get(buyer, material)).isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE material_version SET title='overwrite' WHERE material_id=?", material))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void listingCannotPublishBeforePaymentAndDuplicateRequestsKeepOneOrder() {
    String key = UUID.randomUUID().toString();
    var a = orders.publish(creator, material, new Idempotency(key));
    var b = orders.publish(creator, material, new Idempotency(key));
    assertThat(a.id()).isEqualTo(b.id());
    assertThat(catalog.get(creator, material).status()).isEqualTo("PUBLISH_PAYMENT_PENDING");
    assertThatThrownBy(() -> catalog.publicGet(material)).isInstanceOf(ApiException.class);
    var p = payments.pay(creator, a.id());
    assertThat(payments.pay(creator, a.id()).id()).isEqualTo(p.id());
    payments.mockSuccess(creator, p.id());
    payments.mockSuccess(creator, p.id());
    assertThat(orders.get(creator, a.id()).status()).isEqualTo("PAID");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND"
                    + " event_type='PAYMENT_SUCCEEDED'",
                Integer.class,
                a.id()))
        .isEqualTo(1);
  }

  @Test
  void snapshotSurvivesNewPriceAndConflictingIdempotencyIsRejected() {
    String id = purchase();
    var order = orders.get(buyer, id);
    catalog.offShelf(creator, material);
    catalog.edit(creator, material, new Edit(null, null, null, null, 30000L, null));
    assertThat(orders.get(buyer, id).amountCent()).isEqualTo(10000);
    assertThat(order.license().path("terms").path("scope").asText()).isEqualTo("worldwide");
    var original = trades.order(id);
    assertThatThrownBy(
            () ->
                orders.purchase(
                    buyer,
                    new Purchase(
                        material,
                        2,
                        catalog.get(creator, material).licenseId(),
                        true,
                        original.idempotencyKey)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void channelSuccessWithLostLocalResponseIsRecoveredByClose() {
    String id = purchase();
    var p = payments.pay(buyer, id);
    ((MockPaymentProvider) provider).succeed(p.id());
    assertThat(trades.order(id).status).isEqualTo("PAYING");
    payments.close(buyer, id, false);
    assertThat(trades.order(id).status).isEqualTo("PAID");
  }

  @Test
  void cancelledChannelCannotBePaidAndDailyCounterIsIdempotent() {
    String id = purchase();
    var p = payments.pay(buyer, id);
    payments.close(buyer, id, false);
    payments.close(buyer, id, false);
    assertThat(trades.order(id).status).isEqualTo("CANCELLED");
    assertThatThrownBy(() -> payments.mockSuccess(buyer, p.id())).isInstanceOf(ApiException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT cancel_count FROM commerce_user_guard WHERE user_id=?",
                Integer.class,
                buyer))
        .isEqualTo(1);
  }

  @Test
  void refundRetryUsesSingleChannelRefundAndOutbox() {
    String id = purchase();
    var p = payments.pay(buyer, id);
    payments.mockSuccess(buyer, p.id());
    var req = new RefundRequest("refund-key", "requested");
    var r = payments.refund(buyer, id, req, false);
    assertThat(payments.refund(buyer, id, req, false).id()).isEqualTo(r.id());
    assertThat(trades.order(id).status).isEqualTo("REFUNDED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM mock_channel_refund WHERE payment_id=?",
                Integer.class,
                p.id()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND"
                    + " event_type='REFUND_SUCCEEDED'",
                Integer.class,
                id))
        .isEqualTo(1);
  }

  @Test
  void concurrentIdempotencyCreatesOnePurchase() throws Exception {
    jdbc.update("UPDATE material SET status='PUBLISHED' WHERE id=?", material);
    var m = catalog.get(creator, material);
    var req = new Purchase(material, m.version(), m.licenseId(), true, "parallel-key");
    try (var pool = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<Future<String>>();
      for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> orders.purchase(buyer, req).id()));
      Set<String> ids = new HashSet<>();
      for (var f : futures) ids.add(f.get(20, TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);
    }
  }

  @Test
  void forgedAmountRollsBackPayment() {
    String id = purchase();
    var p = payments.pay(buyer, id);
    assertThatThrownBy(
            () ->
                paymentTx.apply(
                    new PaymentProvider.Result(
                        p.id(), "SUCCEEDED", 1, "CNY", "bad", null, java.time.Instant.now()),
                    "MOCK"))
        .isInstanceOf(ApiException.class);
    assertThat(trades.order(id).status).isEqualTo("PAYING");
  }

  @Test
  void moneyUsesExactIntegerRounding() {
    assertThat(Money.rate(101, 1500)).isEqualTo(15);
    assertThat(Money.rate(1, 5000)).isEqualTo(1);
    assertThatThrownBy(() -> Money.amount(Long.MAX_VALUE)).isInstanceOf(ApiException.class);
    var r = catalogDb.currentRule();
    var f = Money.fees(10000, r, false);
    assertThat(f.commissionCent() + f.channelFeeCent() + f.creatorCent()).isEqualTo(10000);
  }

  @Test
  void pendingOrderLimitUsesDatabaseGuard() {
    for (int i = 0; i < 5; i++) purchase();
    assertThatThrownBy(this::purchase).isInstanceOf(ApiException.class).hasMessageContaining("上限");
    assertThat(trades.pending(buyer)).isEqualTo(5);
  }

  @Test
  void concurrentPayAndCloseCannotProducePaidCancelledOrder() throws Exception {
    String id = purchase();
    var p = payments.pay(buyer, id);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var paid =
          pool.submit(
              () -> {
                try {
                  payments.mockSuccess(buyer, p.id());
                } catch (ApiException e) {
                  assertThat(e.status).isEqualTo(409);
                }
              });
      var closed =
          pool.submit(
              () -> {
                try {
                  payments.close(buyer, id, false);
                } catch (ApiException e) {
                  assertThat(e.status).isEqualTo(409);
                }
              });
      paid.get(20, TimeUnit.SECONDS);
      closed.get(20, TimeUnit.SECONDS);
    }
    var channel = provider.query(p.id());
    assertThat(trades.order(id).status)
        .isEqualTo(channel.status().equals("SUCCEEDED") ? "PAID" : "CANCELLED");
  }

  @Test
  void duplicateAndConflictingNotificationAreAtomic() {
    String id = purchase();
    var p = payments.pay(buyer, id);
    var result = ((MockPaymentProvider) provider).succeed(p.id());
    var n = new PaymentProvider.Notification("notification-" + id, "a".repeat(64), result, null);
    paymentTx.notification(n, "MOCK");
    paymentTx.notification(n, "MOCK");
    assertThatThrownBy(
            () ->
                paymentTx.notification(
                    new PaymentProvider.Notification(n.id(), "b".repeat(64), result, null), "MOCK"))
        .isInstanceOf(ApiException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_notification WHERE notification_id=?",
                Integer.class,
                n.id()))
        .isEqualTo(1);
  }

  String event(String order, String type) throws Exception {
    var row =
        jdbc.queryForMap(
            "SELECT event_id,event_type,event_version,aggregate_type,aggregate_id FROM outbox_event"
                + " WHERE aggregate_id=? AND event_type=?",
            order,
            type);
    return json.writeValueAsString(
        Map.of(
            "database",
            "framehub_video",
            "table",
            "outbox_event",
            "type",
            "INSERT",
            "isDdl",
            false,
            "data",
            List.of(row)));
  }

  String paidPurchase() throws Exception {
    String id = purchase();
    var p = payments.pay(buyer, id);
    payments.mockSuccess(buyer, p.id());
    commerceEvents.accept(event(id, "PAYMENT_SUCCEEDED"));
    return id;
  }

  @Test
  void paidEventPublishesListingAndDuplicatesDoNotDoubleBook() throws Exception {
    String id = listing();
    var p = payments.pay(creator, id);
    payments.mockSuccess(creator, p.id());
    String raw = event(id, "PAYMENT_SUCCEEDED");
    commerceEvents.accept(raw);
    commerceEvents.accept(raw);
    assertThat(catalog.publicGet(material).status()).isEqualTo("PUBLISHED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_journal WHERE business_key=?",
                Integer.class,
                "payment:" + id))
        .isEqualTo(1);
    assertThat(ledger.verify().get("unbalancedJournals")).asList().isEmpty();
  }

  @Test
  void paidEventGrantsOnceAndReleaseIsIdempotent() throws Exception {
    String id = paidPurchase();
    commerceEvents.accept(event(id, "PAYMENT_SUCCEEDED"));
    assertThat(grants.byOrder(id)).isNotNull();
    assertThat(ledger.balances(creator).get("frozenCent")).isEqualTo(8440L);
    jdbc.update("UPDATE earning_freeze SET release_at=CURRENT_TIMESTAMP(6) WHERE order_id=?", id);
    assertThat(settlements.release(id)).isTrue();
    assertThat(settlements.release(id)).isFalse();
    assertThat(ledger.balances(creator).get("availableCent")).isEqualTo(8440L);
    assertThat(ledger.verify().get("accountMismatches")).asList().isEmpty();
  }

  @Test
  void refundBeforePaidEventDoesNotReactivateEntitlementOrRepeatReversal() throws Exception {
    String id = purchase();
    var p = payments.pay(buyer, id);
    payments.mockSuccess(buyer, p.id());
    payments.refund(buyer, id, new RefundRequest("refund", "refund"), false);
    commerceEvents.accept(event(id, "REFUND_SUCCEEDED"));
    commerceEvents.accept(event(id, "PAYMENT_SUCCEEDED"));
    commerceEvents.accept(event(id, "REFUND_SUCCEEDED"));
    assertThat(grants.byOrder(id).status).isEqualTo("REVOKED");
    assertThat(ledger.balances(creator).get("frozenCent")).isEqualTo(0L);
    assertThat(ledger.balances(creator).get("availableCent")).isEqualTo(0L);
    assertThat(ledger.verify().get("accountMismatches")).asList().isEmpty();
    assertThat(ledger.verify().get("unbalancedJournals")).asList().isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_journal WHERE reference_id=?", Integer.class, id))
        .isEqualTo(2);
  }

  @Test
  void committedJournalCannotBeEditedOrDeleted() throws Exception {
    String id = paidPurchase();
    String journal =
        jdbc.queryForObject(
            "SELECT id FROM ledger_journal WHERE business_key=?", String.class, "payment:" + id);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE ledger_entry SET delta_cent=1 WHERE journal_id=?", journal))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entry WHERE journal_id=?", journal))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  void released(String order) {
    jdbc.update(
        "UPDATE earning_freeze SET release_at=CURRENT_TIMESTAMP(6) WHERE order_id=?", order);
    settlements.release(order);
    withdrawals.saveAccount(creator, new WithdrawalAccount("Test creator", "mock-account"));
  }

  @Test
  void concurrentWithdrawalsCannotOverdrawAvailableFunds() throws Exception {
    String id = paidPurchase();
    released(id);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int n = 0; n < 2; n++)
        results.add(
            pool.submit(
                () -> {
                  try {
                    withdrawals.create(
                        creator, new WithdrawalRequest(6000, UUID.randomUUID().toString()));
                    return true;
                  } catch (ApiException e) {
                    assertThat(e.status).isEqualTo(409);
                    return false;
                  }
                }));
      int success = 0;
      for (var f : results) if (f.get(20, TimeUnit.SECONDS)) success++;
      assertThat(success).isEqualTo(1);
    }
    assertThat(ledger.balances(creator).get("availableCent")).isEqualTo(2440L);
    assertThat(ledger.balances(creator).get("payoutPendingCent")).isEqualTo(6000L);
  }

  @Test
  void withdrawalFailureReleasesFundsExactlyOnce() throws Exception {
    String id = paidPurchase();
    released(id);
    var req = new WithdrawalRequest(8440, "withdraw");
    var w = withdrawals.create(creator, req);
    assertThat(withdrawals.create(creator, req).id()).isEqualTo(w.id());
    withdrawals.mockComplete(creator, w.id(), false);
    withdrawals.mockComplete(creator, w.id(), false);
    assertThat(ledger.balances(creator).get("availableCent")).isEqualTo(8440L);
    assertThat(ledger.balances(creator).get("payoutPendingCent")).isEqualTo(0L);
    assertThat(ledger.verify().get("accountMismatches")).asList().isEmpty();
  }

  @Test
  void refundAfterWithdrawalCreatesDebtAndLaterIncomeRepaysFirst() throws Exception {
    String id = paidPurchase();
    released(id);
    var w = withdrawals.create(creator, new WithdrawalRequest(8440, "withdraw"));
    withdrawals.mockComplete(creator, w.id(), true);
    payments.refund(buyer, id, new RefundRequest("refund", "refund after payout"), false);
    commerceEvents.accept(event(id, "REFUND_SUCCEEDED"));
    assertThat(ledger.balances(creator).get("debtCent")).isEqualTo(8440L);
    assertThatThrownBy(() -> withdrawals.create(creator, new WithdrawalRequest(100, "more")))
        .isInstanceOf(ApiException.class);
    String later = paidPurchase();
    released(later);
    assertThat(ledger.balances(creator).get("debtCent")).isEqualTo(0L);
    assertThat(ledger.balances(creator).get("availableCent")).isEqualTo(0L);
    assertThat(ledger.verify().get("unbalancedJournals")).asList().isEmpty();
  }

  @Test
  void refundDuringPendingWithdrawalThenFailureRepaysDebt() throws Exception {
    String id = paidPurchase();
    released(id);
    var w = withdrawals.create(creator, new WithdrawalRequest(8440, "withdraw"));
    payments.refund(buyer, id, new RefundRequest("refund", "pending payout"), false);
    commerceEvents.accept(event(id, "REFUND_SUCCEEDED"));
    assertThat(ledger.balances(creator).get("debtCent")).isEqualTo(8440L);
    withdrawals.mockComplete(creator, w.id(), false);
    assertThat(ledger.balances(creator).get("availableCent")).isEqualTo(0L);
    assertThat(ledger.balances(creator).get("debtCent")).isEqualTo(0L);
    assertThat(ledger.balances(creator).get("payoutPendingCent")).isEqualTo(0L);
  }

  @Test
  void lostWithdrawalResponseRecoveredByQueryWithoutSecondDebit() throws Exception {
    String id = paidPurchase();
    released(id);
    var w = withdrawals.create(creator, new WithdrawalRequest(8440, "withdraw"));
    ((MockWithdrawalProvider) withdrawalProvider).complete(w.id(), true);
    assertThat(withdrawals.get(creator, w.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(withdrawals.get(creator, w.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(ledger.balances(creator).get("payoutPendingCent")).isEqualTo(0L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_journal WHERE business_key=?",
                Integer.class,
                "withdraw-result:" + w.id()))
        .isEqualTo(1);
  }

  @Test
  void inboxAndJournalRollBackWhenEntitlementInsertFails() throws Exception {
    String id = purchase();
    var p = payments.pay(buyer, id);
    payments.mockSuccess(buyer, p.id());
    String raw = event(id, "PAYMENT_SUCCEEDED");
    jdbc.execute(
        "CREATE TRIGGER test_fail_grant BEFORE INSERT ON entitlement FOR EACH ROW SIGNAL SQLSTATE"
            + " '45000' SET MESSAGE_TEXT='simulated crash'");
    try {
      assertThatThrownBy(() -> commerceEvents.accept(raw)).isInstanceOf(RuntimeException.class);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM ledger_journal WHERE business_key=?",
                  Integer.class,
                  "payment:" + id))
          .isEqualTo(0);
      assertThat(grants.byOrder(id)).isNull();
    } finally {
      jdbc.execute("DROP TRIGGER test_fail_grant");
    }
    commerceEvents.accept(raw);
    assertThat(grants.byOrder(id)).isNotNull();
  }

  @Test
  void downloadsRequireOwnershipAndPaidUnrevokedEntitlement() throws Exception {
    String id = paidPurchase();
    var e = grants.byOrder(id);
    org.mockito.Mockito.when(storage.head(e.sourceKey))
        .thenReturn(
            software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder()
                .contentLength(10L)
                .build());
    org.mockito.Mockito.when(
            storage.signedGet(
                org.mockito.ArgumentMatchers.eq(e.sourceKey),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class)))
        .thenReturn("http://private-test/signed");
    assertThat(delivery.download(buyer, e.id).get("expiresIn")).isEqualTo(120L);
    assertThatThrownBy(() -> delivery.download(creator, e.id)).isInstanceOf(ApiException.class);
    payments.refund(buyer, id, new RefundRequest("refund", "refund"), false);
    assertThatThrownBy(() -> delivery.download(buyer, e.id)).isInstanceOf(ApiException.class);
    commerceEvents.accept(event(id, "REFUND_SUCCEEDED"));
    assertThatThrownBy(() -> delivery.download(buyer, e.id)).isInstanceOf(ApiException.class);
  }

  @Test
  void fixtureReconciliationRepairsOnlyVerifiedChannelTruth() throws Exception {
    String id = purchase();
    var p = payments.pay(buyer, id);
    var channel = ((MockPaymentProvider) provider).succeed(p.id());
    var rows =
        List.of(
            new PaymentProvider.BillRow(
                "PAYMENT", p.id(), channel.channelId(), p.amountCent(), "SUCCEEDED", null));
    var day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
    var batch = reconciliation.fixture(creator, day, rows);
    assertThat(trades.order(id).status).isEqualTo("PAID");
    assertThat(reconciliation.differences(batch.id()))
        .anyMatch(
            d ->
                d.referenceId().equals(p.id())
                    && d.kind().equals("STATUS_MISMATCH")
                    && d.status().equals("REPAIRED"));
    assertThat(reconciliation.fixture(creator, day, rows).id()).isEqualTo(batch.id());
    String fake = purchase();
    var fp = payments.pay(buyer, fake);
    var fakeBatch =
        reconciliation.fixture(
            creator,
            day,
            List.of(
                new PaymentProvider.BillRow(
                    "PAYMENT", fp.id(), "forged-channel", fp.amountCent(), "SUCCEEDED", null)));
    assertThat(trades.order(fake).status).isEqualTo("PAYING");
    assertThat(reconciliation.differences(fakeBatch.id()))
        .anyMatch(d -> d.referenceId().equals(fp.id()) && d.status().equals("OPEN"));
  }

  @Test
  void reconciliationRecordsMissingAndAmountMismatchWithoutChangingMoney() throws Exception {
    String id = paidPurchase();
    var p = trades.paymentForOrder(id);
    var day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
    var wrong =
        reconciliation.fixture(
            creator,
            day,
            List.of(
                new PaymentProvider.BillRow(
                    "PAYMENT", p.id, p.channelId, p.amountCent + 1, "SUCCEEDED", null)));
    assertThat(reconciliation.differences(wrong.id()))
        .anyMatch(d -> d.referenceId().equals(p.id) && d.kind().equals("AMOUNT_MISMATCH"));
    var missing = reconciliation.fixture(creator, day, List.of());
    assertThat(reconciliation.differences(missing.id()))
        .anyMatch(d -> d.referenceId().equals(p.id) && d.kind().equals("CHANNEL_MISSING"));
    assertThat(ledger.balances(creator).get("frozenCent")).isEqualTo(8440L);
  }
}
