package com.commercefoundry.domainlayer.look.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 룩(Shop the Look) 오류 코드 ({@code coding-conventions.md} §8). product-service 의 {@code PRODUCT_} 검증 대역(1xx)이 다 차서
 * 룩은 컨텍스트 접두어 {@code LOOK_} 를 새로 연다 (§8-2, 선례: order-service 의 컨텍스트 접두어). 업무 오류는 001~, 필드별 검증 코드(101~)는
 * {@link LookValidationMessage} 에 둔다.
 *
 * <p>요청 검증 실패는 이 enum 이 아니라 서비스 공용 검증 처리({@code ProductExceptionHandler})가 봉투로 바꾼다 — 메시지 앞의 {@code LOOK_1xx} 를 그대로 쓰고,
 * 역직렬화 실패 · 타입 불일치 · 필수 파라미터 누락은 서비스 공용 {@code PRODUCT_100} · {@code PRODUCT_198} · {@code PRODUCT_199} 다.
 */
@Getter
@RequiredArgsConstructor
public enum LookErrorCode {

    /** 경로의 룩 아이디가 없다. */
    NOT_FOUND_LOOK("LOOK_001", "존재하지 않는 룩입니다.", HttpStatus.NOT_FOUND),
    /** 룩 수 상한에 닿았다. 요청 값은 올바르고 룩 상태가 막으므로 409 다 — 하나를 지우면 다시 된다. */
    LOOK_LIMIT_EXCEEDED("LOOK_002", "룩은 최대 %s개까지 만들 수 있습니다.", HttpStatus.CONFLICT),
    /** 한 룩에 같은 상품이 두 번 있다. */
    LOOK_PRODUCT_DUPLICATED("LOOK_003", "한 룩에 같은 상품을 두 번 넣을 수 없습니다.", HttpStatus.BAD_REQUEST),
    /** 본문의 상품 아이디가 없는 상품이다(숨김 · 판매 종료 상품은 있는 상품이다). 본문 참조라 400 이다 — 메시지에 없는 아이디를 모두 싣는다. */
    LOOK_PRODUCT_NOT_FOUND("LOOK_004", "존재하지 않는 상품이 있습니다: %s", HttpStatus.BAD_REQUEST),
    /** 이미지 주소가 스토어 미디어 저장소의 공개 주소 아래가 아니거나 형식이 틀렸다. 메시지에 공개 주소를 싣지 않는다. */
    LOOK_IMAGE_URL_NOT_ALLOWED("LOOK_005", "이미지 주소는 스토어 미디어 저장소의 공개 주소여야 합니다.", HttpStatus.BAD_REQUEST),
    /** 미디어 공개 주소({@code store.media.public-base-url})가 설정되지 않아 이미지가 필수인 룩 쓰기를 받지 않는다(닫힌 기본값). 서버 설정 문제라 503 이다. */
    LOOK_MEDIA_STORAGE_NOT_CONFIGURED("LOOK_006", "미디어 저장소 주소가 설정되지 않아 룩 이미지를 등록할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    /** 순서 변경 본문이 현재 룩 아이디 집합과 다르다 — 누락 · 중복 · 모르는 아이디. 다시 조회한 뒤 보낸다. */
    LOOK_ORDER_MISMATCH("LOOK_007", "순서 변경에는 현재 룩 아이디를 빠짐없이 한 번씩 보내야 합니다.", HttpStatus.BAD_REQUEST),
    /** 다른 어드민의 룩 쓰기가 룩 잠금을 쥐고 있어 시간 상한 안에 끝나지 않았다. 요청끼리 겹친 것이라 503 이 아니다. */
    LOOK_BUSY("LOOK_008", "다른 룩 변경을 처리 중입니다. 다시 시도해 주세요.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
