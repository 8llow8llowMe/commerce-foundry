package com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client;

import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client.feign.ProductDraftGenerationInputClient;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client.support.ProductDraftGenerationInputInternalResponseSupport;
import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure;
import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure.Code;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationInputPort;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationImageQueryResult;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationInputQueryResult;
import feign.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductDraftGenerationInputClientAdapter implements ProductDraftGenerationInputPort {

    /** product 이미지 바이트 응답의 계약 형식 ({@code ProductDraftInternalController#generationImage}). */
    static final String OCTET_STREAM = "application/octet-stream";

    private final ProductDraftGenerationInputClient client;
    private final ProductDraftGenerationInputInternalResponseSupport support;

    @Override
    public ProductDraftGenerationInputQueryResult getGenerationInput(long draftId) {
        return support.toQueryResult(support.call(() -> client.getGenerationInput(draftId)));
    }

    /**
     * 순번마다 한 번 부른다 — product 계약이 봉투 없는 바이트 한 장씩이라 벌크 엔드포인트가 없다(최대 8장, 검증 Processor 가 장수 · 합계를 메타데이터로 먼저 막는다).
     * 한 장이라도 실패하면 거기서 멈춘다. {@code onImage} 는 서킷 밖에서 부른다 — 호출자의 검증 실패 · 취소가 product 장애로 세이지 않는다.
     */
    @Override
    public void readGenerationImages(long draftId, List<ImageRequest> images, Consumer<ProductDraftGenerationImageQueryResult> onImage) {
        for (ImageRequest image : images) {
            ProductDraftGenerationImageQueryResult received = support.call(() -> read(draftId, image));
            if (received == null) {
                // 헤더 · 본문이 고정 값과 다르다. product 는 정상 응답했으므로 서킷에는 성공으로 남는다
                throw new ProductDraftGenerationRemoteFailure(Code.PRODUCT_DRAFT_IMAGE_CHANGED, false);
            }
            onImage.accept(received);
        }
    }

    /**
     * 서킷 안에서 한 장을 읽는다. 순서: 상태(2xx 아니면 기본 ErrorDecoder 와 같은 예외) → {@code Content-Length}(없거나 다르면 본문을 읽지 않음) →
     * {@code Content-Type} → 본문을 {@code byteLength + 1} 바이트까지만 읽기. 고정 값과 다르면 null(바뀐 이미지)이다. 응답 · 스트림은 여기서 닫는다.
     */
    private ProductDraftGenerationImageQueryResult read(long draftId, ImageRequest image) {
        try (Response response = client.getGenerationImage(draftId, image.index())) {
            support.requireSuccess(response);
            if (!lengthMatches(response, image.byteLength())) {
                return null;
            }
            String mediaType = mediaType(response);
            if (mediaType == null || !(OCTET_STREAM.equals(mediaType) || image.contentType().equals(mediaType))) {
                return null;
            }
            byte[] bytes = readExactly(response, image.byteLength());
            return bytes == null ? null : new ProductDraftGenerationImageQueryResult(image.index(), mediaType, bytes);
        } catch (IOException exception) {
            // 본문을 읽다 끊겼거나 읽기 타임아웃 — support 가 재시도 가능한 UNAVAILABLE 로 바꾼다(원인은 싣지 않는다)
            throw new UncheckedIOException(exception);
        }
    }

    /** {@code Content-Length} 가 하나뿐이고 {@code expected} 와 같은가. */
    private static boolean lengthMatches(Response response, int expected) {
        String value = single(response.headers().get("Content-Length"));
        if (value == null || value.isEmpty() || value.length() > 19 || !value.chars().allMatch(Character::isDigit)) {
            return false;
        }
        return Long.parseLong(value) == expected;
    }

    /** {@code Content-Type} 하나에서 파라미터를 뺀 소문자 형식. 없거나 여러 개면 null. */
    private static String mediaType(Response response) {
        String value = single(response.headers().get("Content-Type"));
        if (value == null) {
            return null;
        }
        int parameters = value.indexOf(';');
        String type = (parameters < 0 ? value : value.substring(0, parameters)).strip().toLowerCase(Locale.ROOT);
        return type.isEmpty() ? null : type;
    }

    private static String single(Collection<String> values) {
        return values == null || values.size() != 1 ? null : values.iterator().next();
    }

    /** 본문을 {@code length + 1} 바이트까지만 읽는다. 짧거나 길면 null — 길면 나머지를 읽지 않고 끊는다. */
    private static byte[] readExactly(Response response, int length) throws IOException {
        if (response.body() == null) {
            return null;
        }
        try (InputStream stream = response.body().asInputStream()) {
            byte[] bytes = new byte[length];
            if (stream.readNBytes(bytes, 0, length) != length || stream.read() != -1) {
                return null;
            }
            return bytes;
        }
    }
}
