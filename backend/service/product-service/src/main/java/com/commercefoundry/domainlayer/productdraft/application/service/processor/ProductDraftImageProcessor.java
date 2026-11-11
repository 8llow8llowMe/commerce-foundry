package com.commercefoundry.domainlayer.productdraft.application.service.processor;

import com.commercefoundry.ai.client.AiImage;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftImageStoragePort;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftImageObjectQueryResult;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 상품 초안 이미지의 키 · ETag 고정과 생성 작업용 바이트 읽기 (#359 D3 · D11). <b>스토리지 I/O 가 있어 트랜잭션을 걸지 않는다</b> — 호출한 쪽도 트랜잭션 밖에서 부른다.
 * 외부 주소를 내려받지 않는다. 로그 · 예외에 키 · 주소 · ETag · 바이트를 싣지 않는다.
 *
 * <p><b>상한</b> — 장당 바이트는 ai-core {@link AiImage#MAX_BYTES}(5MiB)다. 장수는 요청 DTO 의 8장(PRODUCT_DRAFT_120)이 {@link AiImage#MAX_COUNT}(8)와 같아
 * 여기서 다시 세지 않는다 — 둘 중 작은 값이 8 이다.
 *
 * <p><b>허용 키</b> — 업로드를 마친 상품 미디어 키 {@code products/media/{actorId}/{UUID}/{SHA-256}.{jpg|png|webp}} 만이다(D11, 모양은 미디어 업로드 확정
 * {@code MediaImageStorageAdapter} 가 만든다). 업로드 확정 전 원본({@code uploads/...}) · 상품별 키({@code products/{id}/...}) · 이관 이미지는 받지 않는다.
 * 상품별 키를 막는 것은 다른 상품 소속을 이 요청에서 확인할 수 없어서다 — 초안은 상품이 아직 없다.
 */
@Component
@RequiredArgsConstructor
public class ProductDraftImageProcessor {

    public static final int MAX_IMAGE_BYTES = AiImage.MAX_BYTES;
    static final Pattern MEDIA_KEY = Pattern.compile(
        "products/media/[1-9][0-9]{0,18}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{64}\\.(jpg|png|webp)");
    private static final Map<String, String> CONTENT_TYPE_BY_EXTENSION = Map.of("jpg", "image/jpeg", "png", "image/png", "webp", "image/webp");

    private final ProductDraftImageStoragePort storage;

    /**
     * 요청 이미지를 관리 키로 바꾸고 HEAD 값을 고정한다. 순서를 지킨다. 키 판정을 전부 마친 뒤에 HEAD 를 부른다 — 잘못된 키 하나 때문에 저장소를 부르지 않는다.
     * HEAD 는 장마다 한 번(최대 8번)이다 — 저장소에 여러 객체를 한 번에 확인하는 API 가 없다.
     *
     * @throws ProductDraftException {@code 031}(관리 키 아님) · {@code 033}(없음) · {@code 036}(크기 · 형식) · {@code 501}(저장소 장애, 503)
     */
    public List<ProductDraftImage> pin(List<ProductDraftImage> images) {
        if (images.isEmpty()) {
            return List.of();
        }
        List<String> keys = images.stream().map(image -> managedKey(image.url())).toList();
        List<ProductDraftImage> pinned = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            pinned.add(images.get(i).pinned(head(keys.get(i))));
        }
        return List.copyOf(pinned);
    }

    /**
     * 생성 작업이 읽는 바이트. 고정한 ETag 조건으로 읽고 magic bytes · 크기 · 형식을 고정 값과 다시 맞춘다.
     * 없어졌거나 바뀌었으면 같은 초안으로는 다시 시도해도 안 되는 {@code 044}(410)다.
     */
    public byte[] generationBytes(ProductDraftImageSource source) {
        // 저장 경로(pin)가 이미 걸렀어도, product 자격 증명으로 다른 접두를 대신 읽지 않게 읽기 직전에 다시 확인한다.
        if (!MEDIA_KEY.matcher(source.key()).matches()) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_IMAGE_CHANGED);
        }
        ProductDraftImageObjectQueryResult result = storage.read(source.key(), MAX_IMAGE_BYTES, source.eTag());
        if (!(result instanceof ProductDraftImageObjectQueryResult.Read read)
            || read.bytes().length != source.length() || !read.contentType().equals(source.contentType())) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_IMAGE_CHANGED);
        }
        return read.bytes();
    }

    private String managedKey(String url) {
        return storage.managedKeyOf(url).filter(key -> MEDIA_KEY.matcher(key).matches())
            .orElseThrow(() -> new ProductDraftException(ProductDraftErrorCode.IMAGE_NOT_MANAGED));
    }

    private ProductDraftImageSource head(String key) {
        ProductDraftImageObjectQueryResult result = storage.head(key);
        if (!(result instanceof ProductDraftImageObjectQueryResult.Stored stored)) {
            throw new ProductDraftException(ProductDraftErrorCode.IMAGE_NOT_FOUND);
        }
        Matcher matcher = MEDIA_KEY.matcher(key);
        String expectedType = matcher.matches() ? CONTENT_TYPE_BY_EXTENSION.get(matcher.group(1)) : null;
        // 키 확장자와 저장 형식이 같아야 한다 — 업로드 확정이 둘을 같이 정하므로 다르면 저장소 밖에서 바뀐 객체다.
        if (stored.length() <= 0 || stored.length() > MAX_IMAGE_BYTES || stored.contentType() == null || !stored.contentType().equals(expectedType)
            || stored.eTag() == null || stored.eTag().isBlank()) {
            throw new ProductDraftException(ProductDraftErrorCode.IMAGE_FORMAT_NOT_ALLOWED);
        }
        return new ProductDraftImageSource(key, stored.eTag(), stored.length(), stored.contentType());
    }
}
