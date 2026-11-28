# 스토어 법적 문서 (#569)

operations-service `legaldocument`가 고객사별 이용약관·개인정보처리방침 초안, 게시본과 이력을 소유한다.
현재 배포의 단일 스토어 설정이며 멀티 테넌트 키를 받지 않는다. 브랜드·사업자 실정보를 코드나 템플릿에 넣지 않는다.
`type=TERMS|PRIVACY`, `locale=ko|en`의 네 조합을 지원한다. 둘은 경로·화면 분기에 쓰는 **원시 문자열 제어 코드**로 metadata 객체가 아니다.
허용 값은 대소문자를 구분하고 언어 fallback은 없다. locale query의 기본값은 `ko`다.

## ADMIN 계약

조회 4종은 `AdminAuthority.SETTINGS_READ`, 초안 저장 · 게시는 `SETTINGS_MANAGE`(둘 다 ADMIN · OWNER, `security-guide.md` §3-1)이고 표준 `Response` 봉투와 `Cache-Control: no-store`를 사용한다.
쓰기 행위자는 실제 access JWT의 memberId로만 정하며 요청이 지정할 수 없다.

| 경로 | 요청 | 응답 dataBody |
|---|---|---|
| `GET /api/v1/admin/store/legal-documents` | 없음 | 등록한 문서 Summary 배열, type·locale순. 미등록 조합은 생략 |
| `GET /api/v1/admin/store/legal-documents/{type}/{locale}` | 경로 제어 코드 | Detail |
| `PUT /api/v1/admin/store/legal-documents/{type}/{locale}/draft` | `{expectedVersion,title,body}` | 새 리비전의 Detail, 200 |
| `POST /api/v1/admin/store/legal-documents/{type}/{locale}/publish` | `{expectedVersion,revisionNumber,reviewConfirmed}` | 현재 최신 리비전의 Detail, 200 |
| `GET /api/v1/admin/store/legal-documents/{type}/{locale}/revisions` | page=0, size=10 (1~20) | 리비전 번호 내림차순 `PageResponse<Revision>` |
| `GET /api/v1/admin/store/legal-document-templates/{type}` | locale=ko | Template, 저장·게시 없음 |

- Summary: `{type,locale,latestRevision,publishedRevision,version}`. 미게시 publishedRevision은 null이다.
- Detail: `{document:Summary,revision:Revision}`. revision은 **항상 최신 리비전**이다. 최신본이 게시 상태이면 게시 메타데이터도 포함한다.
- Revision: `{revisionNumber,title,body,createdBy,createdAt,publishedBy,publishedAt}`. 관리자 식별자는 문자열, 시각은 UTC ISO이며 미게시 publishedBy/publishedAt은 null이다.
- 신규 저장·게시 시각은 Processor에서 DB `timestamp(6)` 정밀도에 맞춰 마이크로초 미만을 버린다. 반올림으로 다음 초로 넘기지 않으며,
  최초 응답·DB 재조회·반복 게시에서 같은 시각을 반환한다. 기존 리비전의 작성·최초 게시 시각은 다시 계산하거나 변경하지 않는다.
- Template: `{templateId,version,type,locale,title,body,checklist,guidance}`. templateId는 `legal-{terms|privacy}-{ko|en}`, 초기 version은 1이다.
- 초안 저장은 title·body 전체 교체 입력이며 항상 새 불변 리비전을 추가한다. 최초 expectedVersion은 생략/null, 기존 문서는 조회한 0 이상 버전이 필요하다.
  같은 본문 재전송도 새 저장이므로 멱등하지 않다. 응답을 잃으면 상세·이력을 재조회하고 재저장 여부를 판단한다.
- title은 공백만으로 구성할 수 없는 1~200자이며 제어문자가 없어야 한다. body는 공백만으로 구성할 수 없는 1~50000자이고 CR/LF/TAB 외 제어문자를 거부한다.
  길이는 Java 문자열 길이(UTF-16 코드 단위) 기준이다. HTML 형태도 단순 텍스트로 저장한다.
