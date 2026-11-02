package com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.request;

import com.commercefoundry.shared.commerce.CurrencyCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 금액은 order-service 가 클레임 정책으로 계산한 최소 화폐 단위다. */
public record PaymentCancelRequest(@Positive long claimId, @Positive long amount, @NotNull CurrencyCode currency) { }
