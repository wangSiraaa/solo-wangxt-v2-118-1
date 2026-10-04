package com.treasury.clearing.service.plan;

import java.math.BigDecimal;

public record PlannedExclusion(String claimId,
                               String invoiceNo,
                               BigDecimal amount,
                               String currency,
                               String reasonCode,
                               String reasonDetail) {

    public PlannedExclusion(String claimId, String invoiceNo, String reasonCode, String reasonDetail) {
        this(claimId, invoiceNo, null, null, reasonCode, reasonDetail);
    }
}
