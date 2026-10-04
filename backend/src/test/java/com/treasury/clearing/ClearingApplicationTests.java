package com.treasury.clearing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Map;

/**
 * Full-stack acceptance on the seeded dataset:
 * - NA-CNY  : 8 open claims (2 rings) collapse to 2 real cash legs,
 *             pledged + disputed excluded;
 * - NA-NOFF : closed ring preserved as 3 original debts;
 * - NA-XCCY : mixed-currency ring nets into CNY with rate/time/diff recorded;
 * - lifecycle: SIMULATED -> CONFIRMED -> PAID_SIMULATED, claims only settle on confirm.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClearingApplicationTests {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    // ------------------------------------------------------------------
    // Batch-scoped deferral acceptance (run before the lifecycle test,
    // which settles the NA-CNY ring claims on confirm).
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void deferredOpenInvoiceIsExcludedWithReasonAndAmountsChange() throws Exception {
        // Hold one open NA-CNY invoice out of this batch: 600 B->C. The remaining
        // five ring invoices must produce different totals and one DEFERRED row.
        JsonNode sim = simulate(LocalDate.of(2026, 9, 30), Map.of("agreementCodes",
                json.createArrayNode().add("NA-CNY")),
                java.util.List.of(Map.of("claimId", "CLM-R1-002", "reason", "凭证尚未办妥，暂缓入池")));

        JsonNode cny = group(sim, "NA-CNY");
        assertThat(cny.get("originalLegCount").asInt()).isEqualTo(5);
        assertThat(cny.get("grossAmount").decimalValue()).isEqualByComparingTo("2000.00");
        // Ring without B->C 600: positions A +600, B -200, C -400 -> net 1000 (was 600).
        assertThat(cny.get("netAmount").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(sim.get("excludedClaimCount").asInt()).isEqualTo(3); // pledged + disputed + deferred

        boolean found = false;
        for (JsonNode ex : cny.get("exclusions")) {
            if (ex.get("reasonCode").asText().equals("DEFERRED")) {
                assertThat(ex.get("claimId").asText()).isEqualTo("CLM-R1-002");
                assertThat(ex.get("invoiceNo").asText()).isEqualTo("INV-B-2001");
                assertThat(ex.get("amount").decimalValue()).isEqualByComparingTo("600.00");
                assertThat(ex.get("currency").asText()).isEqualTo("CNY");
                assertThat(ex.get("reasonDetail").asText()).contains("凭证尚未办妥");
                found = true;
            }
        }
        assertThat(found).isTrue();
        // The deferred invoice has no leg / trace items in this batch.
        for (JsonNode leg : cny.get("legs")) {
            for (JsonNode it : leg.get("items")) {
                assertThat(it.get("claimId").asText()).isNotEqualTo("CLM-R1-002");
            }
        }
        // And the original claim row is untouched.
        assertThat(findClaim(claims(), "CLM-R1-002").get("status").asText()).isEqualTo("OPEN");
        assertThat(findClaim(claims(), "CLM-R1-002").get("offsetBatchId").asText()).isEmpty();
    }

    @Test
    @Order(2)
    void sameInvoicePoolsAgainWhenNotDeferredOnNextTrial() throws Exception {
        JsonNode sim = simulate(LocalDate.of(2026, 9, 30), Map.of("agreementCodes",
                json.createArrayNode().add("NA-CNY")), java.util.List.of());

        JsonNode cny = group(sim, "NA-CNY");
        assertThat(cny.get("originalLegCount").asInt()).isEqualTo(6);
        assertThat(cny.get("grossAmount").decimalValue()).isEqualByComparingTo("2600.00");
        assertThat(cny.get("netAmount").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(reasons(cny)).containsExactlyInAnyOrder("PLEDGED", "DISPUTED");
        assertThat(cashLegs(cny)).isEqualTo(2);
    }

    @Test
    @Order(3)
    void invalidDeferralSelectionsAreRejectedWithoutCreatingABatch() throws Exception {
        int batchesBefore = batchCount();

        // Unknown claim id.
        expectSimulateConflict(java.util.List.of(Map.of("claimId", "CLM-DOES-NOT-EXIST", "reason", "x")));
        // Cross-agreement pick: NA-NOFF claim while only NA-CNY is in scope.
        expectSimulateConflict(java.util.List.of(
                Map.of("claimId", "CLM-NF-001", "reason", "x")), "NA-CNY");
        // Missing reason.
        expectSimulateConflict(java.util.List.of(
                Map.of("claimId", "CLM-R1-001", "reason", "  ")), "NA-CNY");

        // All malformed requests aborted before persistence: no misleading batch.
        assertThat(batchCount()).isEqualTo(batchesBefore);
    }

    @Test
    @Order(4)
    void fullAcceptanceScenario() throws Exception {
        JsonNode sim = simulate(LocalDate.of(2026, 9, 30));

        // NA-CNY: 8 incoming (6 open rings + pledged + disputed); 6 included, 2 excluded;
        // the two rings (gross 2600) collapse to exactly 2 real legs worth 600.
        JsonNode cny = group(sim, "NA-CNY");
        assertThat(cny.get("passThrough").asBoolean()).isFalse();
        assertThat(cny.get("originalLegCount").asInt()).isEqualTo(6);
        assertThat(cny.get("exclusions").size()).isEqualTo(2);
        assertThat(reasons(cny)).containsExactlyInAnyOrder("PLEDGED", "DISPUTED");
        long cnyCashLegs = cashLegs(cny);
        assertThat(cnyCashLegs).isEqualTo(2);
        // Compare numerically: JsonNode.asText() normalizes trailing zeros (600.00 -> 600.0).
        assertThat(cny.get("netAmount").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(cny.get("grossAmount").decimalValue()).isEqualByComparingTo("2600.00");

        // Zero-amount memo legs trace all six cancelled ring invoices.
        java.util.Set<String> memoInvoices = new java.util.HashSet<>();
        for (JsonNode leg : cny.get("legs")) {
            if (leg.get("amount").asDouble() == 0) {
                for (JsonNode it : leg.get("items")) {
                    memoInvoices.add(it.get("invoiceNo").asText());
                }
            }
        }
        assertThat(memoInvoices).contains(
                "INV-A-1001", "INV-B-2001", "INV-C-3001",
                "INV-A-1002", "INV-B-2002", "INV-C-3002");

        // NA-NOFF: netting forbidden -> the closed ring survives as 3 original debts.
        JsonNode noff = group(sim, "NA-NOFF");
        assertThat(noff.get("passThrough").asBoolean()).isTrue();
        assertThat(noff.get("legs").size()).isEqualTo(3);
        for (JsonNode leg : noff.get("legs")) {
            assertThat(leg.get("isOriginal").asBoolean()).isTrue();
            assertThat(leg.get("amount").decimalValue()).isEqualByComparingTo("500.00");
        }

        // NA-XCCY: USD leg converted at the 08:30 snapshot (agreement fx_as_of 09:00),
        // +0.005 rounding diff recorded, EUR out-of-scope claim excluded.
        JsonNode xccy = group(sim, "NA-XCCY");
        assertThat(xccy.get("crossCurrency").asBoolean()).isTrue();
        assertThat(xccy.get("settlementCurrency").asText()).isEqualTo("CNY");
        assertThat(reasons(xccy)).contains("CCY_NOT_ALLOWED");
        // The USD claim is split across a real leg (receivable side at B) and the
        // set-off memo at E; inspect its debtor-side (PAYER) slice for full conversion.
        JsonNode usdItem = findItem(xccy, "CLM-XC-002", "PAYER");
        assertThat(usdItem.get("fxRate").asText()).startsWith("7.20535");
        assertThat(usdItem.get("fxAsOf").asText()).startsWith("2026-09-30T08:30:00");
        assertThat(usdItem.get("convertedExact").decimalValue()).isEqualByComparingTo("720.535000");
        assertThat(usdItem.get("convertedBooked").decimalValue()).isEqualByComparingTo("720.54");
        assertThat(usdItem.get("roundingDiff").decimalValue()).isEqualByComparingTo("0.005000");
        // Rounding does not move any entity off its cent balance: exactly 2 cash legs.
        assertThat(cashLegs(xccy)).isEqualTo(2);

        // Per-entity cent balance: signed cash flows equal the booked positions.
        assertLegsBalancePerEntity(xccy);
        assertLegsBalancePerEntity(cny);

        String batchId = sim.get("id").asText();
        assertThat(sim.get("status").asText()).isEqualTo("SIMULATED");

        // Simulation must not settle original claims.
        JsonNode claimsAfterSim = claims();
        assertThat(findClaim(claimsAfterSim, "CLM-R1-001").get("status").asText()).isEqualTo("OPEN");

        // Confirm -> claims get the batch reference; then simulate payment.
        JsonNode confirmed = postJson("/api/batches/" + batchId + "/confirm");
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
        JsonNode claimsAfterConfirm = claims();
        assertThat(findClaim(claimsAfterConfirm, "CLM-R1-001").get("offsetBatchId").asText())
                .isEqualTo(batchId);
        assertThat(findClaim(claimsAfterConfirm, "CLM-R1-001").get("status").asText())
                .isEqualTo("SETTLED");
        // Pass-through group claims stay open: set-off was never allowed.
        assertThat(findClaim(claimsAfterConfirm, "CLM-NF-001").get("status").asText())
                .isEqualTo("OPEN");

        JsonNode paid = postJson("/api/batches/" + batchId + "/pay-simulated");
        assertThat(paid.get("status").asText()).isEqualTo("PAID_SIMULATED");
        boolean atLeastOneSimulatedPayment = false;
        for (JsonNode g : paid.get("groups")) {
            for (JsonNode leg : g.get("legs")) {
                if (leg.get("amount").asDouble() > 0) {
                    assertThat(leg.get("paidSimulatedAt").asText()).isNotBlank();
                    atLeastOneSimulatedPayment = true;
                }
            }
        }
        assertThat(atLeastOneSimulatedPayment).isTrue();

        // Cannot confirm twice.
        mockMvc.perform(post("/api/batches/" + batchId + "/confirm"))
                .andExpect(status().isConflict());
    }

    private JsonNode simulate(LocalDate date) throws Exception {
        return simulate(date, Map.of(), java.util.List.of());
    }

    private JsonNode simulate(LocalDate date, Map<String, com.fasterxml.jackson.databind.JsonNode> extra,
                              java.util.List<Map<String, String>> deferred) throws Exception {
        var root = json.createObjectNode();
        root.put("valuationDate", date.toString());
        extra.forEach(root::set);
        ArrayNode arr = root.putArray("deferred");
        for (Map<String, String> d : deferred) {
            arr.addObject().put("claimId", d.get("claimId")).put("reason", d.get("reason"));
        }
        return json.readTree(mockMvc.perform(post("/api/batches/simulate")
                        .contentType("application/json;charset=UTF-8")
                        .content(json.writeValueAsString(root).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private void expectSimulateConflict(java.util.List<Map<String, String>> deferred,
                                        String... agreementCodes) throws Exception {
        var root = json.createObjectNode();
        root.put("valuationDate", "2026-09-30");
        if (agreementCodes.length > 0) {
            ArrayNode codes = root.putArray("agreementCodes");
            for (String code : agreementCodes) {
                codes.add(code);
            }
        }
        ArrayNode arr = root.putArray("deferred");
        for (Map<String, String> d : deferred) {
            arr.addObject().put("claimId", d.get("claimId")).put("reason", d.get("reason"));
        }
        mockMvc.perform(post("/api/batches/simulate")
                        .contentType("application/json;charset=UTF-8")
                        .content(json.writeValueAsString(root).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isConflict());
    }

    private JsonNode listBatchesJson() throws Exception {
        return json.readTree(mockMvc.perform(get("/api/batches"))
                .andReturn().getResponse().getContentAsString());
    }

    private int batchCount() throws Exception {
        return listBatchesJson().size();
    }

    private JsonNode postJson(String path) throws Exception {
        return json.readTree(mockMvc.perform(post(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode claims() throws Exception {
        return json.readTree(mockMvc.perform(get("/api/claims"))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode group(JsonNode batch, String agreementCode) {
        for (JsonNode g : batch.get("groups")) {
            if (g.get("agreementCode").asText().equals(agreementCode)) {
                return g;
            }
        }
        throw new AssertionError("group missing: " + agreementCode);
    }

    private long cashLegs(JsonNode g) {
        long n = 0;
        for (JsonNode leg : g.get("legs")) {
            if (leg.get("amount").asDouble() > 0) {
                n++;
            }
        }
        return n;
    }

    private java.util.List<String> reasons(JsonNode g) {
        java.util.List<String> out = new java.util.ArrayList<>();
        g.get("exclusions").forEach(e -> out.add(e.get("reasonCode").asText()));
        return out;
    }

    private JsonNode findItem(JsonNode g, String claimId, String side) {
        for (JsonNode leg : g.get("legs")) {
            for (JsonNode it : leg.get("items")) {
                if (it.get("claimId").asText().equals(claimId)
                        && (side == null || it.get("side").asText().equals(side))) {
                    return it;
                }
            }
        }
        throw new AssertionError("item missing: " + claimId + " side " + side);
    }

    private JsonNode findClaim(JsonNode arr, String id) {
        for (JsonNode c : arr) {
            if (c.get("id").asText().equals(id)) {
                return c;
            }
        }
        throw new AssertionError("claim missing: " + id);
    }

    private void assertLegsBalancePerEntity(JsonNode g) {
        java.util.Map<String, java.math.BigDecimal> flow = new java.util.HashMap<>();
        for (JsonNode leg : g.get("legs")) {
            if (leg.get("amount").asDouble() == 0) {
                continue;
            }
            java.math.BigDecimal amt = leg.get("amount").decimalValue();
            flow.merge(leg.get("payerCode").asText(), amt, java.math.BigDecimal::add);
            flow.merge(leg.get("receiverCode").asText(), amt.negate(), java.math.BigDecimal::add);
        }
        java.math.BigDecimal sum = flow.values().stream()
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("0.00");
    }
}
