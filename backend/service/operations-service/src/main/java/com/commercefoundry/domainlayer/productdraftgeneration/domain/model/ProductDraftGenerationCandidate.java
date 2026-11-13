package com.commercefoundry.domainlayer.productdraftgeneration.domain.model;

import java.util.List;

/**
 * AI 가 낸 상품 문구 제안 — 불신 입력이다. 소재 · 세탁 · 치수 · 원산지 · 인증 칸이 <b>없다</b>: 사실은 운영자 입력만이고 AI 가 채울 자리를 두지 않는다.
 * 확인이 필요한 속성 목록 · 경고는 서버가 운영자 입력으로 계산한다.
 *
 * @param name           상품명 제안
 * @param description    상품 설명 제안 (평문, 개행 허용)
 * @param searchTags     검색 태그 제안
 * @param seoTitle       검색 결과 제목 제안
 * @param seoDescription 검색 결과 설명 제안
 * @param imageAlts      이미지별 대체 텍스트 제안
 */
public record ProductDraftGenerationCandidate(String name, String description, List<String> searchTags, String seoTitle, String seoDescription,
                                              List<ImageAlt> imageAlts) {

    /** 이미지 순번별 대체 텍스트. */
    public record ImageAlt(int index, String altText) {

        @Override
        public String toString() {
            return "ImageAlt[redacted]";
        }
    }

    @Override
    public String toString() {
        return "ProductDraftGenerationCandidate[redacted]";
    }
}
