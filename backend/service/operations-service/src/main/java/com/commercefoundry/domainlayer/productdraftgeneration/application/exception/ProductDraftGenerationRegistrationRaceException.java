package com.commercefoundry.domainlayer.productdraftgeneration.application.exception;

/**
 * 등록 경합에서 졌다는 내부 신호 — 같은 멱등 키로 먼저 커밋된 다른 요청의 활성 작업을 받았는데 이 요청과 결합되지 않는다.
 * Facade 가 받아 쓰기 트랜잭션을 롤백한 뒤 새 읽기 트랜잭션에서 이긴 영수증을 재생한다(다르면 409, 없으면 503). Facade 밖으로 나가지 않는다.
 */
public final class ProductDraftGenerationRegistrationRaceException extends RuntimeException {
    public ProductDraftGenerationRegistrationRaceException() {
        super(null, null, false, false);
    }
}
