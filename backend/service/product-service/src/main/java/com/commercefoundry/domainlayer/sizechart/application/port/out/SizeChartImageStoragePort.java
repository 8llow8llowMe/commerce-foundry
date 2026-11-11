package com.commercefoundry.domainlayer.sizechart.application.port.out;

import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartImageObjectQueryResult;
import java.util.Optional;

/**
 * 사이즈표 초안 이미지의 관리 키 판정과 상한 읽기. 외부 주소를 내려받지 않는다.
 * 저장소 꺼짐 · 장애는 {@code SIZE_CHART_DRAFT_504}(503)로 올린다. DB 트랜잭션 밖에서 부른다.
 */
public interface SizeChartImageStoragePort {

    /** 우리 공개 저장소 주소면 그 키다. I/O 가 없다. 외부 · 형식이 다른 주소는 비어 있다. */
    Optional<String> managedKeyOf(String publicUrl);

    /** HEAD → ETag 조건 상한 읽기 → magic bytes 판별. 호출한 쪽이 소속을 확인한 키만 넘긴다. */
    SizeChartImageObjectQueryResult read(String key, int maxBytes);
}
