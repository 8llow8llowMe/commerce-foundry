package com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response;

public record PaymentCancelResponse(String paymentId, String claimId, String status, long canceledAmount) { }
