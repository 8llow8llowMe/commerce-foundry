## 📝 작업 내용

[작업 내용을 2~4문장으로 요약]

### 주요 변경 사항

1. [주요 변경 1]
2. [주요 변경 2]
3. [주요 변경 3]

## 제목 규칙

`[영역] type: 요약` — 영역은 `BE` / `FE` / `DOCS` / `INFRA` (루트 `CLAUDE.md` 기준)
예: `[BE] feat: 상품 목록 조회 API 구현`

## 타입

- [ ] feat: 새로운 기능 추가
- [ ] fix: 버그 수정
- [ ] chore: 빌드 업무 수정, 패키지 매니저 수정
- [ ] refactor: 코드 리펙토링
- [ ] style: 코드 포맷팅, 세미콜론 누락, 코드 변경이 없는 경우
- [ ] docs: 문서 수정
- [ ] test: 테스트 코드, 리펙토링 테스트 코드 추가

## PR 하기 전에 확인해주세요

- [ ] 코딩 컨벤션을 지켰나요? (BE: `backend/docs/coding-conventions.md`)
- [ ] local ci test를 진행하셨나요?
  - BE: `./gradlew compileJava` / `test` / `check`
  - FE: 프론트엔드 `CLAUDE.md` 의 검증 명령
- [ ] 완료 체크리스트를 확인하셨나요? (BE: `backend/docs/done-checklist.md`)
- [ ] 결제·주문·재고 로직을 건드렸다면 `/payment-guard` · `/order-flow-guard` 를 돌렸나요?
- [ ] 비밀키·사업자 정보·고객 개인정보가 커밋에 섞이지 않았나요? (공개 저장소)
- [ ] 새 키가 생겼다면 `backend/.env.example` 에 이름만 추가하고, 참고 사항에 `Vault 등록 필요: KEY_NAME` 을 적었나요?

## 검증 내역

- [실제로 실행한 명령과 결과. 미실행·실패는 숨기지 않고 적습니다]

## 참고 사항

- [리뷰어가 알아야 할 점 — API 계약 변경, FE 영향, 마이그레이션 등]

## 연관된 이슈

Issue Number: #
