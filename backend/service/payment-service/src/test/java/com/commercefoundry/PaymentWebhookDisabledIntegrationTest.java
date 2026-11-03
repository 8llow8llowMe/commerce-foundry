package com.commercefoundry;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.payment.application.port.in.PaymentWebhookSchedulerUseCase;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentWebhookInboxPort;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"payment.webhook.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:payment-webhook-disabled;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS payment"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentWebhookDisabledIntegrationTest {
    @Autowired MockMvcTester mvc;
    @Autowired PaymentWebhookSchedulerUseCase worker;
    @Autowired PaymentWebhookInboxPort inbox;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean PaymentGatewayPort gateway;
    @Test void defaultOffReturns503AndWorkerRetainsQueuedRows() {
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> inbox.insert(PaymentWebhookInbox.received(150001, PAYMENT_ID, "a".repeat(64), Instant.now())));
        var result = mvc.post().uri("/api/v1/payments/webhooks/toss").contentType(MediaType.APPLICATION_JSON).content("""
            {"eventType":"PAYMENT_STATUS_CHANGED","createdAt":"2026-10-21T10:01:00","data":{"orderId":"9000000001","paymentKey":"dummy"}}
            """).exchange();
        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PAYMENT_WEBHOOK_001");
        worker.process();
        var retained = tx.execute(status -> inbox.findById(150001).orElseThrow().state());
        assertThat(retained).isEqualTo(PaymentWebhookInbox.State.PENDING);
        verifyNoInteractions(gateway);
    }
}
