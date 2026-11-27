# 스토어 브랜드 이미지 (#598)

operations-service가 배포별 헤더 로고와 사이트 아이콘의 초안, 게시, 이력, 되돌리기를 관리한다. 로고는 기본 `logo`와 어두운 배경용 `darkLogo`를, 아이콘은 `icon`을 둔다. 각 필드는 독립적으로 비울 수 있다. 런타임에 등록한 이미지만 사용한다.

## API

| 메서드 · 경로 | 권한 | 계약 |
| --- | --- | --- |
| `GET /api/v1/admin/store/brand-assets` | SETTINGS_READ | 전체 초안, 현재 게시본, 다음 편집에 쓸 `version`. 최초에는 버전 0, 빈 초안과 null 게시본 |
| `PUT /api/v1/admin/store/brand-assets` | SETTINGS_MANAGE | `expectedVersion`과 `assets`를 보내 전체 초안을 교체. 각 필드에 `assetId`와 `altText`를 넣거나 null/생략하여 제거 |
| `POST /api/v1/admin/store/brand-assets/uploads/{kind}` | SETTINGS_MANAGE | `LOGO` · `DARK_LOGO` · `ICON`에 대해 `image/png` 본문을 올리고 `assetId`와 관리자 미리보기 URL 반환 |
| `GET /api/v1/admin/store/brand-assets/uploads/{kind}/{assetId}` | SETTINGS_READ | 인증된 관리자 미리보기 |
| `POST /api/v1/admin/store/brand-assets/publication` | SETTINGS_MANAGE | `expectedVersion`의 현재 초안을 게시. `restoreVersion`을 넣으면 해당 과거 게시본을 새 버전으로 게시하고 현재 초안은 보존 |
| `GET /api/v1/admin/store/brand-assets/publications?page=0&size=10` | SETTINGS_READ | 게시·복구 이력, 최신순. 페이지 크기 1~20 |
| `GET /api/v1/store/brand-assets` | 익명 | 현재 게시본의 `version`, 자산별 `url`과 `altText`. 미게시 상태는 버전 0과 null 필드 |
| `GET /api/v1/store/brand-assets/images/{version}/{kind}/{assetId}` | 익명 | 해당 버전이 현재 게시본이며 그 종류의 참조와 일치할 때 PNG 반환 |

JSON 성공·실패는 공통 응답 봉투를 사용한다. 이미지 조회는 PNG 바이트를 반환한다. 버전과 작성자 ID는 10진 문자열이며 입력 버전은 0 이상인 10진 문자열이다. UUID 자산 ID는 업로드 응답에서 받는다. 로고 대체 텍스트는 필수이며 앞뒤 공백 제외 1~160자다. 아이콘은 0~160자를 받는다. 모두 제어문자를 금지한다.

FE #601은 기존 BFF에서 상대 API URL을 해석하고 미리보기에 관리자 세션을 사용한다. BFF가 본문을 버퍼링하므로 업로드 UI에서도 크기를 먼저 검사하고, 운영 인그레스에는 같은 본문 한도를 적용할 수 있다. 모든 편집과 게시에는 최신 버전이 필요하다. 경합 시 하나의 변경만 반영하고 나머지는 409 `STORE_BRAND_020`을 반환한다. 응답을 잃었거나 충돌하면 GET으로 현재 상태를 확인한다. 이전 공개 주소, 등록되지 않은 UUID, 초안에서만 사용된 UUID는 익명으로 읽을 수 없다. 등록 전, null 필드, 자산 요청에 실패한 경우 프론트엔드는 스토어 이름 및 배포별 아이콘 기본 표시를 사용한다. 어두운 배경에서는 `darkLogo`가 없으면 `logo`를 사용할 수 있다.

설정 응답과 미리보기는 `no-store`다. 공개 PNG 주소는 게시 버전을 포함하고 브라우저 캐시 유효 시간은 최대 5분이다. 게시·되돌리기 뒤 설정을 다시 요청하면 새 이미지 주소 또는 null을 받는다. 게시 제거·되돌리기 후 이전 URL의 신규 요청은 원본 서버에서 404다. 기존 캐시의 사본은 유효 시간까지 남을 수 있다.

## 이미지와 저장

상품용 브라우저 서명 업로드는 상품 권한과 키 형식, 공개 이미지 저장소를 사용한다. 브랜드는 스토어 설정 권한으로 요청 본문을 검증하고, operations-service의 기존 storage-core S3 클라이언트로 비공개 `store-brand/{kind}/{UUID}.png`에 저장한다. 버킷을 배포별로 분리하고 익명 접근을 차단한다. 기존 operations의 `STORAGE_ENABLED=true` 및 서버 자격증명의 `store-brand/` 경로 PUT·HEAD·GET 권한이 필요하다. 같은 버킷을 사용하는 작업 결과의 수명 주기는 `job-results/`에 한정한다. 브랜드 키에 만료 정책을 적용하면 복구할 수 없으므로 게시 이력이 참조하는 객체를 보존한다. 업로드된 미참조 객체의 정리는 후속 운영 과제다.

본문 상한은 1 MiB다. 로고는 너비 16~1024, 높이 16~512 픽셀이고 아이콘은 32~512 픽셀 정사각형이다. 포맷 서명 및 실제 디코딩을 검증하고, 완전히 투명한 픽셀과 표시할 픽셀이 함께 있는지 확인한다. 전체가 투명한 이미지는 거절한다. PNG 픽셀을 새 이미지로 다시 인코딩하여 원본 메타데이터와 남은 바이트를 버린다. SVG와 그 밖의 MIME은 받지 않는다. 저장 요청에는 URL이나 파일 경로 필드가 없으며, 같은 종류로 업로드되어 실제 존재하는 자산만 연결한다. 게시·되돌리기 직전에도 자산 존재를 재확인한다.

## 오류와 운영

- 400 `STORE_BRAND_100`은 요청 형식, `STORE_BRAND_101`은 버전, `STORE_BRAND_102`는 대체 텍스트, `STORE_BRAND_103`은 자산 참조를 확인해야 한다.
- 404 `STORE_BRAND_001`은 조회할 이미지나 되돌릴 게시본이 없을 때, 409 `STORE_BRAND_020`은 버전 충돌에 반환한다.
- 413 `STORE_BRAND_104`는 용량 초과, 415 `STORE_BRAND_105`는 지원하지 않는 Content-Type, 422 `STORE_BRAND_106`은 이미지 내용·해상도·투명도 검증 실패다.
- 503 `STORE_BRAND_030`은 스토리지 비활성 또는 장애다. 업로드와 연결 확인에 실패하면 초안 및 게시 버전은 바뀌지 않는다.
- 운영에는 PostgreSQL 스냅숏의 `store_brand_revision` 증분 DDL을 먼저 적용한다. 각 변경은 버전으로 식별되는 불변 행 하나를 추가한다. 최신 행의 초안·게시 포인터와 게시 이력의 작성자·시각을 사용한다.
