package com.commercefoundry.domainlayer.productdraft.application.port.out.query;

/**
 * operations 생성 작업 등록 결과 (#359 PR-B1). 형식(양수 아이디 · 소문자 hex 64자)과 보낸 초안 회차와의 일치는 어댑터가 확인했다.
 *
 * @param draftId 영수증의 초안 아이디
 * @param generationNo 영수증의 회차
 * @param jobId 등록된(또는 재생된) 작업 아이디
 * @param inputHash 영수증에 저장된 입력 hash
 */
public record ProductDraftGenerationRegistrationQueryResult(long draftId, int generationNo, long jobId, String inputHash) {
}
