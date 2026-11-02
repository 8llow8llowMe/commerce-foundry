package com.commercefoundry.domainlayer.product.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.product.application.command.ProductCreateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageStoragePort;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageUploadKey;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageFormat;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductImageAttachmentProcessorTest {
    private final ProductImageStoragePort storage = mock(ProductImageStoragePort.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private final ProductImageAttachmentProcessor processor = new ProductImageAttachmentProcessor(storage, ids);
    private final ProductImageUploadKey key = ProductImageUploadKey.issue(99, ProductImageFormat.PNG);

    private ProductCreateCommand command(long actor, ProductCreateCommand.Image... images) {
        return ProductCreateCommand.builder().createdByMemberId(actor).images(List.of(images)).build();
    }

    @Test
    void rejectsOtherAdminBeforeAnyStorageAccess() {
        assertThatThrownBy(() -> processor.attach(7, command(98, new ProductCreateCommand.Image(key.value(), ImageType.GALLERY, null))))
            .isInstanceOf(ProductException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsDuplicateKeysAndVideoAndTwoThumbnails() {
        var image = new ProductCreateCommand.Image(key.value(), ImageType.THUMBNAIL, null);
        assertThatThrownBy(() -> processor.attach(7, command(99, image, image))).isInstanceOf(ProductException.class);
        assertThatThrownBy(() -> processor.attach(7, command(99, new ProductCreateCommand.Image(key.value(), ImageType.VIDEO, null))))
            .isInstanceOf(ProductException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void compensatesAllAttemptedCopiesIncludingLostResponseAndPreservesOriginalError() {
        var second = ProductImageUploadKey.issue(99, ProductImageFormat.PNG);
        when(ids.generateId()).thenReturn(8L, 9L);
        when(storage.attach(key, "products/7/8.png")).thenReturn("https://media.example.com/products/7/8.png");
        var failure = new IllegalStateException("copy response lost");
        when(storage.attach(second, "products/7/9.png")).thenThrow(failure);
        doThrow(new IllegalStateException("cleanup failed")).when(storage).delete("products/7/8.png");
        assertThatThrownBy(() -> processor.attach(7, command(99,
            new ProductCreateCommand.Image(key.value(), ImageType.THUMBNAIL, null),
            new ProductCreateCommand.Image(second.value(), ImageType.GALLERY, "상세")))).isSameAs(failure);
        verify(storage).delete("products/7/8.png");
        verify(storage).delete("products/7/9.png");
        verify(storage, never()).delete(key.value());
    }

    @Test
    void buildsServerOwnedKeysAndArrayOrderAndAllowsNoImages() {
        assertThat(processor.attach(7, command(99))).isEmpty();
        verifyNoInteractions(storage);
        when(ids.generateId()).thenReturn(8L);
        when(storage.attach(key, "products/7/8.png")).thenReturn("https://media.example.com/products/7/8.png");
        var images = processor.attach(7, command(99, new ProductCreateCommand.Image(key.value(), ImageType.GALLERY, null)));
        assertThat(images.getFirst().storageKey()).isEqualTo("products/7/8.png");
        assertThat(images.getFirst().displayOrder()).isEqualTo(1);
    }
}
