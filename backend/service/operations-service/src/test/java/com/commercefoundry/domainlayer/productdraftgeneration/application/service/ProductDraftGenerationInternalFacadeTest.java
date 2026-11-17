package com.commercefoundry.domainlayer.productdraftgeneration.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.job.application.service.processor.JobContextReadProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRegistrationProcessor;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.presenter.ProductDraftGenerationInternalPresenter;
import com.commercefoundry.domainlayer.productdraftgeneration.application.command.ProductDraftGenerationRegisterCommand;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationErrorCode;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationException;
import com.commercefoundry.domainlayer.productdraftgeneration.application.info.ProductDraftGenerationRegistrationInfo;
import com.commercefoundry.domainlayer.productdraftgeneration.application.info.ProductDraftGenerationResultInfo;
import com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor.ProductDraftGenerationJobProcessor;
import com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor.ProductDraftGenerationReceiptProcessor;
import com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor.ProductDraftGenerationResultProcessor;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class ProductDraftGenerationInternalFacadeTest {
    private static final String HASH = "a".repeat(64);
    private static final ProductDraftGenerationRegisterCommand COMMAND =
        new ProductDraftGenerationRegisterCommand(10, 1, HASH, 30, Instant.parse("2099-01-01T00:00:00Z"));
    @Mock
    private JobRegistrationProcessor registration;
    @Mock
    private JobContextReadProcessor jobReader;
    @Mock
    private ProductDraftGenerationReceiptProcessor receipts;
    @Mock
    private ProductDraftGenerationResultProcessor results;
    @Mock
    private PlatformTransactionManager transactions;

    private ProductDraftGenerationInternalFacade facade(boolean enabled) {
        return new ProductDraftGenerationInternalFacade(registration, jobReader, receipts, results,
            new ProductDraftGenerationInternalPresenter(), transactions, enabled);
    }

    @Test
    void jobDomainReadAndFeatureResultReadRunInsideTheSameBoundedReadTransaction() {
        var job = Job.register(20, ProductDraftGenerationJobProcessor.TYPE, "{\"draftId\":10}", null, 30L, 0, Instant.EPOCH, 2);
        var transaction = new SimpleTransactionStatus();
        given(transactions.getTransaction(any())).willAnswer(invocation -> {
            TransactionDefinition definition = invocation.getArgument(0);
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.getTimeout()).isEqualTo(5);
            return transaction;
        });
        given(jobReader.findById(20)).willReturn(Optional.of(job));
        given(results.result(20, job)).willReturn(new ProductDraftGenerationResultInfo(10, 1, 20, HASH, "PENDING", null, null));
        var response = facade(true).result(20);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.generationNo()).isEqualTo(1);
        assertThat(response.jobId()).isEqualTo("20");
        var order = inOrder(transactions, jobReader, results);
        order.verify(transactions).getTransaction(any());
        order.verify(jobReader).findById(20);
        order.verify(results).result(20, job);
        order.verify(transactions).commit(transaction);
    }

    @Test
    void disabledFlagStillReplaysAnExistingReceipt() {
        given(transactions.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        given(receipts.replay(COMMAND)).willReturn(Optional.of(new ProductDraftGenerationRegistrationInfo(10, 1, 20, HASH)));
        var response = facade(false).register(COMMAND);
        assertThat(response.jobId()).isEqualTo("20");
        assertThat(response.draftId()).isEqualTo("10");
        verifyNoInteractions(registration);
    }

    @Test
    void disabledFlagBlocksOnlyNewRegistration() {
        given(transactions.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        given(receipts.replay(COMMAND)).willReturn(Optional.empty());
        assertThatThrownBy(() -> facade(false).register(COMMAND)).isInstanceOfSatisfying(ProductDraftGenerationException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(ProductDraftGenerationErrorCode.UNAVAILABLE));
        verifyNoInteractions(registration);
    }

    @Test
    void resultReadFailureBecomesFixed503() {
        given(transactions.getTransaction(any())).willThrow(new QueryTimeoutException("db detail"));
        assertThatThrownBy(() -> facade(true).result(20)).isInstanceOfSatisfying(ProductDraftGenerationException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ProductDraftGenerationErrorCode.RESULT_UNAVAILABLE);
            assertThat(exception.getMessage()).doesNotContain("db detail");
        });
    }
}
