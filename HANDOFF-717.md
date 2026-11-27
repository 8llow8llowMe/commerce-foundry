# HANDOFF #717 JobWorkerFlowTest.perTypeLimitDefersSerially 간헐 실패 (2026-11-27 09:30 갱신)

브랜치 `test/be/717-job-worker-serial-flaky` (develop 993e0ab6 위로 rebase). **PR 전에 이 파일을 지운다.**

## 결론 (현재까지)

원인 미확정, 재현도 아직 못 했다. 엔진 슬롯 계산과 테스트 계측을 읽어 보면 둘 다 맞다.
프로덕션 코드는 고치지 않았다. 이번에 한 일은 **실패할 때 원인이 보이도록 테스트 계측을 늘린 것**이다.

## 11-27 정정 — 이전 재현 결과는 무효

- `cleanTest` 를 붙여도 test 태스크가 **빌드 캐시에서 복원(FROM-CACHE)** 된다. 11-26 의 "단독 3회 통과"(회당 약 10초)와
  11-27 모듈 2·3회차(회당 약 45초)는 실제로 돌지 않았다. 반복 실행할 때는 **`--no-build-cache`** 를 반드시 붙인다.
- 실제로 돈 것은 11-27 모듈 1회차 하나다. 계측을 넣은 상태로 7m43s 동안 1,829건 통과, JobWorkerFlowTest 11건 통과.
- `--no-build-cache` 로 모듈 3회를 다시 돌리던 중 PC 메모리 부족으로 Claude Code 가 백그라운드 실행을 중단시켰다(09:28, 완료된 회차 없음).
- 11-26 의 가설 3(`maxParallelForks = 2`)은 틀렸다. 루트 `build.gradle` 은 **CI 의 product-service 에서만** 2 fork 를 쓴다.
  operations 는 로컬·CI 모두 단일 fork 이고, `junit-platform.properties` 가 없어 JUnit 병렬 실행도 없다.

## 근거

- `JobWorkerFacade` 는 `@Service` 하나뿐이다(`new JobWorkerFacade` 는 단위 테스트 `JobWorkerFacadeTest` 뿐).
  `dispatch` 는 리스너 `job-runner`(concurrency 1, 파티션 1)에서만 불린다.
- `dispatch` 는 `dispatchLock` 안에서 `reserveSlot(type, limit)` 으로 전체 칸과 종류별 칸을 함께 잡는다.
  `FLOW_SERIAL` 은 `JobHandlerOptions(null,null,null,1)` 이라 종류별 상한이 1이다.
- 칸 반납(`releaseSlot`)은 5곳이고, 어느 경로든 예약 한 번에 한 번만 반납한다:
  claim 예외 2곳 · CLAIMED 가 아닌 결과 1곳 · 실행기 거절 1곳 · `run` 의 finally 1곳.
  실행기(`JobWorkerExecutorConfig`)는 core=max, 큐 0, AbortPolicy 라 거절 예외는 작업을 넣기 전에 난다.
- heartbeat 가 멈추는 경로(LOST·취소, RUN_TIMEOUT)는 실행 스레드에 멈춤 신호만 주고, 칸은 `run` 의 finally 에서 반납한다.
- 테스트 핸들러는 `started` → 300ms 대기 → finally `finished` 순서다. 엔진은 핸들러가 돌아온 뒤에 칸을 반납하므로
  계측 카운터는 엔진 칸 수를 넘을 수 없다.
- 다른 컨텍스트의 워커일 가능성도 낮다. H2 `operations-flow` 는 이 클래스만 쓰고, `FLOW_SERIAL` 핸들러는
  이 클래스의 중첩 `@TestConfiguration` 에만 있다. 다른 컨텍스트가 이 신호를 받으면 모르는 종류라 DLT 로 보낸다(이중 실행이 아니다).

## 이번 커밋의 계측 (JobWorkerFlowTest)

`Executions` 가 실행 구간마다 jobId · attempt · 스레드 이름 · `ApplicationContext` id@identity · 시작 시 running · 시작/종료 nanoTime 을
남긴다. `maxConcurrent` 단언이 깨지면 `describe(SERIAL)` 로 두 구간이 메시지에 찍힌다. 이것으로 갈린다:
- 컨텍스트 id 가 다르면 → 컨텍스트가 둘(테스트 구성 문제)
- 같은 컨텍스트에서 시도가 다르거나 같은 작업이면 → 엔진 쪽(감독 세션에 먼저 보고한 뒤 수정)
- 구간이 실제로는 겹치지 않으면 → 계측 경합

## 다음 할 일

1. 메모리가 넉넉할 때 `./gradlew :service:operations-service:cleanTest :service:operations-service:test --no-build-cache --tests "*JobWorkerFlowTest"`
   를 여러 번 돌린다. 클래스 하나라 가볍다. 실패하면 메시지에 찍힌 구간으로 위 분기를 판정한다.
2. 그래도 안 나오면 모듈 전체(`--no-build-cache`)를 반복한다. CI 실패 로그가 있으면 그쪽이 더 빠르다.
3. 원인이 정해지면: 테스트 문제면 결정적으로 고치고, 엔진 문제면 근거를 감독 세션에 먼저 보고한다.
   계측만으로 PR 을 낼지는 감독 세션이 정한다.

## 다음 프롬프트

> #717 이어서. HANDOFF-717.md "다음 할 일" 1→2 순서로 `--no-build-cache` 를 붙여 반복 재현한다(메모리 확인).
> 실패 메시지의 실행 구간으로 원인을 판정한 뒤, 테스트 문제면 고쳐서 /pr, 엔진 문제면 근거를 감독 세션에 먼저 보고한다.
