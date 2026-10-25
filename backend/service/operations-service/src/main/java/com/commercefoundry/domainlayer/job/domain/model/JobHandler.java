package com.commercefoundry.domainlayer.job.domain.model;

/**
 * 작업 종류 하나의 실행 코드. 기능 컨텍스트의 {@code *JobProcessor} 가 구현하고 빈으로 등록하면 워커가 {@link #type()} 으로 찾아 부른다
 * ({@code architecture-guide.md} "백그라운드 작업 실행").
 *
 * <ul>
 *   <li><b>attempt 단위로 멱등이다.</b> 같은 작업이 다시 실행될 수 있다 — 재시도, lease 를 잃은 뒤 다른 워커의 회수, 종료 직전 프로세스가 죽은 경우.
 *       같은 attempt 를 두 번 실행해도 결과가 같아야 한다. 결과 파일 키처럼 attempt 마다 달라지는 것은 {@link JobExecution#attempt()} 를 쓴다.</li>
 *   <li><b>트랜잭션 밖에서 불린다.</b> DB 쓰기가 필요하면 자기 Processor 의 {@code @Transactional} 메서드로 짧게 끊는다 — 오래 걸리는 작업이
 *       커넥션을 쥐고 있지 않게.</li>
 *   <li>오래 걸리면 {@link JobExecution#checkpoint} 를 자주 부른다. 취소 요청 · lease 상실 · 실행 시간 초과를 그 자리에서 알린다.</li>
 *   <li>payload · 결과 · 오류 메시지 · 진행 메시지에 <b>개인정보 원문을 두지 않는다.</b> 아이디만 싣고 실행 시점에 원본 서비스(internal API)에서 읽는다.
 *       어드민 작업 API 는 진행 문구만 운영자에게 보여 준다 — 진행 문구는 운영자가 읽는 업무 언어로 쓴다(예: "3/10 구간 집계 중"). 개인정보가 꼭 필요한 결과는 결과 파일(링크 발급이 감사 대상)로 낸다.</li>
 *   <li>어드민 사용자는 개발자가 아니라 쇼핑몰 운영자다 ({@code api-design-guide.md} §2-8) — {@link #displayName()} · {@link #failureNotice(String)} 는 운영자가 읽는 말로 쓴다.</li>
 * </ul>
 */
public interface JobHandler {

    /** 작업 종류. {@code [A-Z0-9_]{1,64}} 이고 서비스 안에서 유일하다 — 어긋나면 기동하지 않는다. */
    String type();

    /** 어드민 작업 목록에 보이는 작업 이름(예: "월 매출 리포트 내보내기"). 정하지 않으면 종류 코드가 그대로 보인다. */
    default String displayName() {
        return type();
    }

    /**
     * 이 종류가 던진 실패 코드({@link JobExecutionException#getErrorCode()})를 운영자가 읽을 안내 문구로 바꾼다(예: "파일 형식을 확인해 주세요").
     * 모르는 코드면 null — 엔진 공통 문구가 대신 나간다. 오류 원문 · 코드 · 개인정보를 문구에 넣지 않는다.
     */
    default String failureNotice(String errorCode) {
        return null;
    }

    /** 이 종류의 실행 옵션. 비운 칸은 {@code operations.jobs.*} 기본값을 쓴다. */
    default JobHandlerOptions options() {
        return JobHandlerOptions.defaults();
    }

    /**
     * 작업을 실행한다.
     *
     * @return 결과. 결과 JSON 이 없으면 {@link JobOutcome#empty()}
     * @throws JobExecutionException 업무상 실패 — 오류 코드와 재시도 여부를 싣는다
     * @throws JobCancelledException {@link JobExecution#checkpoint} 가 던진 중단 신호. 잡지 말고 그대로 내보낸다
     */
    JobOutcome execute(JobExecution execution);
}
