# 스토어 상단 메뉴 (#599)

`operations-service`의 `storenavigation`은 배포당 하나의 상단 메뉴 트리를 소유한다. 국문·영문 표시명과 설명을
한 리비전으로 함께 저장·게시한다. 카테고리 자체(이름·계층·노출)는 product-service가 소유하며 메뉴는 아이디만 참조한다.
구조는 푸터(`store-footer-guide.md`, #597)와 같다 — 고정 root, 불변 리비전, 최신/게시 포인터, 명시적 게시.

## API 계약

모든 성공·도메인 오류는 공통 `Response` 봉투와 `Cache-Control: no-store`를 쓴다. 관리자 조회는
`AdminAuthority.SETTINGS_READ`, 저장·게시는 `SETTINGS_MANAGE`(ADMIN·OWNER)다. 쓰기 행위자는 실제 access JWT에서만 받는다.

| 경로 | 요청 | `dataBody` |
|---|---|---|
| `GET /api/v1/admin/store/navigation` | 없음 | 최신 초안 Detail. 미등록은 404 |
| `PUT /api/v1/admin/store/navigation` | `{expectedVersion,items}` | 저장한 최신 Detail, 200 |
| `POST /api/v1/admin/store/navigation/publication` | `{expectedVersion,revisionNumber}` | 최신 Detail, 200. 미등록은 404 `STORE_NAV_001` |
| `GET /api/v1/store/navigation` | `locale=ko`(기본, `ko|en`만 허용) | 게시본의 공개 트리. 미게시·미등록은 404 |

- PUT의 `items`는 **평평한 목록**이다. 항목은
  `{key,parentKey,type,labels:{ko,en},descriptions:{ko,en}|null,url,categoryId,displayOrder,visible,primary,shortcut}`이고
  `parentKey`로 트리를 만든다. PUT은 목록 전체 교체이며 `items:[]`는 유효한 빈 메뉴, 누락/null은 오류다.
  `descriptions`·`primary`·`shortcut`은 생략하면 null·false다. `categoryId`는 숫자 또는 JSON 문자열(`"42"`)을 받는다.
- `key`는 리비전 안에서만 의미가 있는 **편집용 식별자**다(DB 행 아이디가 아니다). 편집기는 항목을 옮기거나 새로 만들 때
  자유롭게 정하고, 다음 저장에서 바뀌어도 된다. 공개 응답에는 내리지 않는다.
- Detail은 `{version,latestRevision,publishedRevision,revision}`이다. `revision`은 항상 최신 저장본이며
  `{revisionNumber,items,createdBy,createdAt,publishedBy,publishedAt}`를 포함한다. `items`는 숨김 포함 전체를
  **트리 전위 순서**(부모 다음 자식, 형제는 `displayOrder` 오름차순)로 돌려준다. 관리자·카테고리 아이디는 문자열, 시각은 UTC ISO다.
- 공개 응답은 `{items:[Node]}`이고 `Node`는 `{label,description,type,url,categoryId,primary,shortcut,children:[Node]}`만 허용한다.
  요청 locale의 표시명·설명만 담고 `visible=true` 항목만 형제 순서 오름차순으로 중첩한다. **숨김 항목은 하위 전체와
  primary·shortcut까지 함께 빠진다.** 언어 fallback·편집용 key·버전·작성자·이력은 내리지 않는다. 빈 게시본도 200이다.
  공개 URL을 백엔드에서 가져오거나 리다이렉트를 따라가지 않는다.

### 필드 의미와 스토어프론트 헤더 매핑 (#602)

| 필드 | 의미 | 헤더에서 |
|---|---|---|
| 최상위 항목(`parentKey=null`) | 대제목 | 메뉴 패널의 대제목 |
| `description` | 대제목 아래 한 줄 설명(선택) | 예: "전체 상품 / 카테고리" |
| `primary` | PC 1차 메뉴 노출 | PC 헤더 로고 아래 1차 메뉴 |
| `shortcut` | 모바일 바로가기 노출 | 모바일 헤더 바로가기 줄 |
| 하위 항목(`children`) | 패널 하위 링크 | 대제목 아래 링크 |
| `type` | 링크 종류 | INTERNAL: `url` 경로 그대로, EXTERNAL: 새 창 외부 링크, CATEGORY: `categoryId`로 카테고리 경로 조립, NONE: 링크 없는 묶음 제목 |

`primary`·`shortcut`은 최상위 항목만 켤 수 있다. 같은 항목이 둘 다 켜져도 된다. 순서는 모두 `displayOrder`를 따른다.
`description`은 하위 항목에도 넣을 수 있고 공개 트리의 하위 노드에도 그대로 내려간다. 다만 스토어프론트 헤더(#602)는
**최상위 항목의 설명만** 쓴다 — 하위 설명은 다른 화면(모바일 펼침 등)이 쓸 때를 위해 남겨 둔 값이다.
FE는 브랜드별 고정 NavItem 대신 이 응답을 쓰고, 조회 실패·미게시 때 초안을 대체 노출하지 않는다.

## 초안·게시·동시성

- 최초 PUT은 `expectedVersion=null`, 기존 설정은 관리자 GET의 `version`(0 이상)이 필요하다. 같은 본문이라도 새 불변 리비전을
  추가하고 버전이 증가한다. 응답을 잃은 PUT 재전송은 409이므로 GET으로 확인한다. 저장은 게시 포인터를 바꾸지 않는다.
- 게시는 `expectedVersion`(0 이상)과 양수 `revisionNumber`가 필수다. **현재 최신 초안만** 새 게시본으로 고른다.
  현재 게시 리비전의 반복 게시는 최신 초안이 따로 있어도 변경 없이 성공하며, 이 경우 오래된 `expectedVersion`도 허용한다.
  최초 게시자·게시 시각·버전은 반복 게시에서 유지한다. 그 밖의 오래된 버전·리비전은 409다.
  같은 리비전을 동시에 게시하면 한쪽이 409다 — 게시 409 뒤 GET의 `publishedRevision`이 요청한 번호와 같으면 성공으로 본다.
- 새 게시는 저장본을 **현재 배포 설정의 URL 규칙으로 다시 검증**한다. 저장 뒤 허용 호스트를 지웠다면 게시는 400 `STORE_NAV_109`이고
  아무것도 바뀌지 않는다(이미 게시된 리비전의 반복 게시는 재검증하지 않는다).
- 고정 root `id=1`의 `@Version`, 최신/게시 포인터, 불변 리비전과 항목을 쓴다. root 갱신과 새 리비전·항목 INSERT,
  게시 포인터와 최초 게시 기록은 각각 **포트가 여는 독립 트랜잭션 하나**다. Facade는 트랜잭션을 걸지 않는다 —
  커밋 뒤에 감사를 남겨 감사 실패가 업무 결과를 바꾸지 않게 하고, 호출자 트랜잭션을 오염시키지 않기 위해서다.
  최초 생성 PK·리비전 번호 유니크 경합과 낙관 충돌은 409, 중간 실패는 전체 롤백이다. 409 뒤에는 GET으로 다시 읽는다.
- 시각은 저장 전에 마이크로초로 자른다. 공개 캐시나 FE 번들에 초안을 저장하지 않는다.

## 입력 검증

| 항목 | 규칙 |
|---|---|
| 목록 | 전체 최대 60개(숨김 포함), 한 상위 항목 아래 최대 20개, null 항목 금지. 0개 허용 |
| key | `[a-z0-9][a-z0-9-]{0,39}`, 목록 안에서 유일 |
| 상위 | 목록에 있는 다른 항목 key. 자기 참조·순환 금지, 깊이 최대 3단계 |
| 표시명 | `labels.ko`, `labels.en` 모두 공백 아닌 1~40 UTF-16 코드 단위, 제어문자 금지 |
| 설명 | 없거나(null) `descriptions.ko`, `descriptions.en` 모두 공백 아닌 1~80 UTF-16 코드 단위, 제어문자 금지 |
| 순서·노출 | `displayOrder` 필수 0~999, 같은 상위 항목 아래에서 중복 금지(숨김 포함). `visible` 필수 |
| 노출 위치 | `primary`·`shortcut`은 최상위 항목만 true |
| 유형 | `INTERNAL`·`EXTERNAL`·`CATEGORY`·`NONE`(대소문자 구분) |

숨김 항목도 모든 규칙을 똑같이 지킨다(나중에 노출로 바꿔도 안전해야 한다). 유형별 대상은 다음과 같다.

- `INTERNAL`: `/`로 시작하는 같은 스토어 상대 경로만(최대 512자). `//`, `.`·`..` 구간, `%`, 질의·fragment, 비ASCII는 거부한다.
- `EXTERNAL`: `commerce.store.navigation.allowed-external-hosts`에 명시한 정확한 호스트의 HTTPS URL만. 포트·사용자 정보·
  질의·fragment·`%`·IP/localhost·내부 TLD는 거부한다. 기본값은 빈 목록이라 외부 링크는 모두 거부된다.
- `CATEGORY`: `url` 없음, 양수 `categoryId` 필수. 카테고리 존재 여부는 지금 확인하지 않는다(형식만). 삭제·비활성 카테고리는
  FE가 만든 링크가 404 페이지로 끝난다. 게시 시점 존재 확인은 후속 이슈 후보다(internal 카테고리 확인 Feign).
- `NONE`: 링크 없는 묶음 제목. `url`·`categoryId` 없음.

이 검증은 목적지의 소유권이나 콘텐츠 안전을 증명하지 않는다.

### 설정 키

`commerce.store.navigation.allowed-external-hosts` (환경변수 `STORE_NAVIGATION_ALLOWED_EXTERNAL_HOSTS`, 쉼표 구분, 비밀 아님).
푸터의 `store.footer.allowed-external-hosts`와 **분리**한다 — 푸터는 고객센터·SNS 같은 소수 채널, 메뉴는 매거진·제휴몰 같은
다른 목적지라 승인 범위가 다르고, 한쪽 허용이 다른 쪽을 자동으로 열면 안 된다. 접두어가 `commerce.`인 것은 이 서비스의
공통 설정 블록(`commerce.messaging`) 아래 둔 결정(#599)이다. 호스트 추가는 배포 설정 변경이며 브랜드 도메인을 코드에 고정하지 않는다.

## 오류·감사

| 코드 | HTTP | 의미 |
|---|---|---|
| `STORE_NAV_001` | 404 | 관리자 설정 또는 공개 게시본 없음 |
| `STORE_NAV_020` | 409 | 버전·최신 리비전·동시 생성/수정 충돌 |
| `STORE_NAV_100` | 400 | JSON·locale·범위 밖 숫자 등 일반 요청 오류 |
| `STORE_NAV_101`~`112` | 400 | 순서대로 버전, 목록, key, 상위, 순환, 깊이, 유형, 표시명, URL, 카테고리, 순서, 노출 |
| `STORE_NAV_113`~`115` | 400 | 리비전 번호, 설명, 노출 위치(하위 항목의 primary·shortcut) |
| `STORE_NAV_198`·`199` | 400 | 파라미터 형식·필수 값 오류 |

어드민 401/403은 공통 보안 오류를 따른다. 쓰기 감사는 `STORE_NAVIGATION_DRAFT_SAVE`·`STORE_NAVIGATION_PUBLISH`,
JWT 관리자 ID, 고정 대상 `store-navigation:1`, 성공/실패만 기록한다. key·URL·표시명·설명·카테고리 아이디·요청 본문은
감사·진단 로그에 넣지 않는다. 커밋과 응답 조립 뒤 성공을 기록하며, 감사 실패는 업무 결과를 바꾸지 않는다.
커밋 뒤 응답 유실·Presenter 실패는 실제 반영 가능성이 있으므로 GET으로 확인한다.

## 영속성·배포·검증

`operations.store_navigation`(singleton root/버전·포인터), `store_navigation_revision`(불변 머리·작성/최초 게시 기록),
`store_navigation_item`(리비전별 항목, PK `(item_key, revision_id)`, 리비전 조회 인덱스) 세 테이블을 쓴다.
JPA 연관관계 없이 raw FK 컬럼 주석, `uk_`·`ck_` 제약을 둔다(singleton, 포인터, 게시 기록 짝, 유형, 순서, 자기 참조,
유형별 url/category 형태, 설명 짝, 하위 항목 노출 위치). `primary`는 SQL 예약어라 컬럼 이름을 `primary_menu`·`shortcut_menu`로 둔다.
Hibernate PostgreSQL 17 생성 결과를 `service/operations-service/src/test/resources/ddl/operations-schema.sql`에 반영하고 PR 운영 DDL에 싣는다.
운영 DDL 적용 후 배포하며 기존 데이터 이관과 새 Vault 비밀은 없다. Gateway의 기존 `/api/v1/store/**`·`/api/v1/admin/store/**`
operations 라우트를 재사용한다. dev는 `ddl-auto=update`가 기존 테이블에 컬럼은 더해도 check 제약은 만들지 않으므로,
PR 본문의 운영 DDL(ALTER)을 dev에도 직접 적용한다.

허용 외부 호스트를 설정에서 뺄 때는 그 링크를 지운 새 초안을 저장·게시한다(이미 게시된 리비전은 반복 게시 때 재검증하지 않는다).

도메인 규칙, H2 왕복·롤백·게시, 실제 PostgreSQL 제약·경합(최초 저장·같은 버전 저장·저장/게시·동시 게시), 실제 JWT 권한·봉투·
공개 필드 허용 목록·감사, DDL 스냅숏과 operations-service `cleanTest check`를 수행한다. Docker 없는 로컬의 PostgreSQL skip은
CI 실제 실행과 구분한다.
