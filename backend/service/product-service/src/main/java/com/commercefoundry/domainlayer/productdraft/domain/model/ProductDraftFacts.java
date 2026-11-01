package com.commercefoundry.domainlayer.productdraft.domain.model;

import java.util.List;

/**
 * 운영자가 직접 입력한 사실 — 초안의 근거다. AI 가 이미지로 추정한 값과 섞지 않는다(#359): 소재 · 세탁 · 치수 · 원산지 · 인증은 여기 있는 값만 사실이다.
 * 고객 개인정보를 담지 않는다. 문구는 앞뒤 공백을 뺀 평문이고, 빈 값은 null · 빈 목록이다.
 *
 * @param nameHint 상품명 후보
 * @param categoryId 카테고리 아이디 — 존재는 적용 때 상품 등록 검증이 본다
 * @param materials 소재 (예: 면 100%)
 * @param careNotes 세탁 · 관리 방법
 * @param measurementsNote 치수 메모 (개행 허용)
 * @param origin 원산지
 * @param certifications 인증
 * @param tone 문구 분위기
 * @param mustInclude 꼭 넣을 표현
 * @param mustAvoid 쓰지 않을 표현
 */
public record ProductDraftFacts(
    String nameHint,
    Long categoryId,
    List<String> materials,
    List<String> careNotes,
    String measurementsNote,
    String origin,
    List<String> certifications,
    String tone,
    List<String> mustInclude,
    List<String> mustAvoid
) {

    public ProductDraftFacts {
        materials = copyOf(materials);
        careNotes = copyOf(careNotes);
        certifications = copyOf(certifications);
        mustInclude = copyOf(mustInclude);
        mustAvoid = copyOf(mustAvoid);
    }

    private static List<String> copyOf(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
