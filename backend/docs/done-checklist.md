# Backend Done Checklist

## 1. 기능 단위 완료 기준

- [ ] API 경로가 `api-design-guide.md` §1 의 체계(고객 / 어드민 / internal / webhook)와 서비스 소유 표에 맞다
- [ ] Controller → UseCase → Facade → Processor → Presenter 흐름이 맞다
- [ ] 응답이 `ResponseEntity<Response<T>>` 규칙을 지킨다
- [ ] 응답 DTO 의 식별자가 `String` 이다
- [ ] Swagger 어노테이션과 한국어 설명이 정리됐다 (어드민 API 는 `[어드민]` 태그)
- [ ] 예외가 도메인 3종 세트(ErrorCode / Exception / ExceptionHandler)를 따른다
- [ ] 바꾼 모듈의 compile / test / check 를 로컬에서 통과했고, 전 모듈은 CI(`backend-ci`)가 녹색이다
  - `./gradlew :service:<svc>:test` 만으로는 재실행이 보장되지 않는다 — Gradle 이 `UP-TO-DATE` / `FROM-CACHE` 로
    건너뛰면 테스트가 돌지 않은 채 `BUILD SUCCESSFUL` 이 나온다. 보고할 때는
    `./gradlew :service:<svc>:cleanTest :service:<svc>:check --no-build-cache` 로 실제 실행한 결과를 쓴다.
    여러 모듈을 바꿨으면 그 모듈들만 나열한다.
  - core 같은 공용 모듈을 바꿨으면 그 모듈 check 에 더해 `./gradlew compileTestJava`(전 모듈 컴파일만)까지 본다.
    다른 모듈의 테스트는 CI 그룹 job 결과(건수 표)로 확인한다.
  - 루트 `./gradlew check` · `./gradlew test`(전 모듈 테스트)는 로컬 기본 검증이 아니다 — 같은 PC 에서 여러 세션이 돌면
    테스트 JVM 이 겹쳐 뜬다(`local-run-guide.md` §4-1). 전 모듈은 CI 가 그룹 병렬로 돌린다.

## 2. 계층 체크리스트

- [ ] Controller 가 하위 레이어를 직접 호출하지 않는다
- [ ] Processor 가 Response DTO 를 직접 만들지 않는다
- [ ] Presenter 가 Info → Response 변환만 담당한다
- [ ] Port 경계에 adapter 타입 의존이 없다 (PG·Feign 응답 DTO 가 application 에 없다)
- [ ] 고객 API 와 어드민 API 가 DTO 를 공유하지 않는다
- [ ] 외부 I/O 가 섞인 유스케이스는 Facade 에 트랜잭션을 걸지 않았다

## 3. 데이터 / 영속성 체크리스트

- [ ] JPA 연관관계 어노테이션을 쓰지 않았다
- [ ] FK 컬럼에 `@Column(comment = "... (FK: table.id)")` 가 있다
- [ ] 인덱스 이름이 `idx_` / `uk_` + 모든 컬럼, check 제약 이름이 `ck_{table}_{의미}` 규칙을 따르고 63자를 넘지 않는다
- [ ] 금액이 `long` 최소 단위 + 통화 컬럼 쌍이다
- [ ] 루프 안 단건 조회(N+1)가 없다
- [ ] 동적 쿼리·JPQL 은 `@DataJpaTest` 로 실제 질의를 검증했다
- [ ] 엔티티를 추가·변경했다면 DDL 스냅숏을 같은 PR 에서 갱신하고, PR 본문 `운영 DDL` 절에 Hibernate 가 만든 PostgreSQL DDL(local·dev `update` 결과 또는 스냅숏)을 적었다 (`coding-conventions.md` §9-8)

## 4. 커머스 흐름 체크리스트 (해당 시)

- [ ] 주문·결제 금액을 서버에서 계산하고, 클라이언트 금액을 믿지 않는다
- [ ] 상태 전이가 도메인 메서드로만 일어나고 허용 전이 표를 지킨다
- [ ] 재고 차감이 조건부 UPDATE 한 문장이다
- [ ] 중복 요청(더블 클릭, 재시도, 웹훅 재전송)에 멱등하다
- [ ] PG 호출 결과가 불명확할 때 `UNKNOWN` 으로 남고 재조회 경로가 있다
- [ ] `/payment-guard`, `/order-flow-guard` 를 돌렸다

## 5. 보안 / 운영 체크리스트

- [ ] `adapter/in/web` · `adapter/in/admin` 의 모든 핸들러에 `@PreAuthorize` 가 있다 — 공개 API 는 `permitAll()`, 어드민 API 는 `hasRole('ADMIN')`
- [ ] `adapter/in/internal` 의 모든 핸들러에 허용 호출자 `@PreAuthorize("hasAuthority('SERVICE_<호출 서비스>')")` 가 있다 — `isAuthenticated()` · `permitAll()` 이 아니다 (`security-guide.md` §9-4)
- [ ] internal Feign 클라이언트에 `configuration = InternalFeignClientConfiguration.class` 를 붙였다. 사용자 `Authorization` 을 넘기지 않고(`RequestContextHolder` 로 꺼내 싣지 않는다), 전역 `RequestInterceptor` 빈이 없다 (`coding-conventions.md` §10)
- [ ] 서비스에 인가 스모크 `*SecuritySmokeTest`(`ResourceServerSmokeTestSupport` 상속 — 서비스 간 인증 케이스 포함)가 있다. `isAuthenticated()` 핸들러를 추가했다면 그 경로의 익명 401 · `SECURITY_001` 케이스를 그 스모크에 메서드로 더했다 (`security-guide.md` §5-3)
- [ ] 서비스에 정적 규칙 `<Svc>ArchitectureTest`(`ControllerAuthorizationArchTestSupport` 상속 한 파일)가 있다 — 없으면 규칙이 돌지 않아도 빌드가 통과한다 (`security-guide.md` §9-11)
- [ ] 리소스 소유 확인이 JWT memberId 기준이고, 타인 리소스는 404 다
- [ ] 설정값이 하드코딩 대신 프로퍼티로 관리된다
- [ ] 시크릿 키·사업자 정보가 커밋되지 않았다 (공개 저장소)
- [ ] 외부 호출에 timeout / 서킷브레이커가 적용됐다
- [ ] 로그에 개인정보·결제키·토큰이 남지 않는다

## 6. 문서 / 협업 체크리스트

- [ ] 공통 규칙이 바뀌었다면 `backend/docs/*.md` 를 갱신했다
- [ ] 서비스 책임이 바뀌었다면 `service-inventory.md` 를 갱신했다
- [ ] API 계약이 바뀌었다면 PR 에 FE 영향을 적고 `seonghoho` 를 리뷰어로 지정했다
- [ ] 새 반복 패턴이 생겼다면 스킬화할 가치가 있는지 검토했다
