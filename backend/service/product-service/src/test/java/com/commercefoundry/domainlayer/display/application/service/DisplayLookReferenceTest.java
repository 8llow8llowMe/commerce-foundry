package com.commercefoundry.domainlayer.display.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.request.DisplayItemUpsertRequest;
import com.commercefoundry.domainlayer.display.adapter.in.admin.presenter.DisplayPreviewAdminPresenter;
import com.commercefoundry.domainlayer.display.adapter.in.web.presenter.DisplayWebPresenter;
import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import com.commercefoundry.domainlayer.display.application.exception.DisplayErrorCode;
import com.commercefoundry.domainlayer.display.application.exception.DisplayException;
import com.commercefoundry.domainlayer.display.application.port.out.DisplaySectionRepositoryPort;
import com.commercefoundry.domainlayer.display.application.service.processor.DisplaySectionProcessor;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItem;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySection;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionContent;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import com.commercefoundry.domainlayer.look.application.port.out.LookRepositoryPort;
import com.commercefoundry.domainlayer.look.application.service.processor.LookProcessor;
import com.commercefoundry.domainlayer.look.application.service.processor.LookWebProcessor;
import com.commercefoundry.domainlayer.look.domain.model.Look;
import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import com.commercefoundry.domainlayer.look.domain.model.LookItem;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductListProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductLookupProcessor;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import jakarta.validation.Validation;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** 룩 관리만 바꾸면 메인도 바뀌며, 노출 판정과 상품 벌크 조회는 기존 고객 규칙을 따른다. */
class DisplayLookReferenceTest {

    private final DisplaySectionRepositoryPort displayPort = mock(DisplaySectionRepositoryPort.class);
    private final LookRepositoryPort lookPort = mock(LookRepositoryPort.class);
    private final ProductRepositoryPort productPort = mock(ProductRepositoryPort.class);
    private final SkuRepositoryPort skuPort = mock(SkuRepositoryPort.class);
    private final ProductImageRepositoryPort imagePort = mock(ProductImageRepositoryPort.class);
    private final DisplaySectionProcessor display = new DisplaySectionProcessor(displayPort, new SnowflakeIdGenerator(1, 1), MediaUrlPolicy.of(""), Clock.systemUTC());
    private final StoreProperties enabled = new StoreProperties(new StoreProperties.Features(false, false, false, true));
    private final ProductListProcessor products = new ProductListProcessor(productPort, skuPort, imagePort, enabled);
    private final LookWebProcessor looks = new LookWebProcessor(lookPort, enabled);

    @Test
    void mainReadsCurrentLookAndProductsOnceForAllSections() {
        given(displayPort.findAll()).willReturn(List.of(section(1, DisplaySectionType.LOOK), section(2, DisplaySectionType.PRODUCT_PICK)));
        given(displayPort.findItemsBySectionIds(anyCollection())).willReturn(List.of(
            new DisplayItem(11, 1, 0, DisplayItemType.LOOK, null, null, 10L, null),
            new DisplayItem(12, 1, 1, DisplayItemType.LOOK, null, null, 20L, null),
            new DisplayItem(21, 2, 0, DisplayItemType.PRODUCT, null, 700L, null)
        ));
        given(lookPort.findAll()).willReturn(List.of(look(10, true, "원본.jpg"), look(20, false, "숨김.jpg")));
        given(lookPort.findItemsByLookIds(List.of(10L))).willReturn(List.of(new LookItem(101, 10, 800, 0), new LookItem(102, 10, 900, 1)));
        given(productPort.findVisibleListRowsByIds(anyCollection())).willReturn(List.of(row(700), row(800)));

        var main = facade(looks).getMain();

        var items = main.sections().getFirst().items();
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().look().lookId()).isEqualTo("10");
        assertThat(items.getFirst().look().image().url()).endsWith("원본.jpg");
        assertThat(items.getFirst().look().products()).extracting(card -> card.productId()).containsExactly("800");
        verify(lookPort).findAll();
        verify(lookPort).findItemsByLookIds(List.of(10L));
        verify(productPort).findVisibleListRowsByIds(Set.of(700L, 800L, 900L));
        verify(skuPort).aggregateByProductIds(List.of(700L, 800L));
        verify(imagePort).findThumbnailsByProductIds(List.of(700L, 800L));
        verify(imagePort).findStillImagesByProductIds(List.of(700L, 800L));

