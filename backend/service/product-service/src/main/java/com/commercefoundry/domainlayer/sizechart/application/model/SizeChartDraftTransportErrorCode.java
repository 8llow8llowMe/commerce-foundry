package com.commercefoundry.domainlayer.sizechart.application.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SizeChartDraftTransportErrorCode {
    NOT_FOUND("SIZE_CHART_DRAFT_001", "초안이 없습니다.", HttpStatus.NOT_FOUND),
    INPUT_CONFLICT("SIZE_CHART_DRAFT_002", "초안 입력이 기존 등록 정보와 다릅니다.", HttpStatus.CONFLICT),
    BINDING_CONFLICT("SIZE_CHART_DRAFT_003", "초안과 작업의 연결이 다릅니다.", HttpStatus.CONFLICT),
    INVALID_STATUS("SIZE_CHART_DRAFT_004", "현재 초안 상태에서는 처리할 수 없습니다. 상태를 새로 확인해 주세요.", HttpStatus.CONFLICT),
    TEMPLATE_CHANGED("SIZE_CHART_DRAFT_006", "템플릿이 변경되었습니다. 새 초안을 생성해 주세요.", HttpStatus.CONFLICT),
    CHART_CHANGED("SIZE_CHART_DRAFT_007", "상품 사이즈표가 변경되었습니다. 현재 표를 확인한 뒤 새 초안을 생성해 주세요.", HttpStatus.CONFLICT),
    APPLY_CONFLICT("SIZE_CHART_DRAFT_008", "이미 다른 내용으로 적용한 초안입니다. 상품 사이즈표를 확인해 주세요.", HttpStatus.CONFLICT),
    WARNING_ACK_REQUIRED("SIZE_CHART_DRAFT_009", "남은 경고를 검토하고 확인한 뒤 다시 적용해 주세요.", HttpStatus.CONFLICT),
    EXPIRED("SIZE_CHART_DRAFT_005", "초안이 만료되었습니다.", HttpStatus.GONE),
    UNSAFE_INPUT("SIZE_CHART_DRAFT_030", "안전한 치수 텍스트만 사용할 수 있습니다.", HttpStatus.BAD_REQUEST),
    RESULT_EXPIRED("SIZE_CHART_DRAFT_010", "작업 결과 보관 기간이 만료되었습니다.", HttpStatus.GONE),
    IMAGE_INPUT_DISABLED("SIZE_CHART_DRAFT_011", "이미지로 사이즈표 초안을 만드는 기능은 아직 사용할 수 없습니다. 치수 텍스트로 만들어 주세요.", HttpStatus.BAD_REQUEST),
    IMAGE_SOURCE_NOT_FOUND("SIZE_CHART_DRAFT_012", "이 상품에서 선택한 이미지를 찾을 수 없습니다. 이미지 목록을 새로 확인해 주세요.", HttpStatus.NOT_FOUND),
    IMAGE_SOURCE_NOT_ELIGIBLE("SIZE_CHART_DRAFT_013", "이 이미지는 사이즈표 초안에 사용할 수 없습니다. 상품 이미지로 다시 등록한 뒤 선택해 주세요.", HttpStatus.BAD_REQUEST),
    IMAGE_FORMAT_NOT_ALLOWED("SIZE_CHART_DRAFT_014", "JPEG, PNG, WebP 형식의 5MB 이하 이미지만 사용할 수 있습니다.", HttpStatus.BAD_REQUEST),
    IMAGE_APPROVAL_MISMATCH("SIZE_CHART_DRAFT_015", "확인한 뒤 이미지가 바뀌었습니다. 이미지를 다시 확인하고 승인해 주세요.", HttpStatus.CONFLICT),
    /** internal 생성 이미지 읽기 전용. 같은 초안으로는 다시 시도해도 성공할 수 없다. */
    IMAGE_INPUT_CHANGED("SIZE_CHART_DRAFT_016", "승인한 이미지가 바뀌어 이 초안으로는 생성할 수 없습니다. 새 초안을 만들어 주세요.", HttpStatus.GONE),
    REGISTRATION_UNAVAILABLE("SIZE_CHART_DRAFT_501", "작업 등록 여부를 확인할 수 없습니다. 같은 초안으로 재시도하세요.", HttpStatus.SERVICE_UNAVAILABLE),
    RESULT_UNAVAILABLE("SIZE_CHART_DRAFT_502", "작업 결과를 조회할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    INVALID_CONTRACT("SIZE_CHART_DRAFT_503", "생성 결과를 안전하게 확인할 수 없습니다. 잠시 뒤 다시 확인해 주세요.", HttpStatus.BAD_GATEWAY),
    IMAGE_STORAGE_UNAVAILABLE("SIZE_CHART_DRAFT_504", "이미지 저장소를 확인할 수 없습니다. 잠시 뒤 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
