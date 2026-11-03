package com.commercefoundry.domainlayer.payment.application.port.in;

import com.commercefoundry.domainlayer.payment.application.command.PaymentWebhookCommand;

public interface PaymentWebhookUseCase { void receive(PaymentWebhookCommand command); }
