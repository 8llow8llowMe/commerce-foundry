package com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss.dto;

import java.math.BigDecimal;

/** Automatic cancellation freezes currency alongside amount and request identity. */
public record TossAutomaticCancelRequest(String cancelReason, BigDecimal cancelAmount, String cancelRequestId, String currency) { }
