package com.commercefoundry.domainlayer.product.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.ProductAdminPresenter;
import com.commercefoundry.domainlayer.product.application.command.ProductImagesUpdateCommand;
import com.commercefoundry.domainlayer.product.application.service.processor.*;
import com.commercefoundry.domainlayer.product.domain.model.*;
import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.category.application.service.processor.CategoryProcessor;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.*;

/** Completion certainty, rather than exception type, determines object compensation. */
class ProductImagesTransactionTest {
    @ParameterizedTest
    @ValueSource(strings = {"rollback", "begin", "unknown", "presenter", "preflight", "copy"})
    void compensatesOnlyConfirmedRollbackAndAuditsPreflightFailure(String mode) {
        var attachment = mock(ProductImageAttachmentProcessor.class);
        var prepare = mock(ProductImagesPrepareProcessor.class);
        var update = mock(ProductImagesUpdateProcessor.class);
        var audit = mock(ProductAdminAuditProcessor.class);
        var presenter = mock(ProductAdminPresenter.class);
        var failure = new TransactionSystemException("injected failure");
        var manager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
                assertThat(definition.getTimeout()).isEqualTo(5);
                if (mode.equals("begin")) throw failure;
            }
            @Override protected void doCommit(DefaultTransactionStatus status) { if (mode.equals("unknown")) throw failure; }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
        var command = new ProductImagesUpdateCommand(7, 0, 99, List.of(new ProductImagesUpdateCommand.Image(null, "upload", ImageType.THUMBNAIL, null)));
        var copied = new ProductImage(8, 7, ImageType.THUMBNAIL, "https://media.example.com/new", null, null, null, 0, null, "products/7/8.png");
        when(attachment.attach(eq(7L), eq(99L), anyList())).thenReturn(List.of(copied));
        if (mode.equals("preflight")) doThrow(failure).when(prepare).prepare(command);
        if (mode.equals("copy")) when(attachment.attach(eq(7L), eq(99L), anyList())).thenThrow(failure);
        if (mode.equals("rollback")) when(update.update(eq(command), anyList())).thenThrow(failure);
        if (mode.equals("presenter")) when(presenter.toDetailResponse(any())).thenThrow(failure);
        var facade = new ProductAdminFacade(mock(SnowflakeIdGenerator.class), mock(ProductRegistrationProcessor.class), attachment, manager,
            mock(CategoryProcessor.class), mock(ProductAdminQueryProcessor.class), presenter, mock(ProductAdminDetailProcessor.class),
            mock(ProductWriteProcessor.class), mock(ProductDescriptionSanitizer.class), mock(ProductImageUploadProcessor.class),
            mock(ProductDeleteProcessor.class), mock(ProductHeaderUpdateProcessor.class), mock(ProductCategoryUpdateProcessor.class),
            audit, mock(ProductPricingUpdateProcessor.class), prepare, update, mock(ProductOptionsUpdateProcessor.class));
        doAnswer(c -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            return null;
        }).when(attachment).cleanup(anyList());
        assertThatThrownBy(() -> facade.updateImages(command)).isSameAs(failure);
        verify(attachment, times(mode.equals("rollback") ? 1 : 0)).cleanup(List.of("products/7/8.png"));
        if (List.of("preflight", "copy", "begin").contains(mode)) verify(audit).productImagesReplaced(99, 7, false);
        else verify(audit).productImagesReplaced(99, 7, true);
        if (mode.equals("preflight")) verify(attachment, never()).attach(anyLong(), anyLong(), anyList());
        if (!mode.equals("presenter")) verifyNoInteractions(presenter);
    }
}
