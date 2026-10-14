# Backend Done Checklist

## 1. 기능 단위 완료 기준

- [ ] API 경로가 `api-design-guide.md` §1 의 체계(고객 / 어드민 / internal / webhook)와 서비스 소유 표에 맞다
- [ ] Controller → UseCase → Facade → Processor → Presenter 흐름이 맞다
- [ ] 응답이 `ResponseEntity<Response<T>>` 규칙을 지킨다
- [ ] 응답 DTO 의 식별자가 `String` 이다
- [ ] Swagger 어노테이션과 한국어 설명이 정리됐다 (어드민 API 는 `[어드민]` 태그)
- [ ] 예외가 도메인 3종 세트(ErrorCode / Exception / ExceptionHandler)를 따른다
- [ ] compile / test / check 를 통과했다
  - `./gradlew :service:<svc>:test` 만으로는 재실행이 보장되지 않는다 — Gradle 이 `UP-TO-DATE` / `FROM-CACHE` 로
    건너뛰면 테스트가 돌지 않은 채 `BUILD SUCCESSFUL` 이 나온다. 보고할 때는
    `./gradlew :service:<svc>:cleanTest :service:<svc>:test --no-build-cache` 로 실제 실행한 결과를 쓴다.

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
- [ ] 인덱스 이름이 `idx_` / `uk_` + 모든 컬럼 규칙을 따르고 63자를 넘지 않는다
- [ ] 금액이 `long` 최소 단위 + 통화 컬럼 쌍이다
- [ ] 루프 안 단건 조회(N+1)가 없다
- [ ] 동적 쿼리·JPQL 은 `@DataJpaTest` 로 실제 질의를 검증했다

## 4. 커머스 흐름 체크리스트 (해당 시)

- [ ] 주문·결제 금액을 서버에서 계산하고, 클라이언트 금액을 믿지 않는다
- [ ] 상태 전이가 도메인 메서드로만 일어나고 허용 전이 표를 지킨다
- [ ] 재고 차감이 조건부 UPDATE 한 문장이다
- [ ] 중복 요청(더블 클릭, 재시도, 웹훅 재전송)에 멱등하다
- [ ] PG 호출 결과가 불명확할 때 `UNKNOWN` 으로 남고 재조회 경로가 있다
- [ ] `/payment-guard`, `/order-flow-guard` 를 돌렸다

## 5. 보안 / 운영 체크리스트

- [ ] 인증이 필요한 API 에 `@PreAuthorize` 가 있다. 어드민 API 는 `hasRole('ADMIN')`
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
