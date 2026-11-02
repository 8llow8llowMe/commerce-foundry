# 상담 지식 게시 정책 (#469 PR-A)

support-service knowledge가 FAQ·정책·응답 템플릿의 모델·게시 버전·영속성을 소유한다.
어드민 HTTP와 관리자 쓰기 감사는 PR-B에서 제공한다. 내부 검색 랭킹은 PR-C 계약을 따른다. 언어 대체, AI·원격 진입은 후속이다.
문서 유형·언어는 고정하며 최신 리비전과 현재 게시 리비전 포인터를 분리한다. 새 초안은 기존 게시본을 보존한다.
제목 200자·본문 8,000자, 카테고리·유효기간을 검증한다. DRAFT만 편집·승인하며 승인 이후 내용은 불변이고 수정은 새 초안이다.
APPROVED 중 최신 리비전만 게시하며 validFrom <= 현재시각 < validTo(없으면 무기한)를 만족해야 한다.
게시 교체는 이전 게시본을 ARCHIVED로 바꾼다. 현재 게시본의 동일 게시 요청만 멱등이며 ARCHIVED 재게시를 거절한다.
게시 해제는 포인터를 지우고 해당 리비전을 ARCHIVED로 바꾼다.

KnowledgeProcessor가 작업별 단일 트랜잭션을 연다. 문서·리비전 모두 Long @Version을 사용한다.
초안 생성·게시·현재 게시본 해제는 문서 번호/포인터 실제 변경 후 saveAndFlush를 먼저 호출하고 최대 두 리비전을 저장한다.
리비전 저장 실패도 전체 롤백한다. 어댑터는 실제 flush 낙관 충돌을 SUPPORT_004(409)로 번역한다.
편집·승인은 리비전 버전으로 직렬화한다. 초안 생성과 과거 리비전 승인이 경합하면 과거 APPROVED가 남는 것은 허용한다.
게시의 최신 번호 검사와 루트 버전 검사가 과거 승인본 게시를 차단한다. 벌크 UPDATE·비관 락·JPA 연관관계는 사용하지 않는다.

조회는 현재 게시 포인터·PUBLISHED 상태·정확한 언어·유효기간을 모두 만족하는 최대 100개 한 페이지다.
키워드 검색·랭킹은 아래 PR-C 계약을 따른다. 유사도·다른 언어 대체는 후속이다. PostgreSQL 17 DDL 스냅숏은 운영 DDL 원본이자 validate 테스트 init script다.
H2·PostgreSQL 공통 계약으로 실제 JPQL, 초안의 게시본 보존, 원자 롤백, 멱등, 버전 경합, 유효기간·언어·유일 키를 검증한다.

## 관리자 계약 (#469 PR-B)

모든 `/api/v1/admin/support/knowledge` 진입은 ADMIN 권한을 요구한다. 승인·감사의 행위자는 인증 주체에서만 읽는다.
GET 목록은 optional type/locale, page=0, size=20(최대100), 문서 ID 내림차순이며 본문 없는 최신 리비전 요약과 PageResponse를 반환한다.
요청 본문·필터의 enum wire 값은 type=`FAQ|POLICY|TEMPLATE`, locale=`KO|EN`(한국어/영어, 대문자 코드)이다. category는 Swagger enum을 따른다.
GET `/{documentId}`는 최신 리비전 상세와 문서 유형·언어·최신/게시 번호·문서/리비전 버전을 반환한다. 식별자는 문자열, enum은 metadata다.
POST는 type/locale/content로 문서와 첫 DRAFT를 만들고 201을 반환한다. PUT `/{documentId}`는 revisionNumber/expectedVersion/content로 최신 DRAFT 전체를 편집한다.
POST `/{documentId}/revisions`는 expectedDocumentVersion/content로 새 DRAFT를 만들고 201을 반환한다.
POST `/{documentId}/approve`는 revisionNumber/expectedVersion으로 최신 DRAFT를 승인한다.
POST `/{documentId}/publish`는 revisionNumber/expectedVersion/expectedDocumentVersion으로 최신 APPROVED를 게시한다.
DELETE `/{documentId}`는 본문 없이 필수 query revisionNumber/expectedVersion/expectedDocumentVersion으로 해당 리비전을 soft archive하고 표준 200 봉투를 반환한다.
현재 게시본의 동일 게시 및 이미 ARCHIVED인 대상의 삭제는 버전 검사 전 성공한다. 그 외 오래된 버전은 SUPPORT_004(409)다.
초기 생성 POST는 멱등성을 보장하지 않는다. 새 초안 재시도는 이전 문서 버전으로 409를 받으므로 재조회 후 판단한다.
content는 title(공백 불가/최대200), body(공백 불가/최대8000), category/validFrom 필수, validTo는 validFrom보다 뒤다.
제목과 본문은 plain text다. FE는 이스케이프된 텍스트로 표시하고 HTML로 해석하거나 렌더링하지 않는다.
필드 검증은 SUPPORT_1xx, 형식·필수 query는 SUPPORT_198/199로 400 필드 오류 봉투를 반환한다.
읽기는 root/latest revision의 명시적 JPQL projection join과 동일 필터 count를 사용한다. 목록 조합에 단건 반복 조회가 없다.
쓰기 성공·RuntimeException 실패(404/409/5xx 포함)는 서비스 내 adminaudit Processor와 AdminAuditLog에 기록한다.
감사에는 action/인증 actor/문서 ID/결과만 기록한다. 제목·본문·요청 객체·예외 메시지는 기록하지 않는다.

