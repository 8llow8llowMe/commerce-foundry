# AI Provider 설정과 경계 (#468)

## PR-A: 기존 Claude 생성 경계 분리

서비스의 호출 입구는 `AiClient`, 생성 입구는 `AiClients.create`다. 기존 공개 메서드와 `ai.*` 설정은 그대로이며 기본 구현은 Claude다.
`AiProvider`는 ai-core 패키지 내부 생성 SPI다. 서비스가 SDK나 Provider를 직접 만들지 않는다.

생성 순서는 설정 검증 → 계량 인터셉터 개수 검증 → disabled 분기 → 마스커 생성 → Provider 생성이다.
AI가 꺼졌거나 설정이 잘못됐으면 Provider를 부르지 않는다. 설정 오류에는 값이나 키를 싣지 않는다.
Provider에는 검증된 설정, 공통 `PiiMasker`, 기존 계량 인터셉터를 전달한다.

`ClaudeAiProvider`는 기존 SDK 빌더의 명시적 키, 본문 로그 OFF, 재시도와 연결·읽기·쓰기·요청 타임아웃, 선택적 base URL을 보존한다.
실제 호출의 길이 제한·마스킹·동시 상한·사용량 계량·오류 변환은 기존 `ClaudeAiClient`에 그대로 둔다.
PR-A는 기존 클라이언트 자체를 반환했다. 현재는 Provider 라우터가 생성된 모든 클라이언트를 소유하며 종료 시 각 SDK 자원을 닫는다.

## OpenAI Responses와 기능별 선택

`ai.provider`는 `anthropic`(기본값) 또는 `openai`다. `ai.features.<기능>.provider`를 지정하면 해당 기능만 바뀐다.
기능의 `model`이 없으면 선택된 Provider의 기본 모델을 쓴다. 사용되는 Provider만 키·URL을 검사하고 클라이언트를 만든다.
AI가 꺼졌으면 키·모델을 요구하지 않는다. 모든 Provider는 하나의 공정 세마포어와 같은 계량 인터셉터를 공유한다.

| 설정 | Anthropic | OpenAI |
|------|-----------|--------|
| 키 | `ai.api-key` / `AI_API_KEY` | `ai.openai.api-key` / `AI_OPENAI_API_KEY` |
| 모델 | `ai.default-model` | `ai.openai.model` / `AI_OPENAI_MODEL` (명시 필수) |
| 선택 URL | `ai.base-url` | `ai.openai.base-url` |

공식 OpenAI Java SDK 4.96.0으로 단일 텍스트·이미지, strict JSON 구조화 출력, 다중 턴·도구, 스트리밍을 지원한다.
`store=false`와 `include=[reasoning.encrypted_content]`를 명시한다. OpenAI 출력 토큰 상한은 최소 16이다.
키를 명시하고 SDK 본문 로그를 끈다. HTTPS와 명시적으로 허용된 테스트 루프백 HTTP만 사용한다.
도구 호출은 item ID가 아닌 `call_id`를 사용하며, 미등록 도구와 잘린·스키마에 맞지 않는 인자는 거절한다. 도구 실행은 호출자 책임이다.
reasoning 블록은 `Opaque("openai", ...)`로 서버 이력에 보관한다. 다른 Provider 블록은 전송하지 않는다.
서버가 보관한 이력만 사용하며 브라우저가 보낸 assistant·도구·Provider 블록을 받지 않는다.

전송 텍스트·도구 설명·스키마 값·인자·결과를 가린다. JSON 키를 가려야 하면 구조를 바꾸지 않고 호출을 거절한다.
구조화 출력 스키마도 입력 길이 상한·마스킹·계량에 포함한다. enum·필수 키 같은 제약을 가려야 하면 역직렬화 계약을 보존하도록 전송 전에 거절한다.
입력 상한 → 마스킹 → 파라미터 검사 → 계량 before → 자리 획득 → SDK → 사용량 캡처 → 결과 판정 → 자리 반환 → after 순서다.
before 실패는 HTTP를 보내지 않는다. before가 성공한 호출은 after가 정확히 한 번이며, after 실패는 호출 결과를 바꾸지 않는다.
입력 사용량은 총 입력에서 캐시 입력을 뺀 값, cache-read는 캐시 입력, cache-write는 0이다. 출력은 reasoning 포함 총 출력 그대로다.
스트리밍은 텍스트 delta만 전달한다. terminal 이벤트 없는 EOF는 실패다. 취소는 받은 텍스트·빈 도구 목록·null assistant 메시지다.
시작 시 취소는 `NOT_SENT`, 사용량을 받은 뒤 거절·파싱 실패는 `UNUSABLE`이다. SDK 오류 본문과 원인은 공개 예외에 싣지 않는다.

## 운영 단가와 원장 Provider 식별

