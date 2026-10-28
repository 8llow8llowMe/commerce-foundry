package com.commercefoundry.domainlayer.onboarding.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 운영자 온보딩 오류 코드 ({@code coding-conventions.md} §8, #357).
 *
 * <p>대역 — {@code 00x} 없는 리소스(404), {@code 02x} 버전 · 동시성 충돌(409), {@code 1xx} 요청 검증(400, 필드별 문구는 {@link OnboardingValidationMessage}).
 */
@Getter
@RequiredArgsConstructor
public enum OnboardingErrorCode {

    /** 허용 목록(yml {@code operations.onboarding.guides})에 없는 투어다. FE 는 이 응답을 받으면 서버 기록 없이 로컬 저장으로 돌아간다. */
    GUIDE_NOT_FOUND("ONBOARDING_001", "등록되지 않은 안내입니다.", HttpStatus.NOT_FOUND),

    /** 배포된 설정 버전보다 오래된 가이드 버전이다 — 옛 번들의 화면이다. FE 는 GET 을 한 번 다시 불러오고, 그래도 다르면 번들 갱신 대상이다. */
    STALE_GUIDE_VERSION("ONBOARDING_020", "안내가 새 버전으로 바뀌었습니다. 화면을 새로 불러와 주세요.", HttpStatus.CONFLICT),
    /** 다른 기기 · 탭과 계속 겹쳐 반영하지 못했다 (낙관 락 · 첫 행 유니크 키). 다시 시도하면 합쳐진다. */
    CONCURRENT_UPDATE("ONBOARDING_021", "안내 기록이 그 사이 바뀌었습니다. 다시 시도해 주세요.", HttpStatus.CONFLICT),

    INVALID_REQUEST("ONBOARDING_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    /** 설정 버전보다 큰 버전 — 아직 배포되지 않은 안내다. */
    UNRELEASED_GUIDE_VERSION("ONBOARDING_103", "아직 배포되지 않은 안내 버전입니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_TYPE_INVALID("ONBOARDING_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("ONBOARDING_199", "필수 요청 파라미터가 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
