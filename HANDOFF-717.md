# HANDOFF #717 JobWorkerFlowTest.perTypeLimitDefersSerially 간헐 실패 (2026-11-26 17:45)

브랜치 `test/be/717-job-worker-serial-flaky` (origin/develop ac60b3cc). 코드 변경 없음, 분석만. **PR 전에 이 파일을 지운다.**

## 결론 (현재까지)

원인 미확정. 엔진 쪽 단일 인스턴스 슬롯 계산과 테스트 계측은 둘 다 읽어 보면 맞다 — 그래서 "한 워커 안의 경합"이 아니라
**같은 `Executions` 빈을 보는 실행 경로가 둘 이상**이거나 **슬롯이 두 번 반납되는 드문 경로**를 의심한다. 프로덕션 코드는 고치지 않았다.

## 근거

- `JobWorkerFacade.dispatch` 는 `dispatchLock` 안에서 `reserveSlot(type, limit)` 으로 전체·종류별 칸을 함께 잡는다.
  `FLOW_SERIAL` 은 `JobHandlerOptions(null,null,null,1)` → 종류별 상한 1.
- 칸 반납(`releaseSlot`) 위치는 5곳뿐: dispatch 의 claim 예외 2곳 · claim 결과가 CLAIMED 아님 1곳(각각 start 전),
  `start` 의 실행기 거절 catch 1곳, `run` 의 finally 1곳. 정상 경로에서 한 예약당 한 번 반납이다.
- 테스트 핸들러는 `executions.started` → sleep 300ms → finally `executions.finished` 이고, 엔진은 핸들러가 돌아온 뒤에야
  칸을 반납한다 → 계측 카운터 ≤ 엔진 칸 수. 한 엔진 안에서는 2가 나올 수 없다.
- 리스너 `job-runner` 는 concurrency 1, 내장 Kafka 파티션 1.
- lease 만료 회수(lease 2s, heartbeat 200ms) 로 같은 작업의 시도 2가 나와도 칸이 잡혀 있어 TYPE_FULL 로 미뤄진다 → 겹치지 않는다
  (이 경우는 `count(first)==2` 로 드러난다).

## 남은 가설 (다음 세션이 확인)

1. **같은 JVM 에 같은 핸들러를 가진 워커가 둘** — 컨텍스트가 두 번 뜨는 경우(캐시 축출·재생성)인지. `started` 에 `execution.workerId()`·
   스레드 이름·`System.identityHashCode(JobWorkerFacade)` 를 기록해 실패 시 메시지에 찍게 계측을 늘리면 바로 갈린다.
2. **`start` 의 `jobWorkerTaskExecutor.execute` 가 작업을 실제로 넣은 뒤 예외** → catch 에서 칸 반납 + 실행 스레드의 finally 반납 = 이중 반납.
   실행기(`JobWorkerExecutorConfig`)는 core=max=`worker.concurrency`, 큐 0, 기본 AbortPolicy 라 거절은 제출 전에 던진다 →
   이 가설은 약하다(정상 JDK 경로에선 이중 반납 없음).
3. 루트 `build.gradle:133` `maxParallelForks = 2` — 포크 간 공유 자원(같은 H2 이름 `operations-flow`, 포트)이 섞이는지.

### 가설 1 보강 (감독 세션 힌트: 컨텍스트 캐시로 다른 클래스의 워커가 같은 DB 작업을 집어감)

- 17:35 확인: operations 의 `@SpringBootTest` 24개 중 `commerce.messaging.enabled=true` 는 4개
  (JobWorkerFlowTest · DltAlertFlowTest · DltAlertKafkaPostgresTest · DltAlertActivationTest), `@EmbeddedKafka` 는 2개(JobWorkerFlowTest · DltAlertFlowTest).
- H2 이름 `operations-flow` 는 JobWorkerFlowTest 만 쓴다. 나머지는 `application-test.yml` 의 `operations-context` → 다른 컨텍스트의
  lease 만료 판정이 이 테스트의 작업 행을 볼 수는 없다. 다만 **DltAlertFlowTest 컨텍스트에도 job 리스너·워커 빈이 뜨는지**,
  그 컨텍스트가 같은 내장 브로커(JVM 안 공유 여부)·같은 컨슈머 그룹 `<ns>.operations.job-runner` 를 쓰는지는 아직 확인 못 했다.
  같은 브로커·그룹이면 파티션 재배정 순간 한쪽이 `FLOW_SERIAL` 을 모르는 종류로 DLT 에 보내는 쪽이지 이중 실행은 아니다
  → 이중 실행을 만들려면 **SERIAL 핸들러를 가진 컨텍스트가 둘**이어야 한다. 계측(워커 id·스레드·컨텍스트 id)으로 확정할 것.
- 확인 방법: `Executions.started` 에 `execution.workerId()`, `Thread.currentThread().getName()`, `ApplicationContext#getId()` 를 함께 기록하고,
  `maxConcurrent` 실패 메시지에 겹친 두 기록을 찍는다. 스케줄러·워커 on/off 속성(`operations.jobs.*`)이 컨텍스트별로 어떤지도 본다.

## 재현

`./gradlew :service:operations-service:cleanTest :service:operations-service:test --tests "*JobWorkerFlowTest"` 3회(17:34, develop ac60b3cc 그대로):
3회 모두 11건 통과, 실패 0 — **단독 실행으로는 재현 안 됨**. 이슈 보고는 모듈 전체 테스트 중 실패였으므로
다음엔 `:service:operations-service:test` 전체(다른 클래스와 같은 JVM·`maxParallelForks=2`)로 반복해 본다.

## 다음 프롬프트

> /dev-orchestrator #717 이어서. HANDOFF-717.md 의 가설 1→2→3 순서로, 먼저 테스트 계측에 workerId·스레드·워커 인스턴스 식별을 더하고
> 클래스를 반복 실행해 원인을 가른다. 엔진 이중 반납이면 회귀 테스트 + 수정(이슈 댓글 먼저), 테스트 문제면 계측만 고친다. 끝나면 HANDOFF 삭제 후 /pr.
