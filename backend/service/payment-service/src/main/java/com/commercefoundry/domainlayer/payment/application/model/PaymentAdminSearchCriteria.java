package com.commercefoundry.domainlayer.payment.application.model;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;

public record PaymentAdminSearchCriteria(PaymentStatus status, Long orderId, Boolean orderSynced, int page, int size) {
}