operations는 선택된 모든 OpenAI 기본·기능 모델에 명시적 단가를 요구한다. `operations.ai.pricing.providers.<anthropic|openai>.models.<모델>`로
동일한 모델 ID도 Provider별 단가를 설정할 수 있다. 선택 순서는 Provider의 정확한 모델 → 기존 `operations.ai.pricing.models`의 정확한 모델 →
해당 Provider와 기존 공통 단가 전체의 항목별 최댓값이다. 다른 Provider의 단가는 최고 단가 계산에도 포함하지 않는다.
모든 사용 Provider에는 자체 또는 공통 단가 풀이 있어야 하며, OpenAI의 기동 검사는 최고 단가 대체만으로 통과하지 않는다.
기존 공통 단가 설정과 Claude의 최고 단가 대체는 호환된다. 실제 모델과 단가는 운영자가 공식 가격을 확인해 설정한다.

신규 호출은 예약 시 선택한 `anthropic`/`openai`를 `AiCallContext`부터 `operations.ai_usage_call.provider`까지 전달한다.
Provider는 정산·회수에서 바꾸지 않는다. 과거 행의 NULL은 미기록이며 모델 이름으로 추정하거나 일괄 채우지 않는다.
과거 NULL 행의 비용 계산은 기존 공통 단가만 사용한다. 예전 9·12인자 계량 생성자와 예약 명령·pending 호출은 Anthropic으로 호환된다.
Provider별 단가만 사용하는 설정으로 전환하더라도, 과거 Provider가 NULL인 PENDING 예약의 정산이 끝날 때까지 기존 공통 단가를 유지한다.
이를 먼저 지우면 해당 호출의 실측 정산이 실패하고 회수 작업에서 예상 비용으로 ABANDONED 처리될 수 있다.

배포 전 [운영 delta DDL](../service/operations-service/src/test/resources/ddl/ai-usage-provider.sql)을 DB에 먼저 적용한 뒤 애플리케이션을 배포한다.
`application.yml`의 `hibernate.default_schema=operations`와 PostgreSQL 17 Hibernate 생성 스냅숏에 근거한 nullable varchar(32) 컬럼·comment만 추가한다.
기본값·NOT NULL·인덱스·backfill은 없다. 실제 운영 DB 적용과 유료 API 호출은 검증 범위에 포함하지 않는다.
실제 OpenAI SDK 루프백 → 공통 계량 → H2 원장 통합시험에서 단일·구조화·도구·스트리밍, 거절 과금, 보내기 전 취소를 검증한다.
PostgreSQL 원장 시험도 Provider 보존과 과거 NULL 로드·정산·회수를 검증하며 Docker/CI에서 실행한다. FE API 계약 변경은 없다.

자동 Provider 전환은 하지 않는다(#468 확정 결정). 같은 Provider의 SDK 재시도 이후에는 기존 `AiException`으로 실패를 전달한다.
새 Provider도 단일·구조화·대화·도구·스트리밍 모든 경로에서 개인정보 마스킹과 계량을 적용해야 한다.

## 이미지 요청 크기 — 기능 쪽 합계 상한 (#359)

ai-core 는 이미지 **장수**(`AiImage.MAX_COUNT` 8)와 **장당 크기**(`AiImage.MAX_BYTES` 5MiB)만 막고 요청 전체 크기는 보지 않는다. 8장 × 5MiB(40MiB)는 base64 로
약 53MB 라 Messages API 요청 본문 상한 32MB 를 넘는다(넘으면 413 — 보낸 뒤 실패, 재시도해도 같다). 그래서 이미지를 싣는 기능은 **원본 바이트 합계 상한을 스스로 둔다.**

| 기능 | 합계 상한 | 검사 위치 · 시점 | 넘으면 |
|------|-----------|------------------|--------|
| `product-draft` (작업 `PRODUCT_DRAFT_GENERATE`) | 20MiB (`20 * 1024 * 1024`) | `ProductDraftGenerationValidationProcessor` — 바이트를 받기 전, product 생성 입력의 `byteLength` 합계 | 작업 실패 `PRODUCT_DRAFT_IMAGES_TOO_LARGE`(재시도 불가) |

근거: 20MiB 를 base64 로 바꾸면 4/3 배로 약 26.7MB 이고, 32MB 에서 남는 약 5MB 를 시스템 · 사용자 프롬프트 · 구조화 출력 스키마 · JSON 여유분으로 둔다.
사이즈표 IMAGE(`size-chart`)는 아직 operations 가 IMAGE 를 거절하므로 해당 없다 — 열 때 같은 방식으로 상한을 정한다.
PR-3 할 일: product 초안 등록 검증에도 같은 20MiB 합계를 둬 운영자가 생성 전에 알게 한다(지금은 product 가 장당 5MiB · 8장만 본다).