- 게시는 expectedVersion 필수(0 이상), revisionNumber 양수, reviewConfirmed=true 필수다. 최신 리비전만 새로 게시할 수 있다.
  title/body에 `{{` 또는 `}}`가 남으면 거부한다. 미치환 검사는 법률 검증이나 실제 내용 검토를 대신하지 않는다.
- 현재 publishedRevision을 다시 게시하면 오래된 expectedVersion도 변경 없이 성공한다. 이후 새 초안이 있어도 공개 포인터·최초 게시자·게시 시각은 그대로이며 응답은 GET 상세와 같은 최신 초안이다.
  현재 게시본이 아닌 이전 번호를 직접 재게시할 수 없다. 이전 본문 복원은 이력에서 내용을 읽고 새 초안으로 저장한 뒤 게시한다.
- `@Version` root 갱신과 revision 삽입/게시 기록을 adapter 단일 트랜잭션에서 처리한다. 최초 생성 유니크 키 경합·편집/게시 경합은 409다.
  실패 시 root와 revision을 함께 롤백한다. 409 후 상세를 재조회해 운영자가 변경을 확인하고 다시 요청한다. 자동 덮어쓰기하지 않는다.

## 고객 공개 계약

다음 GET은 익명 접근 가능하며 표준 봉투와 `Cache-Control: no-store`를 사용한다.

| 경로 | 응답 dataBody |
|---|---|
| `GET /api/v1/store/legal-documents?locale=ko` | 현재 게시본 Summary 배열 `{type,locale,revisionNumber,title,publishedAt}` (type순, 본문 없음) |
| `GET /api/v1/store/legal-documents/{type}?locale=ko` | 현재 Document `{type,locale,revisionNumber,title,body,publishedAt}` |
| `GET /api/v1/store/legal-documents/{type}/revisions/{revisionNumber}?locale=ko` | 실제 게시했던 Document |

게시본 없는 목록은 빈 배열이다. 현재본 미게시·문서 없음·언어 미게시·존재하지 않거나 실제 게시되지 않은 revision은 모두 404다.
초안 번호를 알아도 공개 조회할 수 없다. 공개 DTO는 관리자 ID·작성 시각·편집 버전·포인터를 포함하지 않는다.
FE는 title/body를 **텍스트 노드로 렌더링하고 escape**한다. `dangerouslySetInnerHTML`이나 HTML 해석에 넘기지 않는다.
문서 본문·초안·버전 응답은 BFF/브라우저/중간 캐시에 보관하지 않는다. 공개 페이지와 편집기는 FE #570 범위다.

## 오류

| 코드 | HTTP | 의미 |
|---|---|---|
| LEGAL_DOCUMENT_001 | 404 | 문서/공개 게시본/게시 이력 없음 |
| LEGAL_DOCUMENT_020 | 409 | expectedVersion 불일치, 최신이 아닌 게시, 동시 갱신 |
| LEGAL_DOCUMENT_021 | 400 | 도메인 게시 검토 확인 누락 |
| LEGAL_DOCUMENT_022 | 400 | 제목·본문 미치환 표시 |
| LEGAL_DOCUMENT_100 | 400 | JSON 오류·지원하지 않는 type/locale 등 요청 오류 |
| LEGAL_DOCUMENT_101 | 400 | expectedVersion 필수/음수 (최초 초안 null 예외) |
| LEGAL_DOCUMENT_102 | 400 | 제목 필수·길이·제어문자 |
| LEGAL_DOCUMENT_103 | 400 | 본문 필수·길이·제어문자 |
| LEGAL_DOCUMENT_104 | 400 | 리비전 번호 양수 |
| LEGAL_DOCUMENT_105 | 400 | HTTP 요청 reviewConfirmed 필수·true |
| LEGAL_DOCUMENT_106 | 400 | page/size 범위 |
| LEGAL_DOCUMENT_198 | 400 | 파라미터 타입 불일치 |
| LEGAL_DOCUMENT_199 | 400 | 필수 파라미터 없음 |

