package com.commercefoundry.domainlayer.payment.application.command;

import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminStatusFilter;

public record PaymentAdminSearchCommand(PaymentAdminStatusFilter status, Long orderId, Boolean orderSynced, int page, int size) {
}
