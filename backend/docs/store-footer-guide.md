# 스토어 푸터 채널·추가 링크 (#597)

`operations-service`의 `storefooter`는 배포당 하나의 푸터 링크 설정을 소유한다. 회사·고객센터 실정보는
`storeprofile`(#566), 이용약관·개인정보처리방침 본문과 게시 상태는 `legaldocument`(#569)가 소유한다.
푸터에는 이 값을 복제하거나 고객사별 기본값을 넣지 않는다. 국문·영문 문구는 한 리비전으로 함께 저장·게시한다.

## API 계약

모든 성공·도메인 오류는 공통 `Response` 봉투와 `Cache-Control: no-store`를 사용한다. 관리자 조회는
`AdminAuthority.SETTINGS_READ`, 저장·게시는 `SETTINGS_MANAGE`(ADMIN·OWNER)다. 쓰기 행위자는 실제 access JWT에서만 받는다.

| 경로 | 요청 | `dataBody` |
|---|---|---|
| `GET /api/v1/admin/store/footer` | 없음 | 최신 초안 Detail. 미등록은 404 |
| `PUT /api/v1/admin/store/footer` | `{expectedVersion,copyrightText,links}` | 저장한 최신 Detail, 200 |
| `POST /api/v1/admin/store/footer/publication` | `{expectedVersion,revisionNumber}` | 최신 Detail, 200 |
| `GET /api/v1/admin/store/footer/revisions` | `page=0,size=10`(1~20) | 리비전 번호 내림차순 `PageResponse<Revision>` |
| `GET /api/v1/store/footer` | `locale=ko`(기본, `ko|en`만 허용) | 게시본의 공개 Content. 미게시·미등록은 404 |

- PUT의 `copyrightText`는 `{ko,en}`이고 `links`는 `[{type,labels:{ko,en},url,displayOrder,visible}]`이다.
  링크별 식별자는 요청·응답에 없으며 PUT은 목록 전체 교체다. `links:[]`는 유효한 빈 푸터이고 누락/null은 오류다.
- Detail은 `{version,latestRevision,publishedRevision,revision}`이다. `revision`은 항상 최신 저장본이며
  `{revisionNumber,copyrightText,links,createdBy,createdAt,publishedBy,publishedAt}`를 포함한다.
  관리자 식별자는 문자열, 시각은 UTC ISO다. 최신본이 미게시이면 게시자·게시 시각은 null이다.
- 이력 Revision도 같은 불변 내용·작성/최초 게시 기록을 담는다. 복원은 이력을 새 PUT 초안으로 복사한 뒤 게시한다.
- 공개 Content는 `{copyrightText,links:[{type,label,url}]}`만 허용한다. 요청 locale의 문구와 `visible=true` 링크만
  순서 오름차순으로 반환하며 다른 언어·숨김 링크·내부 ID·버전·작성자·이력을 내리지 않는다. 언어 fallback은 없다.
  빈 목록도 정상 게시본이면 200이다. 공개 URL은 백엔드에서 HTTP로 가져오거나 리다이렉트를 따라가지 않는다.

## 초안·게시·동시성

- 최초 PUT은 `expectedVersion=null`; 기존 설정은 관리자 GET의 `version`(0 이상)이 필요하다.
  같은 본문이라도 새 불변 리비전을 추가하고 버전은 증가한다. 응답을 잃은 PUT 재전송은 409이므로 GET으로 확인한다.
  저장은 현재 게시 포인터를 바꾸지 않는다.
- 게시는 `expectedVersion`(0 이상)과 양수 `revisionNumber`가 필수다. 현재 최신 초안만 새 게시본으로 고른다.
  현재 게시 리비전의 반복 게시는 최신 초안이 따로 있어도 변경 없이 성공하며, 이 경우 오래된 expectedVersion도 허용한다.
  최초 게시자·게시 시각·버전은 반복 게시에서 유지한다. 그 밖의 오래된 버전·리비전은 409다.
- 고정 root `id=1`의 `@Version`, 최신/게시 포인터, 불변 리비전과 링크를 사용한다. root 갱신과 새 리비전·링크 INSERT,
  게시 포인터와 첫 게시 기록은 각각 한 트랜잭션이다. 최초 생성 PK·유니크 경합 및 낙관 충돌은 409,
  중간 실패는 전체 롤백이다. 409 후 자동 덮어쓰기 대신 GET으로 최신 상태를 다시 읽는다.
- 시각은 저장 전에 마이크로초 정밀도로 자른다. 공개 캐시나 FE 번들에 초안을 저장하지 않는다.

## 입력 검증

| 항목 | 규칙 |
|---|---|
| 목록 | 최대 20개(숨김 포함), null 항목 금지. 0개 허용 |
| 표시명 | 각 링크 `labels.ko`, `labels.en` 모두 공백 아닌 1~80 UTF-16 코드 단위. 제어문자 금지. 숨김도 동일 |
| 저작권 | `copyrightText.ko/en` 각각 null 또는 공백 아닌 1~200자. 제어문자 금지 |
| 순서·노출 | `displayOrder` 필수 0~999, 전체 목록에서 중복 금지(숨김 포함); `visible` 필수 Boolean |
| URL | 공백 아닌 최대 2048자. 아래 HTTPS 정책을 저장·새 게시 때 모두 검사 |

`type`은 대소문자 구분 제어 코드 `INSTAGRAM`, `KAKAO_CHANNEL`, `CUSTOM`만 허용한다. `INSTAGRAM`은
`instagram.com` 또는 `www.instagram.com`의 계정형 한 경로 구간, `KAKAO_CHANNEL`은 `pf.kakao.com`의
`/_채널ID` 또는 그 뒤 `/chat`만 허용한다. `CUSTOM`은 `store.footer.allowed-external-hosts`에 명시한
정확한 호스트만 허용한다. 이 목록의 기본값은 비어 있어 승인되지 않은 호스트는 저장할 수 없다.
호스트 추가는 배포 설정 변경이며 브랜드 도메인을 공통 코드에 고정하지 않는다.

모든 URL은 HTTPS, ASCII DNS 호스트, 기본 포트(명시 포트 없음), 허용된 ASCII 경로만 받는다. scheme·host는
구조적으로 파싱해 소문자 비교한다. 사용자 정보, IP/localhost·내부 호스트, 와일드카드·접미 문자열 우회,
백슬래시·공백·제어문자·인코딩된 구분자, query·fragment, 상대경로·`http:`·`javascript:`·`data:`·`file:`·
`mailto:`·`tel:`은 거부한다. 숨김 항목도 유효한 URL이 필요하며 편집기의 빈 행은 제거하거나 완성한 뒤 저장한다.
이 검증은 목적지의 소유권이나 콘텐츠 안전을 증명하지 않는다.

## 오류·감사·FE 연계

| 코드 | HTTP | 의미 |
|---|---|---|
| `STORE_FOOTER_001` | 404 | 관리자 설정 또는 공개 게시본 없음 |
| `STORE_FOOTER_020` | 409 | 버전·최신 리비전·동시 생성/수정 충돌 |
| `STORE_FOOTER_100` | 400 | JSON·type·locale 등 일반 요청 오류 |
| `STORE_FOOTER_101`~`109` | 400 | 순서대로 버전, 저작권, 목록, 유형, 표시명, URL, 순서, 노출, 리비전 번호 |
| `STORE_FOOTER_198`·`199` | 400 | 파라미터 형식·필수 값 오류 |

어드민 401/403은 공통 보안 오류를 따른다. 쓰기 감사는 `STORE_FOOTER_DRAFT_SAVE`·`STORE_FOOTER_PUBLISH`,
JWT 관리자 ID, 고정 대상 `store-footer:1`, 성공/실패만 기록한다. URL·표시명·저작권·연락처·요청 본문은
감사·진단 로그에 넣지 않는다. 저장 트랜잭션과 응답 조립 뒤 성공을 기록하며 감사 실패는 업무 결과를 바꾸지 않는다.
커밋 뒤 응답 유실·Presenter 실패는 실제 반영 가능성이 있으므로 GET·이력으로 확인한다.

FE #600은 초안 미리보기를 저장/게시 없이 form state로 수행한다. 문구는 텍스트로 escape하고,
아이콘 링크는 locale 표시명으로 접근 가능한 이름을 제공한다. 외부 anchor는
`target="_blank" rel="noopener noreferrer"`와 새 창 안내를 사용한다. FE는 안전 URL 형식을 렌더링 전에도 확인한다.
회사/CS는 #566, 정책 링크는 #569의 게시 API와 조합한다. 푸터 미등록·장애 때 초안을 대체 노출하지 않고
채널·저작권 부분만 숨긴다. 공개 조회 캐시와 고객사 실정보 번들은 두지 않는다.

## 영속성·배포·검증

신규 `operations.store_footer`(singleton root/버전·포인터), `store_footer_revision`(불변 문구·작성/첫 게시 이력),
`store_footer_link`(리비전별 순서·유형·문구·URL·노출) 세 테이블을 쓴다. JPA 연관관계 없이 raw FK 컬럼 주석,
`uk_`·`ck_` 제약을 둔다. Hibernate PostgreSQL 17 생성 결과를
`service/operations-service/src/test/resources/ddl/operations-schema.sql`에 반영하고 PR 운영 DDL에 새 테이블만 싣는다.
운영 DDL 적용 후 배포하며 기존 데이터 이관과 새 Vault 비밀은 없다. Gateway의 기존 `/api/v1/store/**`와
`/api/v1/admin/store/**` operations 라우팅을 재사용한다.

도메인 URL/버전 테스트, H2·실제 PostgreSQL 조회·제약·롤백·경합, 실제 JWT 권한·봉투·비공개·감사·
공개 필드 허용 목록, DDL 스냅숏과 operations-service `cleanTest/check`를 수행한다. Docker 없는 로컬의
PostgreSQL skip은 CI 실제 실행과 구분한다.
