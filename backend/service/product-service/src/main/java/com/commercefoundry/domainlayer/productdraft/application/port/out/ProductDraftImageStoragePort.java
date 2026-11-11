package com.commercefoundry.domainlayer.productdraft.application.port.out;

import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftImageObjectQueryResult;
import java.util.Optional;

/**
 * 초안 이미지의 관리 키 판정 · HEAD · ETag 조건 읽기 (#359 D3). 외부 주소를 내려받지 않는다.
 * 저장소 꺼짐 · 장애는 {@code PRODUCT_DRAFT_501}(503)로 올린다. DB 트랜잭션 밖에서 부른다.
 */
public interface ProductDraftImageStoragePort {

    /** 저장소가 만드는 공개 주소와 정확히 같은 주소면 그 키다. I/O 가 없다. 외부 · 퍼센트 인코딩 · 형식이 다른 주소는 비어 있다. */
    Optional<String> managedKeyOf(String publicUrl);

    /** HEAD — {@code Stored} 또는 {@code Missing}. 호출한 쪽이 키 패턴을 확인한 키만 넘긴다. */
    ProductDraftImageObjectQueryResult head(String key);

    /** {@code expectedETag} 조건 상한 읽기 → magic bytes 판별 — {@code Read} · {@code Missing} · {@code Invalid} · {@code Changed}. */
    ProductDraftImageObjectQueryResult read(String key, int maxBytes, String expectedETag);
}
