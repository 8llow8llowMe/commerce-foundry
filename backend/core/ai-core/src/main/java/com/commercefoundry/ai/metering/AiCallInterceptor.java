package com.commercefoundry.ai.metering;

/**
 * Claude API 호출 한 번의 앞뒤에 끼는 계량 SPI. 사용량 원장 · 월 한도를 가진 서비스가 빈으로 하나 등록한다 ({@code bootstrap-conventions.md} AI).
 *
 * <p>순서가 고정이다 — 길이 검사 → 마스킹 → {@link #before} → 동시 상한 자리 얻기 → 호출(SDK 재시도 포함) → 자리 반납 → {@link #after}.
 *
 * <ul>
 *   <li>{@link #before} 가 {@code AiException} 을 던지면 그대로 호출자에게 간다 — 자리를 잡지 않고 HTTP 요청도 없다. 한도 초과는
 *       {@code BUDGET_EXCEEDED}, 원장 장애는 {@code METERING_FAILED} 로 던진다. 다른 예외는 {@code METERING_FAILED} 로 바뀐다.</li>
 *   <li>{@link #before} 가 돌아왔으면 {@link #after} 는 정확히 한 번 불린다 — 결과가 무엇이든({@link AiCallOutcome.Status}). 길이 · 마스킹에서
 *       막혀 {@code before} 까지 오지 않은 호출에는 둘 다 불리지 않는다.</li>
 *   <li>{@link #after} 는 동시 상한 자리를 반납한 뒤에 불린다 — 원장 기록이 느려도 다른 호출을 막지 않는다. 던진 예외는 클라이언트가 잡아 WARN(타입만)으로
 *       남기고 결과 · 원래 예외를 바꾸지 않는다. 기록을 놓치면 예약은 회수 스케줄러가 정리한다.</li>
 * </ul>
 *
 * <p>프로세스에 하나만 둔다 — 둘 이상이면 기동이 멈춘다(앞선 {@code before} 성공 뒤 다음 {@code before} 가 실패할 때의 보상 순서를 정하지 않으려고).
 * 구현은 스레드 안전해야 한다. 컨텍스트 · 결과에는 원문 · 개인정보가 없다.
 */
public interface AiCallInterceptor {

    /**
     * 보내기 전. 한도를 예약하고 나중에 {@link #after} 가 받을 핸들을 돌려준다.
     *
     * @throws com.commercefoundry.ai.client.AiException 보내면 안 될 때({@code BUDGET_EXCEEDED} · {@code METERING_FAILED})
     */
    AiCallHandle before(AiCallContext context);

    /**
     * 끝난 뒤(성공 · 실패 · 타임아웃 · 보내지 못함). {@code handle} 은 {@link #before} 가 돌려준 그대로다.
     *
     * <p>스레드의 인터럽트 플래그가 켜진 채 불릴 수 있다(자리 대기 중 인터럽트 → {@code NOT_SENT}). 예약 해제는 멱등이고, 인터럽트 상태에서도
     * 끝나게(인터럽트에 반응하는 대기 · 락 없이) 구현한다 — 클라이언트는 플래그를 지우지 않는다.
     */
    void after(AiCallHandle handle, AiCallOutcome outcome);
}
