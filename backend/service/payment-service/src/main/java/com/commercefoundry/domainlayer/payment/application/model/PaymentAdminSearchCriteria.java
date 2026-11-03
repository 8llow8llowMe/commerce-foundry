package com.commercefoundry.domainlayer.payment.application.model;

public record PaymentAdminSearchCriteria(PaymentAdminStatusFilter status, Long orderId, Boolean orderSynced, int page, int size) {
}
