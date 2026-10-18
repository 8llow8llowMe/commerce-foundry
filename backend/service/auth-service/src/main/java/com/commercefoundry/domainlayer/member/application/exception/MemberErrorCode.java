package com.commercefoundry.domainlayer.member.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 회원 · 인증 오류 코드 ({@code coding-conventions.md} §8). 필드별 검증 코드(101~)는 {@link MemberValidationMessage} 에 둔다.
 *
 * <p>접두어는 서비스 단위 {@code AUTH_} 다 — 로그인 · 토큰(#4 A3 · A4)도 같은 코드 공간을 쓴다. 001~009 는 회원, 010~ 은 토큰이다.
 * 로그인 실패는 401 이 아니라 400 이다 — 401 은 토큰(access · refresh) 거절에만 쓴다 (#4 결정 5).
 */
@Getter
@RequiredArgsConstructor
public enum MemberErrorCode {

    /** 이메일이 없거나 · 비밀번호가 틀렸거나 · 고객 로그인에 관리자 계정이 왔다. 어느 쪽인지 밝히지 않는다 (#4 A3). */
    LOGIN_FAILED("AUTH_001", "이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    DUPLICATE_EMAIL("AUTH_002", "이미 가입된 이메일입니다.", HttpStatus.CONFLICT),
    NOT_FOUND_MEMBER("AUTH_003", "존재하지 않는 회원입니다.", HttpStatus.NOT_FOUND),
    /** 같은 회원을 다른 요청이 먼저 고쳤다 (낙관적 락). 다시 읽고 다시 보내면 된다. */
    MEMBER_UPDATE_CONFLICT("AUTH_004", "회원 정보가 다른 요청으로 먼저 변경되었습니다. 다시 시도해 주세요.", HttpStatus.CONFLICT),

    /** 코드 접두어 없는 검증 메시지 · 역직렬화 실패(깨진 JSON, 본문 없음)의 폴백. */
    INVALID_REQUEST("AUTH_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_TYPE_INVALID("AUTH_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("AUTH_199", "필수 요청 파라미터가 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