## 내부 키워드 검색 계약 (#469 PR-C)

같은 서비스의 호출자는 HTTP 없는 KnowledgeSearchUseCase → KnowledgeSearchFacade → KnowledgeProcessor.search를 사용한다.
Facade가 기존 Clock을 요청당 한 번 읽고 cutoff를 Processor에 전달한다. 호출 command에는 사용자 cutoff가 없다.
읽기 전용 트랜잭션 안에서 현재 게시 포인터와 리비전을 단일 SELECT로 읽으므로 문장 단위 스냅숏으로 충분하다.
외부 AI·Vector DB·상품 원격 조회·캐시는 없으며 저장소 오류는 전파한다. 엔티티·DDL·인덱스 변경은 없다.

question은 null/blank 불가, 원문과 NFKC 결과가 각각 UTF-16 500자 이하여야 한다. NFKC 후 Locale.ROOT 소문자로 바꾸고
Unicode letter/digit 이외 문자를 구분자로 삼는다. 순서대로 중복을 제거한 처음 16개 토큰만 사용하고 17번째 이후는 무시한다.
사용할 토큰은 각각 UTF-16 64자 이하여야 하며 잘라내지 않는다. 유효 토큰 0개, 필수 locale(KO|EN) 누락,
필수 topN(1~20) 범위 위반은 기존 INVALID_INPUT(SUPPORT_002)의 고정 문구로 거절한다. category는 선택이다.
질문·검색 토큰·근거 제목·본문은 로그나 toString에 노출하지 않는다.

정확한 locale 및 선택 category를 먼저 제한하고 현재 게시 포인터가 가리키는 PUBLISHED 중 승인자·승인시각이 있는 리비전만 검색한다.
validFrom <= cutoff < validTo(종료 null은 무기한)를 만족해야 한다. 토큰별 LOWER(title) 부분문자열 포함 3점,
LOWER(body) 포함 1점을 합산한다(양쪽 포함 4점, 출현 횟수 무관). 0점은 제외하고 DB에서 score 내림차순,
documentId 내림차순으로 정렬한 뒤 topN을 적용한다. LIKE는 바인드 파라미터와 명시적 ! escape를 사용한다.
언어나 category가 맞는 근거가 없으면 빈 목록이며 영어 자동 fallback은 없다. DB 원문에는 LOWER만 적용하므로
완전한 Unicode 정규화 대칭, 형태소·동의어·의미 검색을 보장하지 않는다. 결과는 문서/리비전 ID·번호, 제목·본문,
유형·언어·category·score·유효기간을 담는 내부 Info이며 HTTP 계약에는 영향이 없다.
승인 정보 없는 PUBLISHED는 DB 상태 메타데이터 제약으로 생성할 수 없고 검색 조건도 두 not-null을 방어적으로 검사한다.
