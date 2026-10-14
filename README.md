# commerce-foundry

A modular e-commerce platform built with Spring Cloud and Next.js, designed for customizable storefronts,
order management, and payment integrations.

재사용 가능한 쇼핑몰 엔진입니다. 첫 번째 적용 사례는 생활한복 브랜드 MOKOYURI 의 카페24 → 자체 쇼핑몰 이전입니다.

## 구성

| 영역 | 기술 | 내용 |
|------|------|------|
| `backend/` | Java 21 · Spring Boot · Spring Cloud (Gateway, Eureka, OpenFeign) · PostgreSQL · Redis | 회원·상품·주문·결제·배송 MSA, Hexagonal Architecture |
| `frontend/` | Next.js · TypeScript | 고객 스토어프론트(국문/영문) · 어드민 콘솔 |
| 결제 | 토스페이먼츠 | 국내 카드 · 카카오페이 · 네이버페이 · PayPal(해외, USD) |

```text
backend/
├── cloud/     api-gateway · service-discovery
├── core/      common · persistence · redis · security · storage · shared-commerce
└── service/   auth · product · order · payment · delivery
```

## 문서

| 문서 | 내용 |
|------|------|
| [CLAUDE.md](CLAUDE.md) | 저장소 공통 규칙 · 워크스페이스 지도 |
| [docs/git-workflow.md](docs/git-workflow.md) | 이슈 → 브랜치 → PR → 머지 규칙 |
| [backend/docs/](backend/docs/README.md) | 백엔드 아키텍처 · 컨벤션 · API · 결제 · 주문 흐름 |
| [docs/claude-agents.md](docs/claude-agents.md) | Claude Code 하위 에이전트 운영 |

## 협업

```text
/issue → /issue-start <번호> → 작업 → /pr → CI → Rebase and merge
```

- 이슈 담당자: `[BE]`·`[INFRA]`·`[DOCS]` → @shindonggeun, `[FE]` → @seonghoho
- 모든 작업 PR 의 base 는 `develop` 입니다.

> 공개 저장소입니다. 결제 키·사업자 정보는 Vault 에만 두고 저장소에는 키 이름만 둡니다
> ([backend/.env.example](backend/.env.example), [secret-management.md](backend/docs/secret-management.md)).
