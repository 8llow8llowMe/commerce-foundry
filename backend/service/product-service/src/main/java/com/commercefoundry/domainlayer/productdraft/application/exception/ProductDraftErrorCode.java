package com.commercefoundry.domainlayer.productdraft.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * AI 상품 초안 오류 코드 ({@code coding-conventions.md} §8). {@code PRODUCT_1xx} 검증 대역이 다 차서 컨텍스트 접두어 {@code PRODUCT_DRAFT_} 를 새로 연다
 * (§8-2, 선례: 룩 {@code LOOK_} · SKU 원가 {@code SKU_COST_}). 업무 오류는 001~, 필드별 검증 코드(101~)는 {@link ProductDraftValidationMessage} 에 둔다.
 * 번호 대역: 0xx 초안 존재 · 상태, 03x 이미지. 메시지는 운영자가 읽는 문구다 — 설정값 · 내부 상태 이름을 싣지 않는다.
 *
 * <p>요청 검증 실패는 서비스 공용 검증 처리({@code ProductExceptionHandler})가 봉투로 바꾼다 — 메시지 앞의 {@code PRODUCT_DRAFT_1xx} 를 그대로 쓰고,
 * 역직렬화 실패 · 타입 불일치 · 필수 파라미터 누락은 서비스 공용 {@code PRODUCT_100} · {@code PRODUCT_198} · {@code PRODUCT_199} 다.
 */
@Getter
@RequiredArgsConstructor
public enum ProductDraftErrorCode {

    /** 경로의 초안 아이디가 없다. */
    NOT_FOUND_DRAFT("PRODUCT_DRAFT_001", "존재하지 않는 초안입니다.", HttpStatus.NOT_FOUND),
    /** 이미 적용한 초안처럼 끝난 초안은 거절할 수 없다. 요청 값은 맞고 초안 상태가 막으므로 409 다. */
    DRAFT_NOT_REJECTABLE("PRODUCT_DRAFT_020", "이미 적용한 초안은 거절할 수 없습니다.", HttpStatus.CONFLICT),
    /** 보관 기간이 지난 초안이다. 새로 만들면 된다. */
    DRAFT_EXPIRED("PRODUCT_DRAFT_022", "보관 기간이 지난 초안입니다. 새 초안을 만들어 주세요.", HttpStatus.CONFLICT),
    /** 같은 초안을 다른 어드민이 동시에 바꿨다(낙관적 락). 다시 조회한 뒤 시도한다. */
    DRAFT_BUSY("PRODUCT_DRAFT_025", "다른 변경과 겹쳤습니다. 다시 확인한 뒤 시도해 주세요.", HttpStatus.CONFLICT),
    /** 이미지 주소가 스토어 미디어 저장소의 공개 주소 아래가 아니거나 형식이 틀렸다. 메시지에 공개 주소를 싣지 않는다. */
    IMAGE_URL_NOT_ALLOWED("PRODUCT_DRAFT_030", "이미지 주소는 스토어 미디어 저장소의 공개 주소여야 합니다.", HttpStatus.BAD_REQUEST),
    /** 미디어 공개 주소({@code store.media.public-base-url})가 설정되지 않아 이미지가 든 초안을 받지 않는다(닫힌 기본값). 서버 설정 문제라 503 이다. */
    MEDIA_STORAGE_NOT_CONFIGURED("PRODUCT_DRAFT_032", "미디어 저장소 주소가 설정되지 않아 이미지를 받을 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    /** 대표 이미지가 두 장 이상이다. */
    THUMBNAIL_DUPLICATED("PRODUCT_DRAFT_034", "대표 이미지는 한 장만 지정할 수 있습니다.", HttpStatus.BAD_REQUEST),
    /** 같은 이미지 주소가 두 번 있다. */
    IMAGE_DUPLICATED("PRODUCT_DRAFT_035", "같은 이미지를 두 번 넣을 수 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