        given(lookPort.findAll()).willReturn(List.of(look(10, true, "교체.jpg")));
        assertThat(facade(looks).getMain().sections().getFirst().items().getFirst().look().image().url()).endsWith("교체.jpg");
    }

    @Test
    void featureOffAndDeletedLooksAreAbsentWithoutLosingSection() {
        given(displayPort.findAll()).willReturn(List.of(section(1, DisplaySectionType.LOOK)));
        given(displayPort.findItemsBySectionIds(anyCollection())).willReturn(List.of(new DisplayItem(11, 1, 0, DisplayItemType.LOOK, null, null, 10L, null)));
        var off = new LookWebProcessor(lookPort, new StoreProperties(null));
        assertThat(facade(off).getMain().sections().getFirst().items()).isEmpty();
        verifyNoInteractions(lookPort, productPort, skuPort, imagePort);

        given(lookPort.findAll()).willReturn(List.of(look(10, true, "화보.jpg")));
        given(lookPort.findItemsByLookIds(List.of(10L))).willReturn(List.of());
        assertThat(facade(looks).getMain().sections().getFirst().items()).isEmpty();
    }

    @Test
    void legacyLookRemainsReadableByAdminButDoesNotLeakItsOldProducts() {
        var legacy = new DisplaySectionContent(section(1, DisplaySectionType.LOOK), List.of(new DisplayItem(11, 1, 0, DisplayItemType.PRODUCT, null, 700L, null)));
        given(displayPort.findAll()).willReturn(List.of(legacy.section()));
        given(displayPort.findItemsBySectionIds(anyCollection())).willReturn(legacy.items());

        assertThat(facade(looks).getMain().sections()).isEmpty();
        assertThat(display.toAdminInfo(legacy, java.util.Map.of()).items().getFirst().product().productId()).isEqualTo(700);
        verifyNoInteractions(lookPort, productPort, skuPort, imagePort);
        assertError(() -> display.draftOf(command(List.of(DisplaySectionUpsertCommand.Item.builder().itemType(DisplayItemType.PRODUCT).productId(700L).build()))),
            DisplayErrorCode.DISPLAY_ITEM_TYPE_NOT_ALLOWED);
    }

    @Test
    void lookReferencesHaveBoundsDuplicateAndExistenceChecks() {
        assertError(() -> display.draftOf(command(List.of())), DisplayErrorCode.DISPLAY_ITEM_COUNT_OUT_OF_RANGE);
        assertThat(display.draftOf(command(refs(1))).lookIds()).containsExactly(1L);
        assertThat(display.draftOf(command(refs(10))).lookIds()).hasSize(10);
        assertError(() -> display.draftOf(command(refs(11))), DisplayErrorCode.DISPLAY_ITEM_COUNT_OUT_OF_RANGE);
        assertError(() -> display.draftOf(command(List.of(ref(10), ref(10)))), DisplayErrorCode.DISPLAY_LOOK_DUPLICATED);
        assertError(() -> display.validateLooksExist(Set.of(10L), Set.of()), DisplayErrorCode.DISPLAY_LOOK_NOT_FOUND);
        assertError(() -> display.draftOf(DisplaySectionUpsertCommand.builder().sectionType(DisplaySectionType.STORY).items(List.of(ref(10))).build()),
            DisplayErrorCode.DISPLAY_ITEM_TYPE_NOT_ALLOWED);
    }

    @Test
    void previewChecksHiddenDraftReferencesAndThenUsesCustomerFlag() {
        var adminLooks = new LookProcessor(lookPort, new SnowflakeIdGenerator(1, 1), MediaUrlPolicy.of(""));
        var off = new LookWebProcessor(lookPort, new StoreProperties(null));
        var preview = new DisplayPreviewAdminFacade(display, new ProductLookupProcessor(productPort), products, new DisplayPreviewAdminPresenter(), adminLooks, off);
        given(lookPort.findAll()).willReturn(List.of(look(10, false, "화보.jpg")));
        assertThat(preview.preview(List.of(command(List.of(ref(10))))).sections().getFirst().items()).isEmpty();
        assertError(() -> preview.preview(List.of(command(List.of(ref(99))))), DisplayErrorCode.DISPLAY_LOOK_NOT_FOUND);
        verifyNoInteractions(displayPort);
    }

    @Test
    void requestRequiresPositiveLookIdAndExclusivePayload() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(DisplayItemUpsertRequest.builder().itemType(DisplayItemType.LOOK).lookId(10L).build())).isEmpty();
            assertThat(validator.validate(DisplayItemUpsertRequest.builder().itemType(DisplayItemType.LOOK).build()))
                .extracting(v -> v.getMessage()).anyMatch(message -> message.startsWith("DISPLAY_102:"));
            assertThat(validator.validate(DisplayItemUpsertRequest.builder().itemType(DisplayItemType.LOOK).lookId(0L).build()))
                .extracting(v -> v.getMessage()).anyMatch(message -> message.startsWith("DISPLAY_101:"));
            assertThat(validator.validate(DisplayItemUpsertRequest.builder().itemType(DisplayItemType.LOOK).lookId(10L).productId(700L).build())).isNotEmpty();
            assertThat(validator.validate(DisplayItemUpsertRequest.builder().itemType(DisplayItemType.PRODUCT).lookId(10L).productId(700L).build())).isNotEmpty();
        }
    }

    private DisplayWebFacade facade(LookWebProcessor processor) {
        return new DisplayWebFacade(display, products, new DisplayWebPresenter(), processor);
    }

    private static DisplaySection section(long id, DisplaySectionType type) {
        return new DisplaySection(id, type, "섹션", null, null, null, null, 0, true, null, null);
    }

    private static Look look(long id, boolean visible, String image) {
        return new Look(id, "룩", "설명", new LookImage("https://cdn.example.com/" + image, 1200, 1600, "화보"), visible, 0);
    }

    private static ProductListQueryResult row(long id) {
        return new ProductListQueryResult(id, "상품", 39000, CurrencyCode.KRW, null, null, SaleStatus.ON_SALE, false, false);
    }

    private static DisplaySectionUpsertCommand command(List<DisplaySectionUpsertCommand.Item> items) {
        return DisplaySectionUpsertCommand.builder().sectionType(DisplaySectionType.LOOK).visible(true).items(items).build();
    }

    private static DisplaySectionUpsertCommand.Item ref(long id) {
        return DisplaySectionUpsertCommand.Item.builder().itemType(DisplayItemType.LOOK).lookId(id).build();
    }

    private static List<DisplaySectionUpsertCommand.Item> refs(int count) {
        return LongStream.rangeClosed(1, count).mapToObj(DisplayLookReferenceTest::ref).toList();
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, DisplayErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DisplayException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }
}
