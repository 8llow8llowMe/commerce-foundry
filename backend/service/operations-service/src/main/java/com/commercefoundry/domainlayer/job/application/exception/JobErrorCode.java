package com.commercefoundry.domainlayer.job.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 백그라운드 작업 오류 코드 ({@code coding-conventions.md} §8).
 *
 * <p>대역 — {@code 00x} 없는 리소스(404) · 등록 입력(400), {@code 02x} 동시성 · 상태 충돌(409).
 *
 * <p>{@code JobExceptionHandler} 는 아직 두지 않는다 — 이 컨텍스트에 응답 진입(컨트롤러)이 없다. 등록은 같은 서비스의 기능 Facade 가 부르고,
 * 그 Facade 의 진입 핸들러가 자기 코드로 번역한다 ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙). 어드민 작업 API(#212 PR-C)가
 * 생기면 핸들러와 요청 검증 코드(1xx)를 함께 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum JobErrorCode {

    NOT_FOUND_JOB("JOB_001", "존재하지 않는 작업입니다.", HttpStatus.NOT_FOUND),
    /** 등록된 핸들러가 없는 종류다 — 호출한 코드의 실수다. */
    UNKNOWN_JOB_TYPE("JOB_002", "등록되지 않은 작업 종류입니다: %s", HttpStatus.BAD_REQUEST),
    /** payload 가 16KiB 를 넘는다. 큰 입력은 아이디만 싣고 실행 시점에 읽는다. */
    JOB_PAYLOAD_TOO_LARGE("JOB_003", "작업 입력이 너무 큽니다.", HttpStatus.BAD_REQUEST),

    /**
     * 같은 멱등 키의 활성 작업이 동시에 등록됐다({@code uk_job_type_active_dedup_key}). {@code JobRegisterUseCase} 는 새 트랜잭션에서 이긴 작업을 읽어 돌려주고,
     * 자기 트랜잭션 안에서 Processor 를 직접 부른 기능 Facade 에는 이 오류가 그대로 간다 — PostgreSQL 이 트랜잭션을 중단시켜 같은 트랜잭션에서 다시 읽을 수 없다.
     * 다시 시도하면 기존 작업을 받는다.
     */
    JOB_DEDUP_CONFLICT("JOB_020", "같은 작업이 동시에 등록됐습니다. 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /** 읽은 뒤 다른 워커 · 요청이 같은 작업을 바꿨다 ({@code @Version}). 워커는 진 쪽을 "다른 워커가 가져감" 으로 보고 넘어간다. */
    JOB_CONFLICT("JOB_021", "작업이 그 사이 바뀌었습니다. 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /** 끝난(SUCCEEDED · FAILED · CANCELLED) 작업을 취소하려 했다. */
    JOB_ALREADY_FINISHED("JOB_022", "이미 끝난 작업입니다.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
