# 스토어 법적 문서 (#569)

operations-service `legaldocument`가 고객사별 이용약관·개인정보처리방침 초안, 게시본과 이력을 소유한다.
현재 배포의 단일 스토어 설정이며 멀티 테넌트 키를 받지 않는다. 브랜드·사업자 실정보를 코드나 템플릿에 넣지 않는다.
`type=TERMS|PRIVACY`, `locale=ko|en`의 네 조합을 지원한다. 둘은 경로·화면 분기에 쓰는 **원시 문자열 제어 코드**로 metadata 객체가 아니다.
허용 값은 대소문자를 구분하고 언어 fallback은 없다. locale query의 기본값은 `ko`다.

## ADMIN 계약

모두 `hasRole('ADMIN')`, 표준 `Response` 봉투와 `Cache-Control: no-store`를 사용한다.
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
