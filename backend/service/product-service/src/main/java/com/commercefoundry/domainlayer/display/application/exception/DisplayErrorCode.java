package com.commercefoundry.domainlayer.display.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 메인 전시 오류 코드 ({@code coding-conventions.md} §8). 접두어는 서비스 단위 {@code PRODUCT_} 이고 전시는 046~ 을 쓴다
 * (001~ 상품 · 02x · 03x 재고 · 040~045 어드민 카테고리 — {@code ProductErrorCode}). 새 코드를 더할 때 두 enum 의 번호가 겹치지 않게 한다.
 * 필드별 검증 코드(140~)는 {@link DisplayValidationMessage} 에 둔다.
 *
 * <p>요청 검증 실패(PRODUCT_100 · 1xx · 198 · 199)는 이 enum 이 아니라 서비스 공용 검증 처리({@code ProductExceptionHandler})가 봉투로 바꾼다.
 */
@Getter
@RequiredArgsConstructor
public enum DisplayErrorCode {

    /** 경로의 섹션 아이디가 없다. */
    NOT_FOUND_DISPLAY_SECTION("PRODUCT_046", "존재하지 않는 전시 섹션입니다.", HttpStatus.NOT_FOUND),
    /** 섹션 유형이 받지 않는 아이템 유형이다 — 첫 인자 섹션 유형, 둘째 아이템 유형. */
    DISPLAY_ITEM_TYPE_NOT_ALLOWED("PRODUCT_047", "%s 섹션에는 %s 아이템을 둘 수 없습니다.", HttpStatus.BAD_REQUEST),
    /** 섹션 유형이 정한 아이템 수 범위를 벗어났다 — 섹션 유형, 아이템 유형, 하한, 상한. */
    DISPLAY_ITEM_COUNT_OUT_OF_RANGE("PRODUCT_048", "%s 섹션의 %s 아이템은 %s~%s개여야 합니다.", HttpStatus.BAD_REQUEST),
    /** 한 섹션에 같은 상품이 두 번 있다. */
    DISPLAY_PRODUCT_DUPLICATED("PRODUCT_049", "한 섹션에 같은 상품을 두 번 넣을 수 없습니다.", HttpStatus.BAD_REQUEST),
    /** 본문의 상품 아이디가 없는 상품이다(숨긴 상품은 있는 상품이다). 경로 리소스가 아니라 본문 참조라 400 이다 — 메시지에 없는 아이디를 모두 싣는다. */
    DISPLAY_PRODUCT_NOT_FOUND("PRODUCT_050", "존재하지 않는 상품이 있습니다: %s", HttpStatus.BAD_REQUEST),
    /** 노출 종료 시각이 시작 시각보다 뒤가 아니다. */
    DISPLAY_PERIOD_INVALID("PRODUCT_051", "노출 종료 시각은 시작 시각보다 뒤여야 합니다.", HttpStatus.BAD_REQUEST),
    /** 링크 주소가 '/' 로 시작하는 상대 경로나 https 절대 주소가 아니다. */
    DISPLAY_LINK_URL_INVALID("PRODUCT_052", "링크 주소는 '/' 로 시작하는 상대 경로나 https 주소여야 합니다.", HttpStatus.BAD_REQUEST),
    /** 링크 문구만 있고 주소가 없다. */
    DISPLAY_LINK_LABEL_WITHOUT_URL("PRODUCT_053", "링크 문구는 링크 주소와 함께 보내야 합니다.", HttpStatus.BAD_REQUEST),
    /** 미디어 주소가 스토어 미디어 저장소의 공개 주소 아래가 아니거나 형식이 틀렸다. 메시지에 공개 주소를 싣지 않는다. */
    DISPLAY_MEDIA_URL_NOT_ALLOWED("PRODUCT_054", "미디어 주소는 스토어 미디어 저장소의 공개 주소여야 합니다.", HttpStatus.BAD_REQUEST),
    /**
     * 미디어 공개 주소({@code store.media.public-base-url})가 설정되지 않아 미디어가 있는 쓰기를 받지 않는다(닫힌 기본값).
     * 요청이 아니라 서버 설정 문제라 400 이 아니라 503 이다. 미디어가 없는 섹션(상품 픽 · 미디어 없는 스토리)의 쓰기는 된다.
     */
    DISPLAY_MEDIA_STORAGE_NOT_CONFIGURED("PRODUCT_055", "미디어 저장소 주소가 설정되지 않아 이미지 · 영상을 등록할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    /** 섹션 수 상한에 닿았다. 요청 값은 올바르고 전시 상태가 막으므로 409 다 — 하나를 지우면 다시 된다. */
    DISPLAY_SECTION_LIMIT_EXCEEDED("PRODUCT_056", "전시 섹션은 최대 %s개까지 만들 수 있습니다.", HttpStatus.CONFLICT),
    /** 순서 변경 본문이 현재 섹션 아이디 집합과 다르다 — 누락 · 중복 · 모르는 아이디. 다시 조회한 뒤 보낸다. */
    DISPLAY_SECTION_ORDER_MISMATCH("PRODUCT_057", "순서 변경에는 현재 전시 섹션 아이디를 빠짐없이 한 번씩 보내야 합니다.", HttpStatus.BAD_REQUEST),
    /** 다른 어드민의 전시 쓰기가 전시 잠금을 쥐고 있어 시간 상한(5초) 안에 끝나지 않았다. 요청끼리 겹친 것이라 503 이 아니다. */
    DISPLAY_BUSY("PRODUCT_058", "다른 전시 변경을 처리 중입니다. 다시 시도해 주세요.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
