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
import com.commercefoundry.domainlayer.product.domain.model.ProductImageManagedSource;
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

    private static final String MEDIA = "products/media/7/0f8fad5b-d9cb-469f-a165-70867728950e/";

    private static ProductImageManagedSource managed(char hash, ProductImageFormat format) {
        return new ProductImageManagedSource(MEDIA + String.valueOf(hash).repeat(64) + "." + format.getExtension(), "\"etag\"", 12, format);
    }

    @Test
    void managedSourceSkipsOwnershipAndCopiesWithItsFormat() {
        when(ids.generateId()).thenReturn(8L, 9L);
        var thumbnail = managed('a', ProductImageFormat.WEBP);
        var gallery = managed('b', ProductImageFormat.JPEG);
        when(storage.attachManaged(thumbnail, "products/7/8.webp")).thenReturn("https://media.example.com/products/7/8.webp");
        when(storage.attachManaged(gallery, "products/7/9.jpg")).thenReturn("https://media.example.com/products/7/9.jpg");

        // 초안을 만든 어드민과 적용하는 어드민이 달라도 붙는다 — 관리 원본은 키 소유를 보지 않는다.
        var images = processor.attach(7, command(55,
            ProductCreateCommand.Image.managed(thumbnail, ImageType.THUMBNAIL, "대표"),
            ProductCreateCommand.Image.managed(gallery, ImageType.GALLERY, null)));

        assertThat(images).extracting(i -> i.storageKey()).containsExactly("products/7/8.webp", "products/7/9.jpg");
        assertThat(images).extracting(i -> i.displayOrder()).containsExactly(0, 2);
        verify(storage, never()).attach(any(), any());
    }

    @Test
    void managedSourceRejectsDuplicatesBeforeStorageAndCompensatesOnFailure() {
        var source = managed('a', ProductImageFormat.PNG);
        assertThatThrownBy(() -> processor.attach(7, command(55,
            ProductCreateCommand.Image.managed(source, ImageType.GALLERY, null), ProductCreateCommand.Image.managed(source, ImageType.GALLERY, null))))
            .isInstanceOf(ProductException.class);
        verifyNoInteractions(storage);

        when(ids.generateId()).thenReturn(8L, 9L);
        var second = managed('b', ProductImageFormat.PNG);
        when(storage.attachManaged(source, "products/7/8.png")).thenReturn("https://media.example.com/products/7/8.png");
        var changed = new ProductException(com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode.IMAGE_UPLOAD_NOT_FOUND);
        when(storage.attachManaged(second, "products/7/9.png")).thenThrow(changed);
        assertThatThrownBy(() -> processor.attach(7, command(55,
            ProductCreateCommand.Image.managed(source, ImageType.GALLERY, null), ProductCreateCommand.Image.managed(second, ImageType.GALLERY, null))))
            .isSameAs(changed);
        verify(storage).delete("products/7/8.png");
        verify(storage).delete("products/7/9.png");
    }

    @Test
    void imageCannotCarryBothSources() {
        assertThatThrownBy(() -> new ProductCreateCommand.Image(key.value(), managed('a', ProductImageFormat.PNG), ImageType.GALLERY, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