ADMIN 무인증은 401, CUSTOMER는 403이며 공통 보안 오류를 따른다. 도메인·검증 오류에도 no-store를 적용한다.
감사는 `LEGAL_DOCUMENT_DRAFT_SAVE`·`LEGAL_DOCUMENT_PUBLISH`, 실제 actorMemberId,
고정 대상 `legal-document:{terms|privacy}-{ko|en}`와 성공/실패만 기록한다. 제목·본문·회원 개인정보를 감사에 넣지 않는다.
두 행위는 프로필과 함께 `OperationsAdminAuditProcessor`로 부분 통합했다(#163 R1). JOB/CAMPAIGN은 이 범위에 포함하지 않는다.
저장소 트랜잭션 완료와 Presenter 조립까지 성공해야 `SUCCESS`이며, Presenter 실패는 커밋 후에도 기존대로 `FAILURE`다.
HTTP 본문 검증·인증·인가 거절은 감사하지 않고, UseCase 내부 지원하지 않는 type/locale 오류는 `target=- result=FAILURE`로 기록한다.
동기 감사·진단 로거의 RuntimeException은 업무 응답과 원래 예외를 바꾸지 않는다(`security-guide.md` §11-4).

## 템플릿 출처와 한계

템플릿은 직접 작성한 **질문·빈칸 중심 시작 구조**이며 특정 고객사나 제3자 약관의 복제본이 아니다.
개인정보 항목 구성 참고는 개인정보보호위원회 [개인정보 처리방침 작성지침(2026.4.) 안내](https://pipc.go.kr/np/cop/bbs/selectBoardArticle.do?bbsId=BS217&mCode=D010030020&nttId=12018)다.
이용약관은 계약·취소·분쟁 등 작성 질문 구조로 제공한다. 공식 자료가 모든 판매 국가·사업 모델에 대한 적합성을 보장하지 않는다.
완성 법률 문서·법률 자문·법적 적합성 보장이 아니며 영어 구조도 법률 번역 보장이 아니다. 관리자가 실제 처리 사실과 최신 자료·전문가 검토를 확인해야 한다.
체크리스트는 검토 안내이고 서버가 개별 항목의 사실 여부를 자동 판정하지 않는다. reviewConfirmed=true와 별도 게시 요청 전에는 공개되지 않는다.
템플릿 조회는 DB·스토어 프로필·외부 링크 자동 수집·AI 호출·자동 저장·자동 게시를 하지 않는다.

## 배포와 검증

`operations.legal_document` root는 (type,locale) 유니크 키·latest/published 포인터·낙관 버전을 갖고,
`operations.legal_document_revision`은 (document_id,revision_number) 유니크 키와 불변 title/body·작성 기록·최초 게시 기록을 갖는다.
JPA 연관관계 없이 raw FK를 쓰고 내부·외부 FK는 컬럼 주석으로 명시한다.
운영 적용 DDL의 정본은 Hibernate 생성 스냅숏 `service/operations-service/src/test/resources/ddl/operations-schema.sql`의 두 테이블이며,
운영자가 적용 후 배포한다. 신규 Vault 키는 없다.

서비스 직접 요청의 계약 테스트는 게이트웨이 배포를 보증하지 않는다. 게이트웨이 `/api/v1/store/**`와 `/api/v1/admin/store/**`의
operations 라우팅은 #566과 함께 배포해야 한다. #569에서 중복 route를 만들지 않는다.
H2·PostgreSQL persistence 계약은 실제 조회/롤백·불변성·제약을 검증하고 PostgreSQL은 최초 저장·저장/게시 경합까지 검증한다.
실제 JWT 흐름은 권한·검증·초안 비공개·필드 허용 목록·감사를 검증한다. Docker 없는 로컬 PostgreSQL 결과는 생략될 수 있어 CI 실행 결과를 별도로 확인한다.

예약 게시, 회원가입/체크아웃 동의 버전 수집, auth의 동의 변경, 자동 번역·법률 검증, AI 자동 게시, 멀티 테넌트 SaaS는 범위 밖이다.

## AI 검토용 초안 생성

모든 경로는 `/api/v1/admin/store/legal-document-drafts/generations` 아래에 있다. 어드민 JWT와 표준 응답 봉투를 사용하고 생성 결과는 `Cache-Control: no-store`로 반환한다.

| 메서드와 상대 경로 | 권한 | 계약 |
|---|---|---|
| `POST` | `SETTINGS_MANAGE` | `Idempotency-Key` 헤더, `{type, locale, templateVersion, confirmedFacts}` 요청, `202`와 문자열 `jobId` |
| `GET /{jobId}` | `SETTINGS_READ` | 요청자 본인의 상태 및 검토용 후보 조회 |
| `POST /{jobId}/review` | `SETTINGS_MANAGE` | 성공 후보의 최초 검토 확인 시각 기록. 반복 요청은 같은 시각 반환 |
| `POST /{jobId}/cancel` | `SETTINGS_MANAGE` | 활성 작업 취소 요청과 현재 상태 반환. 종료 후 재요청은 현재 상태 반환 |

- 요청 키는 16~80자의 영문·숫자·하이픈·밑줄이며 첫 글자는 영문 또는 숫자다. 요청자별 같은 키와 정규화된 입력을 사용하면 종료 상태에서도 원래 작업을 반환한다. 같은 키에 다른 입력은 409다. 재생성은 새 키를 사용한다.
- `type`은 `TERMS` 또는 `PRIVACY`, `locale`은 `ko` 또는 `en`이다. `templateVersion`은 시작 템플릿 응답에서 읽은 정수이며 빠지면 `400 LEGAL_DRAFT_GENERATION_100`이다. `confirmedFacts`는 템플릿에 표시된 빈칸 식별자를 키로 쓰는 객체다. 확인하지 않은 항목은 생략한다. 빈 객체도 허용한다.
- 각 사실은 최대 600 UTF-16 코드 단위의 공개 예정 운영 정보 한 줄이다. 최대 6개이며 HTML·제어문자·템플릿 표시를 거부한다. 회원 원문·비밀값을 입력하지 않는다.
- 응답은 `{jobId, status, notice, candidate, reviewedAt}`이다. `status`는 작업 어드민 응답과 같은 `{code, name, description}` metadata(`QUEUED`·`RUNNING`·`SUCCEEDED`·`FAILED`·`CANCELLED`, 예: `{"code":"SUCCEEDED","name":"성공","description":"실행을 마쳤습니다."}`)다.
- 결과의 `candidate`는 성공 상태에서만 존재한다. `{type, locale, title, body, templateId, templateVersion, unresolvedItems, reviewChecklist}`이며 `type`은 `{code, name, description}` metadata(`TERMS`=이용약관, `PRIVACY`=개인정보처리방침), `locale`은 문자열이다. 관리자는 후보를 기존 문서와 비교하고, 사용할 부분을 편집한 뒤 기존 초안 저장 API에서 버전을 확인하여 별도 저장한다. 게시 API의 최신 리비전·검토 확인·미치환 표시 검사도 적용된다.
- `unresolvedItems`는 확인된 값이 없는 빈칸 식별자다. 본문에는 질문과 `{{빈칸}}`을 남긴다. 모델은 서버가 제공한 절 ID의 순서만 제안한다. 서버는 정확한 순열인지 검사하고 시작 템플릿 및 관리자가 입력한 값으로 제목과 단순 텍스트 본문을 조립한다. 사업자 사실의 진위와 법적 적합성은 운영자가 검토한다.
- 새 생성에는 `OPERATIONS_LEGAL_DRAFT_GENERATION_ENABLED=true`와 공통 `AI_ENABLED=true`가 모두 필요하다. 기본은 꺼져 있으며 기존 작업은 조회할 수 있다. 실행 직전에도 같은 값을 검사하지만, 값은 기동 시 읽으므로 플래그 변경은 재기동 뒤 반영된다.
- 후보는 조회할 때마다 영수증의 입력과 현재 시작 템플릿으로 다시 조립한다(사실값을 더 저장하지 않는다). 시작 템플릿 버전을 올려 배포하면 이전 버전으로 성공한 작업은 `candidate`가 `null`이고 `notice`가 "시작 템플릿이 바뀌어 이전 결과를 다시 만들 수 없습니다."이며, 그 작업의 검토 확인은 `409 LEGAL_DRAFT_GENERATION_004`다. 새 버전으로 다시 생성한다.
- 시작 템플릿의 정본은 legaldocument 의 domain 상수 `LegalDocumentTemplate`(DB·I/O 없음)이다. 어드민 템플릿 조회는 이 값을 Info 로 감싸고, AI 초안 컨텍스트는 다른 컨텍스트 Info 를 거치지 않고 이 값을 직접 읽는다(architecture-guide.md §3 H8). 본문 형식이나 절을 바꾸면 `CURRENT_VERSION` 을 올린다.
- `legal-draft`의 프롬프트 버전은 1, 모델 기본 설정은 `claude-opus-5-5`, 출력 상한은 512토큰이다. 공급자별 모델 해석은 공통 ai-core 설정을 따른다. 프롬프트에는 문서 유형·언어·템플릿 버전과 허용·확인된 절 식별자만 보낸다. 원문 값은 작업 입력, 작업 결과, 원장, 감사에 싣지 않는다. 별도 영수증이 입력과 최초 검토 시각을 저장한다.
- 작업 참조 `job:<id>:<attempt>`로 공통 월 예산 예약과 사용량 정산을 연결한다. 예산 거절, 공급자 실패, 검증 실패 시 후보를 반환하지 않는다. 작업 시도는 1회, 작업 상한은 15분이다. ai-core 전송 재시도·타임아웃 정책은 공통 설정을 따르며 취소·응답 유실·결과 거절 전에 발생한 비용은 공통 원장 정책대로 정산한다.
- 감사 줄에는 행위, 행위자, 작업 식별자, 성공 여부만 기록한다. 검토 확인은 법률 문서 리비전이나 게시 기록을 생성하지 않는다. 영수증과 작업의 서버 보존 기간 동안 동일 키 재생과 후보 조회를 지원한다.

| 코드 | HTTP | 의미 |
|---|---|---|
| LEGAL_DRAFT_GENERATION_001 | 404 | 작업이 없거나 요청자가 다름 |
| LEGAL_DRAFT_GENERATION_002 | 409 | 같은 요청 키에 다른 입력 |
| LEGAL_DRAFT_GENERATION_003 | 409 | 시작 템플릿 버전 불일치 |
| LEGAL_DRAFT_GENERATION_004 | 409 | 검토 확인 가능한 성공 후보 없음 |
| LEGAL_DRAFT_GENERATION_005 | 409 | 취소 요청과 작업 상태 변경 경합 |
| LEGAL_DRAFT_GENERATION_100 | 400 | 입력·경로·요청 키 검증 실패 |
| LEGAL_DRAFT_GENERATION_501 | 503 | 새 생성 기능이 꺼져 있음 |
| LEGAL_DRAFT_GENERATION_502 | 503 | 생성 결과 복원 또는 작업 처리 실패 |

운영 DDL은 Hibernate 스냅숏의 `operations.legal_draft_generation_receipt` 구역을 적용한다. 새 Vault 키는 없다. FE #572는 후보를 텍스트로 비교하고 미확정 항목과 검토 체크리스트를 표시한다. 작업 상태 조회와 후보 적용은 서로 다른 사용자 동작으로 연결한다.
