# AI Provider 확장 경계 (#468)

## PR-A: 기존 Claude 생성 경계 분리

서비스의 호출 입구는 `AiClient`, 생성 입구는 `AiClients.create`다. 기존 공개 메서드와 `ai.*` 설정은 그대로이며 기본 구현은 Claude다.
`AiProvider`는 ai-core 패키지 내부 생성 SPI다. 서비스가 SDK나 Provider를 직접 만들지 않는다.

생성 순서는 설정 검증 → 계량 인터셉터 개수 검증 → disabled 분기 → 마스커 생성 → Provider 생성이다.
AI가 꺼졌거나 설정이 잘못됐으면 Provider를 부르지 않는다. 설정 오류에는 값이나 키를 싣지 않는다.
Provider에는 검증된 설정, 공통 `PiiMasker`, 기존 계량 인터셉터를 전달한다.

`ClaudeAiProvider`는 기존 SDK 빌더의 명시적 키, 본문 로그 OFF, 재시도와 연결·읽기·쓰기·요청 타임아웃, 선택적 base URL을 보존한다.
실제 호출의 길이 제한·마스킹·동시 상한·사용량 계량·오류 변환은 기존 `ClaudeAiClient`에 그대로 둔다.
반환값도 기존 클라이언트 자체여서 종료 시 SDK 자원을 닫는 기존 생명주기를 유지한다.

## 후속 범위

이 단계는 OpenAI 구현이나 기능별 Provider 라우팅을 추가하지 않는다. 다음 PR에서 Responses API 구현과 Provider별 키·모델 설정,
사용량 매핑 및 operations 단가표를 함께 설계·검증한다. 현재 `AiProperties`의 Claude 전용 설정을 다른 Provider에 그대로 적용했다고 간주하지 않는다.

자동 Provider 전환은 하지 않는다(#468 확정 결정). 같은 Provider의 SDK 재시도 이후에는 기존 `AiException`으로 실패를 전달한다.
새 Provider도 단일·구조화·대화·도구·스트리밍 모든 경로에서 개인정보 마스킹과 계량을 적용해야 한다.
