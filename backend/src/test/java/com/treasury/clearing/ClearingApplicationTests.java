package com.treasury.clearing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.clearing.domain.Claim;
import com.treasury.clearing.domain.ClaimStatus;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.repo.ClaimRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

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
class ClearingApplicationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private NettingAgreementRepository agreementRepository;
    @Autowired
    private ClaimRepository claimRepository;
    private final ObjectMapper json = new ObjectMapper();

    @Test
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

    /**
     * Per-batch deferral: treasury holds back one open invoice whose paperwork is not
     * finished. The batch lists it as a DEFERRED exclusion with the reason; amounts,
     * payment legs and invoice tracing are computed from the remaining claims only.
     * The claim row is never modified, so the next run without the selection includes
     * it again. Uses its own agreement so it is independent of the seeded scenario
     * regardless of test order.
     */
    @Test
    void deferralScenario() throws Exception {
        seedDeferralFixture();

        // 1) Defer one open invoice of the test ring, with a reason.
        JsonNode sim = simulate(LocalDate.of(2026, 9, 30), List.of("NA-TST"),
                List.<String[]>of(new String[]{"CLM-TST-001", "凭证未办妥，本次暂缓"}));
        JsonNode g = group(sim, "NA-TST");
        assertThat(g.get("originalLegCount").asInt()).isEqualTo(2);
        assertThat(g.get("grossAmount").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(g.get("netAmount").decimalValue()).isEqualByComparingTo("600.00");
        // Explicit exclusion record carrying the deferral reason.
        assertThat(g.get("exclusions").size()).isEqualTo(1);
        JsonNode ex = g.get("exclusions").get(0);
        assertThat(ex.get("claimId").asText()).isEqualTo("CLM-TST-001");
        assertThat(ex.get("invoiceNo").asText()).isEqualTo("INV-TST-001");
        assertThat(ex.get("reasonCode").asText()).isEqualTo("DEFERRED");
        assertThat(ex.get("reasonDetail").asText()).isEqualTo("凭证未办妥，本次暂缓");
        // Legs recompute from the remaining claims: B pays A 400 and C 200.
        assertThat(cashLegs(g)).isEqualTo(2);
        assertThat(cashAmount(g, "B", "A")).isEqualByComparingTo("400.00");
        assertThat(cashAmount(g, "B", "C")).isEqualByComparingTo("200.00");
        // The deferred invoice is traced by no leg at all.
        assertThat(legInvoices(g)).doesNotContain("INV-TST-001");

        // The claim row itself is untouched by the deferral.
        JsonNode claimsAfterSim = claims();
        assertThat(findClaim(claimsAfterSim, "CLM-TST-001").get("status").asText()).isEqualTo("OPEN");
        assertThat(findClaim(claimsAfterSim, "CLM-TST-001").get("offsetBatchId").asText()).isEmpty();

        // 2) A fresh simulation without the selection lets the invoice re-enter the pool.
        JsonNode sim2 = simulate(LocalDate.of(2026, 9, 30), List.of("NA-TST"), List.of());
        JsonNode g2 = group(sim2, "NA-TST");
        assertThat(g2.get("exclusions").size()).isEqualTo(0);
        assertThat(g2.get("originalLegCount").asInt()).isEqualTo(3);
        assertThat(g2.get("grossAmount").decimalValue()).isEqualByComparingTo("2000.00");
        assertThat(g2.get("netAmount").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(legInvoices(g2)).contains("INV-TST-001");

        // 3) Confirming the deferral batch settles only the claims it actually netted;
        //    the deferred claim stays open and carries no batch reference.
        postJson("/api/batches/" + sim.get("id").asText() + "/confirm");
        JsonNode claimsAfterConfirm = claims();
        assertThat(findClaim(claimsAfterConfirm, "CLM-TST-001").get("status").asText()).isEqualTo("OPEN");
        assertThat(findClaim(claimsAfterConfirm, "CLM-TST-001").get("offsetBatchId").asText()).isEmpty();
        assertThat(findClaim(claimsAfterConfirm, "CLM-TST-002").get("status").asText()).isEqualTo("SETTLED");

        // 4) Unknown claim ids, cross-agreement selections and blank reasons are
        //    rejected with 409 and leave no misleading batch behind.
        int batchesBefore = batchCount();
        simulateExpectConflict(List.of("NA-TST"), List.<String[]>of(new String[]{"NO-SUCH-CLAIM", "x"}));
        simulateExpectConflict(List.of("NA-TST"), List.<String[]>of(new String[]{"CLM-R1-001", "cross-agreement"}));
        simulateExpectConflict(List.of("NA-TST"), List.<String[]>of(new String[]{"CLM-TST-001", "  "}));
        assertThat(batchCount()).isEqualTo(batchesBefore);
    }

    private void seedDeferralFixture() {
        NettingAgreement agr = new NettingAgreement("NA-TST", "暂缓功能测试协议", true, false,
                "CNY", "A", LocalDate.of(2026, 1, 1), null, null, null);
        agr.addMember("A");
        agr.addMember("B");
        agr.addMember("C");
        agr.addCurrency("CNY");
        agreementRepository.save(agr);
        claimRepository.save(new Claim("CLM-TST-001", "INV-TST-001", "NA-TST", "A", "B",
                new BigDecimal("1000.00"), "CNY", ClaimStatus.OPEN,
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 10), "暂缓测试：甲欠乙"));
        claimRepository.save(new Claim("CLM-TST-002", "INV-TST-002", "NA-TST", "B", "C",
                new BigDecimal("600.00"), "CNY", ClaimStatus.OPEN,
                LocalDate.of(2026, 9, 12), LocalDate.of(2026, 10, 12), "暂缓测试：乙欠丙"));
        claimRepository.save(new Claim("CLM-TST-003", "INV-TST-003", "NA-TST", "C", "A",
                new BigDecimal("400.00"), "CNY", ClaimStatus.OPEN,
                LocalDate.of(2026, 9, 15), LocalDate.of(2026, 10, 15), "暂缓测试：丙欠甲"));
    }

    private JsonNode simulate(LocalDate date, List<String> agreementCodes,
                              List<String[]> deferrals) throws Exception {
        var req = json.createObjectNode().put("valuationDate", date.toString());
        var codes = req.putArray("agreementCodes");
        agreementCodes.forEach(codes::add);
        var defs = req.putArray("deferrals");
        for (String[] d : deferrals) {
            defs.addObject().put("claimId", d[0]).put("reason", d[1]);
        }
        return json.readTree(mockMvc.perform(post("/api/batches/simulate")
                        .contentType("application/json")
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void simulateExpectConflict(List<String> agreementCodes, List<String[]> deferrals)
            throws Exception {
        var req = json.createObjectNode().put("valuationDate", "2026-09-30");
        var codes = req.putArray("agreementCodes");
        agreementCodes.forEach(codes::add);
        var defs = req.putArray("deferrals");
        for (String[] d : deferrals) {
            defs.addObject().put("claimId", d[0]).put("reason", d[1]);
        }
        mockMvc.perform(post("/api/batches/simulate")
                        .contentType("application/json")
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    private int batchCount() throws Exception {
        return json.readTree(mockMvc.perform(get("/api/batches"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).size();
    }

    private java.math.BigDecimal cashAmount(JsonNode g, String payer, String receiver) {
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        for (JsonNode leg : g.get("legs")) {
            if (leg.get("amount").asDouble() > 0
                    && leg.get("payerCode").asText().equals(payer)
                    && leg.get("receiverCode").asText().equals(receiver)) {
                sum = sum.add(leg.get("amount").decimalValue());
            }
        }
        return sum;
    }

    private java.util.Set<String> legInvoices(JsonNode g) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (JsonNode leg : g.get("legs")) {
            for (JsonNode it : leg.get("items")) {
                out.add(it.get("invoiceNo").asText());
            }
        }
        return out;
    }

    private JsonNode simulate(LocalDate date) throws Exception {
        var req = json.createObjectNode().put("valuationDate", date.toString());
        return json.readTree(mockMvc.perform(post("/api/batches/simulate")
                        .contentType("application/json")
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postJson(String path) throws Exception {
        return json.readTree(mockMvc.perform(post(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode claims() throws Exception {
        return json.readTree(mockMvc.perform(get("/api/claims"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
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
