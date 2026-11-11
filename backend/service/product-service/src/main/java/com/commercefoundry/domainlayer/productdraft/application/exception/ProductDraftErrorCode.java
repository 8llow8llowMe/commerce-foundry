package com.commercefoundry.domainlayer.productdraft.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * AI 상품 초안 오류 코드 ({@code coding-conventions.md} §8). {@code PRODUCT_1xx} 검증 대역이 다 차서 컨텍스트 접두어 {@code PRODUCT_DRAFT_} 를 새로 연다
 * (§8-2, 선례: 룩 {@code LOOK_} · SKU 원가 {@code SKU_COST_}). 업무 오류는 001~, 필드별 검증 코드(101~)는 {@link ProductDraftValidationMessage} 에 둔다.
 * 번호 대역: 0xx 초안 존재 · 상태, 03x 이미지, 04x 생성 internal, 5xx 저장소 장애. 메시지는 운영자가 읽는 문구다 — 설정값 · 내부 상태 이름 · 키 · 주소를 싣지 않는다.
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
    IMAGE_DUPLICATED("PRODUCT_DRAFT_035", "같은 이미지를 두 번 넣을 수 없습니다.", HttpStatus.BAD_REQUEST),
    /**
     * 공개 주소 아래지만 업로드를 마친 상품 미디어 키({@code products/media/**})가 아니다 — 업로드 확정 전 원본 · 상품별 키 · 이관 이미지 · 퍼센트 인코딩 주소(#359 D11).
     * 메시지에 키 · 주소를 싣지 않는다.
     */
    IMAGE_NOT_MANAGED("PRODUCT_DRAFT_031", "미디어 업로드를 마친 이미지만 넣을 수 있습니다. 이미지를 업로드한 뒤 받은 주소를 넣어 주세요.", HttpStatus.BAD_REQUEST),
    /** 관리 키지만 저장소에 객체가 없다. 본문이 가리키는 자원이 없는 것이라 400 이다(선례: {@code PRODUCT_012}). */
    IMAGE_NOT_FOUND("PRODUCT_DRAFT_033", "이미지를 찾을 수 없습니다. 업로드한 이미지인지 다시 확인해 주세요.", HttpStatus.BAD_REQUEST),
    /** 비었거나 장당 상한(5MiB, ai-core {@code AiImage.MAX_BYTES})보다 크거나 JPEG · PNG · WebP 가 아니다. */
    IMAGE_FORMAT_NOT_ALLOWED("PRODUCT_DRAFT_036", "JPEG, PNG, WebP 형식의 5MB 이하 이미지만 넣을 수 있습니다.", HttpStatus.BAD_REQUEST),

    /*
     * 04x — operations 생성 작업 전용 internal(generation-input · generation-images). 운영자에게 나가지 않는다.
     * 4xx 는 같은 초안으로 다시 시도해도 성공하지 않는 사유다. 만료는 어드민과 같은 {@link #DRAFT_EXPIRED}, 없음은 {@link #NOT_FOUND_DRAFT} 를 쓴다.
     */
    /** 생성 입력 기능이 닫혀 있다({@code product-draft.generation-enabled=false}, 기본). 중단 스위치라 다시 시도하지 않는다(선례: {@code SIZE_CHART_DRAFT_011}). */
    GENERATION_DISABLED("PRODUCT_DRAFT_040", "AI 상품 초안 생성은 아직 사용할 수 없습니다.", HttpStatus.BAD_REQUEST),
    /** 거절한 초안이다. */
    GENERATION_DRAFT_REJECTED("PRODUCT_DRAFT_041", "거절한 초안은 생성할 수 없습니다.", HttpStatus.CONFLICT),
    /** 생성 대기 · 생성 중이 아니다(검토 대기 · 생성 실패 · 적용 완료). */
    GENERATION_STATUS_INVALID("PRODUCT_DRAFT_042", "현재 초안 상태에서는 생성할 수 없습니다.", HttpStatus.CONFLICT),
    /** 이미지 원본을 고정하지 않은 초안이다(#359 PR-2a 이전에 만든 초안). 새로 만들어야 한다. */
    GENERATION_INPUT_UNAVAILABLE("PRODUCT_DRAFT_043", "이 초안은 생성 입력을 만들 수 없습니다. 새 초안을 만들어 주세요.", HttpStatus.CONFLICT),
    /** 고정한 이미지가 없어졌거나 바뀌었다(ETag · 크기 · 형식 불일치). 같은 초안으로는 다시 시도해도 안 된다(선례: {@code SIZE_CHART_DRAFT_016}). */
    GENERATION_IMAGE_CHANGED("PRODUCT_DRAFT_044", "초안 이미지가 바뀌어 이 초안으로는 생성할 수 없습니다. 새 초안을 만들어 주세요.", HttpStatus.GONE),
    /** 이미지 순번이 음수거나 초안 이미지 수를 넘는다. */
    GENERATION_IMAGE_NOT_FOUND("PRODUCT_DRAFT_045", "초안에 해당 순번의 이미지가 없습니다.", HttpStatus.NOT_FOUND),

    /** 이미지 저장소를 확인하지 못했다(꺼짐 · 장애). 초안 생성 · internal 이미지 읽기 공통이고, 잠시 뒤 같은 요청을 다시 보내면 된다. */
    IMAGE_STORAGE_UNAVAILABLE("PRODUCT_DRAFT_501", "이미지 저장소를 확인할 수 없습니다. 잠시 뒤 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
