package com.commercefoundry.domainlayer.product.application.service.processor;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.application.command.ProductHeaderUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductHeaderUpdateProcessorTest {

    @Mock ProductRepositoryPort products;
    @Mock ProductAdminDetailProcessor details;
    @InjectMocks ProductHeaderUpdateProcessor processor;

    private final ProductHeaderUpdateCommand command = new ProductHeaderUpdateCommand(
        1L, 7L, 99L, "새 이름", null, "<p>정제본</p>", SaleStatus.STOP_SELLING, false);

    @Test
    void updatesOnlyHeaderAndKeepsVersionAndChildren() {
        Product current = product(null);
        given(products.findAllByIdsIncludingHidden(List.of(1L))).willReturn(List.of(current));
        processor.update(command, "<p>정제본</p>");
        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(products).update(saved.capture());
        assertThat(saved.getValue()).isEqualTo(new Product(1L, 2L, "새 이름", null, "<p>정제본</p>",
            SaleStatus.STOP_SELLING, false, current.salePrice(), current.listPrice(), current.maxQuantityPerOrder(),
            current.customOrder(), current.customOrderNotice(), current.customOrderLeadTimeDays(), current.preorder(),
            current.expectedShipDate(), 7L, null, current.createdByMemberId()));
        verify(details).getProduct(1L);
    }

    @Test
    void englishHeaderIsSavedWithKoreanHeaderInSameVersionedUpdate() {
        Product current = product(null);
        given(products.findAllByIdsIncludingHidden(List.of(1L))).willReturn(List.of(current));
        var translated = new ProductHeaderUpdateCommand(1L, 7L, 99L, "새 이름", null, "<p>정제본</p>",
            SaleStatus.STOP_SELLING, false, "New name", null, "<p>English</p>");

        processor.update(translated, "<p>정제본</p>", "<p>English</p>");

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(products).update(saved.capture());
        assertThat(saved.getValue().nameEn()).isEqualTo("New name");
        assertThat(saved.getValue().summaryEn()).isNull();
        assertThat(saved.getValue().descriptionHtmlEn()).isEqualTo("<p>English</p>");
        assertThat(saved.getValue().version()).isEqualTo(7L);
    }

    @Test
    void legacyHeaderUpdateKeepsEnglishAndEmptyInputClearsEnglish() {
        Product current = new Product(1L, 2L, "이전 이름", "요약", "<p>이전</p>", SaleStatus.ON_SALE, true,
            Money.of(10000, KRW), null, 5, false, null, null, false, null, 7L, null, 99L,
            "Old English", "Old summary", "<p>Old</p>");
        given(products.findAllByIdsIncludingHidden(List.of(1L))).willReturn(List.of(current));
        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);

        processor.update(command, "<p>정제본</p>", null);
        verify(products).update(saved.capture());
        assertThat(saved.getValue().nameEn()).isEqualTo("Old English");
        assertThat(saved.getValue().descriptionHtmlEn()).isEqualTo("<p>Old</p>");
    }

    @Test
    void staleVersionCannotWrite() {
        given(products.findAllByIdsIncludingHidden(List.of(1L))).willReturn(List.of(product(null)));
        var stale = new ProductHeaderUpdateCommand(1L, 6L, 99L, "새 이름", null, null, SaleStatus.ON_SALE, true);
        assertThatThrownBy(() -> processor.update(stale, null)).isInstanceOf(ProductException.class)
            .satisfies(error -> assertThat(((ProductException) error).getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT));
        verifyNoInteractions(details);
    }

    @Test
    void deletedProductCannotWrite() {
        given(products.findAllByIdsIncludingHidden(List.of(1L))).willReturn(List.of(product(Instant.parse("2026-11-02T00:00:00Z"))));
        assertThatThrownBy(() -> processor.update(command, null)).isInstanceOf(ProductException.class)
            .satisfies(error -> assertThat(((ProductException) error).getErrorCode()).isEqualTo(ProductErrorCode.NOT_FOUND_PRODUCT));
        verifyNoInteractions(details);
    }

    private static Product product(Instant deletedAt) {
        return new Product(1L, 2L, "이전 이름", "요약", "<p>이전</p>", SaleStatus.ON_SALE, true,
            Money.of(10000, KRW), null, 5, false, null, null, false, null, 7L, deletedAt, 99L);
    }
}
