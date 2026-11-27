# 방문 분석 상품 조회 집계 (#226)

operations-service의 방문 분석 컨텍스트가 수집과 어드민 집계를 소유한다. product-service의 상품 등록·공개 상태와 분리한다. 카운트는 클라이언트가 보고한 조회이며 구매나 고유 방문자 수를 뜻하지 않는다.

## #227 소비 계약

- 스토어프론트는 방문 분석에 명시적으로 동의한 뒤에만 같은 출처 BFF를 통해 `POST /api/v1/analytics/events`를 호출한다. 동의 철회 즉시 전송을 중단한다. BFF는 #134의 클라이언트 주소 전달을 사용하고 회원 요청의 access 토큰을 전달한다.
- `application/json` 및 beacon의 `text/plain` UTF-8 본문에서 `{"type":"PRODUCT_VIEW","eventId":"5aa7e291-a1bb-47f4-9d9a-2fbb162268c9","productId":"42","consent":true}` 형식의 한 건을 받는다. 상품 상세의 한 조회당 무작위 UUID v4 하나를 생성해 전송 재시도에 재사용한다. 상품 ID는 양의 64비트 정수를 담은 문자열이다.
- 204는 정상 처리, 재시도 중복, 미동의, 봇·내부 트래픽 또는 수집 비활성화를 포함한다. 응답 본문은 비어 있다. 잘못된 이벤트는 400 `VISIT_ANALYTICS_100`, 1KiB 초과는 413, 지원하지 않는 Content-Type 또는 Content-Encoding은 415다. 게이트웨이는 초당 1건·순간 10건을 #135 HMAC 버킷으로 세며, 초과하면 429와 Retry-After, 제한 기능이나 저장소를 쓸 수 없으면 503을 반환한다.
- 어드민의 `GET /api/v1/admin/analytics/products/top?from=2026-11-01&to=2026-11-27`은 응답 봉투의 `data`에 `[{ "productId":"42", "views":123 }]` 형태로 최대 20개를 담는다. 조회수 내림차순과 상품 ID 오름차순으로 정렬한다. 월 순위는 이달 첫날부터 오늘까지를 조회한다.
- `GET /api/v1/admin/analytics/products/{productId}/daily?from=...&to=...`는 `data`에 `[{ "day":"2026-11-27", "views":4 }]`를 날짜 오름차순으로 담는다. 조회수가 0인 날짜는 생략한다. 두 API는 한국 시간 기준 양 끝 날짜 포함, 최대 31일이며 유효한 기간이 아니면 400 `VISIT_ANALYTICS_101`이다. 상품 식별자가 유효하지 않으면 400 `VISIT_ANALYTICS_102`다. ADMIN과 OWNER만 호출할 수 있다. FE 계약 검토자는 `seonghoho`다.

## 집계와 운영

- 게이트웨이가 외부의 `X-Client-Subject`를 지우고 #135의 HMAC으로 덮어쓴다. operations-service는 게이트웨이 내부 네트워크에서만 접근할 수 있어야 한다. 하류는 전달 IP 헤더나 소켓 주소를 데이터로 읽지 않는다. 인증된 운영자 계정과 알려진 크롤러 User-Agent 패턴을 걸러낸다. 이 필터는 위조된 브라우저 헤더까지 완벽히 식별할 수 없다.
- `visit-analytics.enabled`(`VISIT_ANALYTICS_ENABLED`) 기본값은 false다. #135의 신뢰 프록시와 Vault 설정, #227의 동의 UI·BFF, 운영 동의·보존 정책을 확인한 뒤 수집을 켠다. 운영 DDL은 배포 전에 적용한다. 이벤트의 `consent` 값은 호출자가 전달하는 동의 신호다. 방문자 식별용 쿠키나 회원 ID를 이 기능에서 생성·저장하지 않는다.
- 손실 없이 중복을 제거하도록 이벤트 영수증과 상품별 일간 카운트를 한 트랜잭션에서 갱신한다. 도착 시각을 한국 날짜로 변환하며 원문 이벤트, 회원 식별자, IP, HMAC, User-Agent를 DB에 적지 않는다. 최신 7일의 UUID 영수증과 최신 400일의 상품별 카운트를 보유하고, 일별로 지난 행을 삭제한다.
- 국가·페이지 조회·체류·referrer·UTM 및 실시간 카운터는 후속 범위다. 국가 수집을 위해서는 #135 신뢰 경계 안에서 조회할 GeoIP 데이터 공급·갱신 방식이 필요하다. 기존 HMAC으로 IP나 국가를 역산할 수 없다. #212 작업 엔진은 향후 별도 배치가 필요한 집계의 선택지이며, 이 상품 카운트는 이벤트가 저장될 때 바로 갱신된다.
