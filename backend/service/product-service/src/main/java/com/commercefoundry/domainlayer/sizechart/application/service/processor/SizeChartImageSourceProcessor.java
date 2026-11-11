package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartImageInspectionInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartImageSourceInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftImage;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartImageLimits;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartImageSourceRejection;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartImageStoragePort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartImageObjectQueryResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 사이즈표 초안 IMAGE 입력의 후보 · 확인 · 승인 재검증. <b>관리 키만 읽는다</b> — 외부 주소 · 관리 키가 없는 이관 이미지는 내려받지 않고 사유만 돌려준다.
 * 키의 상품 소속은 요청마다 현재 상품 이미지 · 본문에서 후보를 다시 만들어 확인한다(다른 상품 키 거절). 스토리지 I/O 가 있어 트랜잭션을 걸지 않는다.
 * 로그 · 예외에 키 · 주소 · 바이트를 싣지 않는다.
 * sourceId 는 Presenter 변환 대상(long 식별자)이 아니라 이 컨텍스트가 발급하는 토큰이다({@link SizeChartImageLimits#SOURCE_ID_PATTERN}).
 */
@Component
@RequiredArgsConstructor
public class SizeChartImageSourceProcessor {
    /** 상한의 정본은 {@link SizeChartImageLimits} 다. 여기서는 같은 값을 다시 노출만 한다. */
    public static final int MAX_IMAGE_BYTES = SizeChartImageLimits.MAX_IMAGE_BYTES;
    public static final int MAX_IMAGES = SizeChartImageLimits.MAX_IMAGES;
    private static final int MAX_CANDIDATES = 40;
    private static final String IMAGE_PREFIX = SizeChartImageLimits.IMAGE_SOURCE_PREFIX;
    private static final String DESCRIPTION_PREFIX = SizeChartImageLimits.DESCRIPTION_SOURCE_PREFIX;
    private static final Pattern IMG_SRC = Pattern.compile(
        "<img\\b[^>]*?\\bsrc\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCT_KEY = Pattern.compile("products/([1-9][0-9]{0,18})/[1-9][0-9]{0,18}\\.(?:jpg|png|webp)");
    private static final Pattern MEDIA_KEY = Pattern.compile(
        "products/media/[1-9][0-9]{0,18}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{64}\\.(?:jpg|png|webp)");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final SizeChartImageStoragePort storage;

    /** 후보 목록. 내려받지 않는다. */
    public List<SizeChartImageSourceInfo> sources(long productId, Product product, List<ProductImage> images) {
        return candidates(productId, product, images).stream()
            .map(c -> new SizeChartImageSourceInfo(c.sourceId(), c.previewUrl(), c.rejection() == null, c.rejection())).toList();
    }

    /** 운영자가 승인 전에 보는 확인값. 이 상품 후보의 관리 키만 읽는다. */
    public SizeChartImageInspectionInfo inspect(long productId, Product product, List<ProductImage> images, String sourceId) {
        Candidate candidate = eligible(productId, product, images, sourceId);
        var found = found(storage.read(candidate.key(), MAX_IMAGE_BYTES), SizeChartDraftTransportErrorCode.IMAGE_APPROVAL_MISMATCH);
        return new SizeChartImageInspectionInfo(sourceId, sha256(found.bytes()), found.contentType(), found.bytes().length);
    }

    /** 생성 직전 재검증. 지금 바이트의 SHA-256 이 승인 값과 같아야 초안 입력이 된다. 요청 순서를 지킨다. */
    public List<SizeChartDraftImage> approve(long productId, Product product, List<ProductImage> images, List<SizeChartDraftCreateCommand.Image> approvals) {
        if (approvals == null || approvals.isEmpty() || approvals.size() > MAX_IMAGES
            || new HashSet<>(approvals.stream().map(SizeChartDraftCreateCommand.Image::sourceId).toList()).size() != approvals.size()
            || approvals.stream().anyMatch(a -> a.approvedContentHash() == null || !SHA256.matcher(a.approvedContentHash()).matches())) {
            throw failure(SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        }
        List<Candidate> candidates = candidates(productId, product, images);
        List<Candidate> selected = approvals.stream().map(a -> eligible(candidates, a.sourceId())).toList();
        if (new HashSet<>(selected.stream().map(Candidate::key).toList()).size() != selected.size()) {
            throw failure(SizeChartDraftTransportErrorCode.UNSAFE_INPUT); // 다른 아이디로 같은 키를 두 번 보낼 수 없다
        }
        List<SizeChartDraftImage> approved = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            var found = found(storage.read(selected.get(i).key(), MAX_IMAGE_BYTES), SizeChartDraftTransportErrorCode.IMAGE_APPROVAL_MISMATCH);
            String hash = sha256(found.bytes());
            if (!MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII), approvals.get(i).approvedContentHash().getBytes(StandardCharsets.US_ASCII))) {
                throw failure(SizeChartDraftTransportErrorCode.IMAGE_APPROVAL_MISMATCH);
            }
            approved.add(new SizeChartDraftImage(selected.get(i).key(), hash, found.contentType(), found.bytes().length));
        }
        return List.copyOf(approved);
    }

    /** 생성 작업이 읽는 바이트. 저장된 해시 · 형식 · 길이와 다르면 같은 초안으로는 다시 시도해도 안 되는 오류다. */
    public byte[] generationBytes(SizeChartDraftImage expected) {
        var found = found(storage.read(expected.key(), MAX_IMAGE_BYTES), SizeChartDraftTransportErrorCode.IMAGE_INPUT_CHANGED,
            SizeChartDraftTransportErrorCode.IMAGE_INPUT_CHANGED, SizeChartDraftTransportErrorCode.IMAGE_INPUT_CHANGED);
        if (found.bytes().length != expected.length() || !found.contentType().equals(expected.contentType())
            || !MessageDigest.isEqual(sha256(found.bytes()).getBytes(StandardCharsets.US_ASCII), expected.sha256().getBytes(StandardCharsets.US_ASCII))) {
            throw failure(SizeChartDraftTransportErrorCode.IMAGE_INPUT_CHANGED);
        }
        return found.bytes();
    }

    private Candidate eligible(long productId, Product product, List<ProductImage> images, String sourceId) {
        return eligible(candidates(productId, product, images), sourceId);
    }

    private Candidate eligible(List<Candidate> candidates, String sourceId) {
        Candidate candidate = candidates.stream().filter(c -> c.sourceId().equals(sourceId)).findFirst()
            .orElseThrow(() -> failure(SizeChartDraftTransportErrorCode.IMAGE_SOURCE_NOT_FOUND));
        if (candidate.rejection() != null || candidate.key() == null) {
            throw failure(SizeChartDraftTransportErrorCode.IMAGE_SOURCE_NOT_ELIGIBLE);
        }
        return candidate;
    }

    private List<Candidate> candidates(long productId, Product product, List<ProductImage> images) {
        if (product == null || product.id() != productId) {
            throw failure(SizeChartDraftTransportErrorCode.IMAGE_SOURCE_NOT_FOUND);
        }
        List<Candidate> result = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        for (ProductImage image : images == null ? List.<ProductImage>of() : images) {
            if (image.productId() != productId || image.imageType() == ImageType.VIDEO || !urls.add(image.url())) {
                continue;
            }
            String key = image.storageKey();
            boolean managed = key != null && PRODUCT_KEY.matcher(key).matches() && ownedBy(key, productId);
            result.add(new Candidate(IMAGE_PREFIX + image.id(), preview(image.url()), managed ? key : null,
                managed ? null : SizeChartImageSourceRejection.LEGACY_IMAGE));
        }
        for (String url : descriptionImageUrls(product)) {
            if (result.size() >= MAX_CANDIDATES) {
                break;
            }
            if (!urls.add(url)) {
                continue;
            }
            Optional<String> key = storage.managedKeyOf(url);
            String id = DESCRIPTION_PREFIX + sha256(url.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
            if (key.isEmpty()) {
                result.add(new Candidate(id, null, null, SizeChartImageSourceRejection.EXTERNAL_IMAGE));
            } else if (MEDIA_KEY.matcher(key.get()).matches() || PRODUCT_KEY.matcher(key.get()).matches() && ownedBy(key.get(), productId)) {
                result.add(new Candidate(id, url, key.get(), null));
            } else if (PRODUCT_KEY.matcher(key.get()).matches()) {
                result.add(new Candidate(id, url, null, SizeChartImageSourceRejection.OTHER_PRODUCT_IMAGE));
            } else {
                result.add(new Candidate(id, url, null, SizeChartImageSourceRejection.LEGACY_IMAGE));
            }
        }
        return result.size() > MAX_CANDIDATES ? List.copyOf(result.subList(0, MAX_CANDIDATES)) : List.copyOf(result);
    }

    private String preview(String url) {
        return storage.managedKeyOf(url).isPresent() ? url : null;
    }

    private static boolean ownedBy(String productKey, long productId) {
        Matcher matcher = PRODUCT_KEY.matcher(productKey);
        return matcher.matches() && matcher.group(1).equals(Long.toString(productId));
    }

    /** 본문 국문 · 영문의 {@code <img src>} 주소. 주소를 따라가지 않는다. */
    private static List<String> descriptionImageUrls(Product product) {
        List<String> urls = new ArrayList<>();
        Stream.of(product.descriptionHtml(), product.descriptionHtmlEn()).filter(html -> html != null && !html.isBlank()).forEach(html -> {
            Matcher matcher = IMG_SRC.matcher(html);
            while (matcher.find() && urls.size() < MAX_CANDIDATES) {
                String src = Stream.of(matcher.group(1), matcher.group(2), matcher.group(3)).filter(v -> v != null).findFirst().orElse("").strip();
                if (!src.isEmpty() && src.length() <= 2048) {
                    urls.add(src);
                }
            }
        });
        return urls;
    }

    private static SizeChartImageObjectQueryResult.Found found(SizeChartImageObjectQueryResult result, SizeChartDraftTransportErrorCode changed) {
        return found(result, SizeChartDraftTransportErrorCode.IMAGE_SOURCE_NOT_FOUND, SizeChartDraftTransportErrorCode.IMAGE_FORMAT_NOT_ALLOWED, changed);
    }

    private static SizeChartImageObjectQueryResult.Found found(SizeChartImageObjectQueryResult result, SizeChartDraftTransportErrorCode missing,
        SizeChartDraftTransportErrorCode invalid, SizeChartDraftTransportErrorCode changed) {
        return switch (result) {
            case SizeChartImageObjectQueryResult.Found found -> found;
            case SizeChartImageObjectQueryResult.Missing ignored -> throw failure(missing);
            case SizeChartImageObjectQueryResult.Invalid ignored -> throw failure(invalid);
            case SizeChartImageObjectQueryResult.Changed ignored -> throw failure(changed);
        };
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static SizeChartDraftTransportException failure(SizeChartDraftTransportErrorCode code) {
        return new SizeChartDraftTransportException(code);
    }

    /** @param key 관리 키. 부적격이면 null 이다 */
    private record Candidate(String sourceId, String previewUrl, String key, SizeChartImageSourceRejection rejection) {
    }
}
