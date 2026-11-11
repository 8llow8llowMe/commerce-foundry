package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartImageSourceInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftImage;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartImageSourceRejection;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartImageStoragePort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartImageObjectQueryResult;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class SizeChartImageSourceProcessorTest {
    private static final long PRODUCT_ID = 700;
    private static final String BASE = "https://cdn.example.com/";
    private static final String GALLERY_KEY = "products/700/4611686018427387905.png";
    private static final String MEDIA_KEY = "products/media/40/11111111-1111-4111-8111-111111111111/" + "d".repeat(64) + ".jpg";
    private static final String OTHER_KEY = "products/701/4611686018427387906.png";
    private static final String EXTERNAL = "https://shop.example.org/size.jpg";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
    private static final String PNG_HASH = SizeChartImageSourceProcessor.sha256(PNG);

    private final SizeChartImageStoragePort storage = mock(SizeChartImageStoragePort.class);
    private final SizeChartImageSourceProcessor processor = new SizeChartImageSourceProcessor(storage);
    private final Product product = product("<p>표</p><img src=\"" + BASE + MEDIA_KEY + "\"><IMG alt='x' src='" + EXTERNAL + "'>"
        + "<img src=" + BASE + OTHER_KEY + ">");
    private final List<ProductImage> images = List.of(
        new ProductImage(4611686018427387905L, PRODUCT_ID, ImageType.GALLERY, BASE + GALLERY_KEY, null, null, null, 1, null, GALLERY_KEY),
        new ProductImage(4611686018427387907L, PRODUCT_ID, ImageType.GALLERY, "https://legacy.example.org/a.jpg", null, null, null, 2, null),
        new ProductImage(4611686018427387908L, PRODUCT_ID, ImageType.VIDEO, BASE + "products/700/v.mp4", null, null, null, 3, BASE + "p.jpg"));

    @BeforeEach
    void storage() {
        when(storage.managedKeyOf(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            return url.startsWith(BASE) ? Optional.of(url.substring(BASE.length())) : Optional.empty();
        });
    }

    @Test
    void listsManagedImagesAsEligibleAndExplainsTheRestWithoutReadingBytes() {
        List<SizeChartImageSourceInfo> sources = processor.sources(PRODUCT_ID, product, images);

        assertThat(sources).extracting(SizeChartImageSourceInfo::eligible).containsExactly(true, false, true, false, false);
        assertThat(sources.get(0).sourceId()).isEqualTo("IMG-4611686018427387905");
        assertThat(sources.get(0).previewUrl()).isEqualTo(BASE + GALLERY_KEY);
        assertThat(sources.get(1).rejection()).isEqualTo(SizeChartImageSourceRejection.LEGACY_IMAGE);
        assertThat(sources.get(1).previewUrl()).isNull();
        assertThat(sources.get(2).sourceId()).matches("DESC-[0-9a-f]{32}");
        assertThat(sources.get(3).rejection()).isEqualTo(SizeChartImageSourceRejection.EXTERNAL_IMAGE);
        assertThat(sources.get(3).previewUrl()).isNull();
        assertThat(sources.get(4).rejection()).isEqualTo(SizeChartImageSourceRejection.OTHER_PRODUCT_IMAGE);
        // 미리보기는 우리 공개 주소만 싣는다. 외부 주소는 응답에 다시 싣지 않는다.
        assertThat(sources.toString()).doesNotContain("shop.example.org", "legacy.example.org");
        verify(storage, never()).read(anyString(), anyInt());
    }

    @Test
    void inspectionReadsOnlyThisProductsManagedKeyAndReturnsHash() {
        when(storage.read(GALLERY_KEY, SizeChartImageSourceProcessor.MAX_IMAGE_BYTES)).thenReturn(new SizeChartImageObjectQueryResult.Found(PNG, "image/png"));

        var info = processor.inspect(PRODUCT_ID, product, images, "IMG-4611686018427387905");

        assertThat(info.contentHash()).isEqualTo(PNG_HASH).matches("[0-9a-f]{64}");
        assertThat(info.contentType()).isEqualTo("image/png");
        assertThat(info.contentLength()).isEqualTo(PNG.length);
    }

    @Test
    void ineligibleUnknownAndOtherProductSourcesAreRejectedBeforeStorage() {
        failure(() -> processor.inspect(PRODUCT_ID, product, images, "IMG-4611686018427387907"), SizeChartDraftTransportErrorCode.IMAGE_SOURCE_NOT_ELIGIBLE);
        failure(() -> processor.inspect(PRODUCT_ID, product, images, "IMG-1"), SizeChartDraftTransportErrorCode.IMAGE_SOURCE_NOT_FOUND);
        String otherId = processor.sources(PRODUCT_ID, product, images).get(4).sourceId();
        failure(() -> processor.inspect(PRODUCT_ID, product, images, otherId), SizeChartDraftTransportErrorCode.IMAGE_SOURCE_NOT_ELIGIBLE);
        // 다른 상품의 이미지 행이 섞여 와도 이 상품 후보가 아니다.
        var foreign = List.of(new ProductImage(4611686018427387906L, 701, ImageType.GALLERY, BASE + OTHER_KEY, null, null, null, 1, null, OTHER_KEY));
        failure(() -> processor.inspect(PRODUCT_ID, product, foreign, "IMG-4611686018427387906"), SizeChartDraftTransportErrorCode.IMAGE_SOURCE_NOT_FOUND);
        // 이 상품 이미지 행이라도 키가 다른 상품 경로면 관리 키로 보지 않는다.
        var forged = List.of(new ProductImage(4611686018427387909L, PRODUCT_ID, ImageType.GALLERY, BASE + OTHER_KEY, null, null, null, 1, null, OTHER_KEY));
        failure(() -> processor.inspect(PRODUCT_ID, product, forged, "IMG-4611686018427387909"), SizeChartDraftTransportErrorCode.IMAGE_SOURCE_NOT_ELIGIBLE);
        verify(storage, never()).read(anyString(), anyInt());
    }

    @Test
    void invalidFormatSizeAndChangedObjectsMapToBusinessCodes() {
        when(storage.read(GALLERY_KEY, SizeChartImageSourceProcessor.MAX_IMAGE_BYTES))
            .thenReturn(new SizeChartImageObjectQueryResult.Invalid(), new SizeChartImageObjectQueryResult.Changed(), new SizeChartImageObjectQueryResult.Missing());
        failure(() -> processor.inspect(PRODUCT_ID, product, images, "IMG-4611686018427387905"), SizeChartDraftTransportErrorCode.IMAGE_FORMAT_NOT_ALLOWED);
        failure(() -> processor.inspect(PRODUCT_ID, product, images, "IMG-4611686018427387905"), SizeChartDraftTransportErrorCode.IMAGE_APPROVAL_MISMATCH);
        failure(() -> processor.inspect(PRODUCT_ID, product, images, "IMG-4611686018427387905"), SizeChartDraftTransportErrorCode.IMAGE_SOURCE_NOT_FOUND);
    }

    @Test
    void approvalRereadsBytesAndRequiresTheApprovedHash() {
        when(storage.read(any(), anyInt())).thenReturn(new SizeChartImageObjectQueryResult.Found(PNG, "image/png"));
        String mediaId = processor.sources(PRODUCT_ID, product, images).get(2).sourceId();

        var approved = processor.approve(PRODUCT_ID, product, images, List.of(
            new SizeChartDraftCreateCommand.Image("IMG-4611686018427387905", PNG_HASH), new SizeChartDraftCreateCommand.Image(mediaId, PNG_HASH)));

        assertThat(approved).containsExactly(new SizeChartDraftImage(GALLERY_KEY, PNG_HASH, "image/png", PNG.length),
            new SizeChartDraftImage(MEDIA_KEY, PNG_HASH, "image/png", PNG.length));
        failure(() -> processor.approve(PRODUCT_ID, product, images, List.of(new SizeChartDraftCreateCommand.Image("IMG-4611686018427387905", "e".repeat(64)))),
            SizeChartDraftTransportErrorCode.IMAGE_APPROVAL_MISMATCH);
        failure(() -> processor.approve(PRODUCT_ID, product, images, List.of()), SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
    }

    @Test
    void generationBytesFailPermanentlyWhenStoredHashNoLongerMatches() {
        var expected = new SizeChartDraftImage(GALLERY_KEY, PNG_HASH, "image/png", PNG.length);
        when(storage.read(GALLERY_KEY, SizeChartImageSourceProcessor.MAX_IMAGE_BYTES)).thenReturn(
            new SizeChartImageObjectQueryResult.Found(PNG, "image/png"),
            new SizeChartImageObjectQueryResult.Found(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0}, "image/jpeg"),
            new SizeChartImageObjectQueryResult.Missing(), new SizeChartImageObjectQueryResult.Changed());

        assertThat(processor.generationBytes(expected)).isEqualTo(PNG);
        failure(() -> processor.generationBytes(expected), SizeChartDraftTransportErrorCode.IMAGE_INPUT_CHANGED);
        failure(() -> processor.generationBytes(expected), SizeChartDraftTransportErrorCode.IMAGE_INPUT_CHANGED);
        failure(() -> processor.generationBytes(expected), SizeChartDraftTransportErrorCode.IMAGE_INPUT_CHANGED);
    }

    private static void failure(Executable call, SizeChartDraftTransportErrorCode code) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(SizeChartDraftTransportException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private static Product product(String html) {
        return new Product(PRODUCT_ID, 1L, "상품", null, html, SaleStatus.ON_SALE, true, Money.of(39000, CurrencyCode.KRW), null, null,
            false, null, null, false, null, 0L, null, null);
    }
}
