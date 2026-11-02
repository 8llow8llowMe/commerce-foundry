package com.commercefoundry.domainlayer.product.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.category.domain.model.CategoryTree;
import com.commercefoundry.domainlayer.product.application.command.ProductCreateCommand;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductRegistrationProcessorTest {
    @Test
    void validatesUsingLockedDomainSnapshotThenStoresProductAndImagesBeforeReadingDetail() {
        var writes = mock(ProductWriteProcessor.class);
        var details = mock(ProductAdminDetailProcessor.class);
        var images = mock(ProductImageRepositoryPort.class);
        var processor = new ProductRegistrationProcessor(writes, details, images);
        var tree = CategoryTree.of(List.of());
        var command = ProductCreateCommand.builder().build();
        assertThat(processor.register(7, command, null, List.of(12L), List.of(), tree)).isNull();
        var order = inOrder(writes, images, details);
        order.verify(writes).requireCategories(tree, List.of(12L));
        order.verify(writes).create(command, null, List.of(12L), 7);
        order.verify(images).insertAll(List.of());
        order.verify(details).getProduct(7);
    }
}
