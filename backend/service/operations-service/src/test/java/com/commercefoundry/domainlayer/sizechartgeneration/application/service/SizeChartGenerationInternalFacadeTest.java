package com.commercefoundry.domainlayer.sizechartgeneration.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

import com.commercefoundry.domainlayer.job.application.service.processor.JobContextReadProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRegistrationProcessor;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.presenter.SizeChartGenerationInternalPresenter;
import com.commercefoundry.domainlayer.sizechartgeneration.application.info.SizeChartGenerationResultInfo;
import com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor.SizeChartGenerationReceiptProcessor;
import com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor.SizeChartGenerationResultProcessor;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class SizeChartGenerationInternalFacadeTest {
    @Mock
    private JobRegistrationProcessor registration;
    @Mock
    private JobContextReadProcessor jobReader;
    @Mock
    private SizeChartGenerationReceiptProcessor receipts;
    @Mock
    private SizeChartGenerationResultProcessor results;
    @Mock
    private PlatformTransactionManager transactions;

    @Test
    void jobDomainReadAndFeatureResultReadRunInsideTheSameBoundedReadTransaction() {
        var job = Job.register(20, "SIZE_CHART_GENERATION", "{}", null, 30L, 0, Instant.EPOCH, 3);
        var transaction = new SimpleTransactionStatus();
        given(transactions.getTransaction(any())).willAnswer(invocation -> {
            TransactionDefinition definition = invocation.getArgument(0);
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.getTimeout()).isEqualTo(5);
            return transaction;
        });
        given(jobReader.findById(20)).willReturn(Optional.of(job));
        given(results.result(20, job)).willReturn(new SizeChartGenerationResultInfo(10, 20, "a".repeat(64), "PENDING", null, null));
        var facade = new SizeChartGenerationInternalFacade(registration, jobReader, receipts, results,
            new SizeChartGenerationInternalPresenter(), transactions, true);
        assertThat(facade.result(20).status()).isEqualTo("PENDING");
        var order = inOrder(transactions, jobReader, results);
        order.verify(transactions).getTransaction(any());
        order.verify(jobReader).findById(20);
        order.verify(results).result(20, job);
        order.verify(transactions).commit(transaction);
    }
}
