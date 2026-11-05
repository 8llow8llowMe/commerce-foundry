# 전시 · 룩 이미지 업로드 (#508)

product-service의 mediaupload 컨텍스트가 어드민 공용 이미지 업로드 발급과 확정을 맡는다. 기존 전시 · 룩 저장 API는 공개 URL을 받는다.
상품 등록 · 수정의 이미지 첨부 계약(#9)과 별도다. DB 변경, 발급 원장, 참조 추적, GC 스케줄러는 없다.

## API와 FE 흐름

1. `CATALOG_MANAGE` 권한이 있는 운영자(ADMIN · OWNER) JWT로 `POST /api/v1/admin/displays/image-uploads`에 `{contentType, contentLength}`를 보낸다.
2. 201 `Response` 봉투의 `{uploadKey, uploadUrl, method, headers, expiresAt}`를 읽고, 반환한 메서드와 헤더로 파일을 스토리지에 직접 PUT한다.
3. 같은 운영자 JWT로 `POST /api/v1/admin/displays/image-uploads/{uploadKey}/confirm`을 호출한다. 본문은 없다.
4. 200 `Response` 봉투의 `{publicUrl, contentType, contentLength}`에서 publicUrl을 전시 MEDIA의 pcUrl/mobileUrl 또는 룩 image.url에 넣는다.

발급 · 확정 성공 응답은 `Cache-Control: no-store`다. 서명 URL, 헤더, 업로드 키를 로그에 남기지 않는다.
치수 width/height는 기존 선택 필드다. 서버가 파일의 치수를 해석하지 않으므로 모르면 생략한다.
JPEG(image/jpeg), PNG(image/png), WebP(image/webp)만 지원하며 파일은 1바이트~10MiB다.
MIME은 앞뒤 공백 제거 · 소문자 정규화 후 서명한다. 영상, 포스터, SVG, GIF, 기타 파일은 이 API의 지원 대상이 아니다.
확정 전에 전시/룩을 저장하지 않는다. FE 계약 검토 담당은 seonghoho다.

## 소유권과 검증

클라이언트에는 lowercase canonical UUID와 jpg/png/webp 확장자로 된 basename만 돌려준다.
source는 서버가 JWT actorId로 `uploads/display-media/{actorId}/{UUID}.{ext}`를 재구성한다.
다른 관리자의 키를 써도 자신의 namespace만 조회하므로 없는 객체와 같은 404다.
임의 사용자 ID, 전체 스토리지 경로, 외부 URL을 입력받지 않는다.

확정은 HEAD 존재 여부, 실제 크기, 키 확장자와 HEAD MIME 일치, 비어 있지 않은 strong quoted ETag를 검증한다.
magic bytes는 조건부 range로 읽고 JPEG/PNG/WebP 판별 결과를 키 형식 · HEAD MIME과 비교한다.
range와 copy 모두 HEAD의 opaque ETag를 따옴표까지 그대로 조건에 전달한다.
두 호출 중 원본이 바뀌면 409이며 다시 확정해야 한다. 검증하지 않은 바이트를 공개하지 않는다.

공개 키는 `products/media/{actorId}/{UUID}/{SHA256(opaque ETag UTF-8)}.{ext}`다.
`media`는 숫자 상품 ID와 구분되는 예약 segment다. 이 해시는 ETag의 namespace 구분값이며 콘텐츠 checksum이 아니다.
같은 ETag로 순차 · 동시 확정하면 같은 URL과 같은 representation을 만들고, 다른 ETag는 새 URL을 만든다.
COPY는 검증된 정규 MIME과 `public, max-age=31536000, immutable`로 메타데이터를 교체한다.
복사 전에 URL 500자 제한과 기존 MediaUrlPolicy를 확인한다. storage.public-base-url과 store.media.public-base-url이 어긋나면 503이며 복사하지 않는다.

| 상태 | 코드 |
|---|---|
| 잘못된 basename | HTTP 경로 검증은 400 MEDIA_UPLOAD_106, 내부 호출의 방어 검증은 MEDIA_UPLOAD_002 |
| 형식 · 크기 위반 | 400 MEDIA_UPLOAD_003 · MEDIA_UPLOAD_004 |
| 객체 없음 · 다른 관리자 namespace | 404 MEDIA_UPLOAD_001 |
| range/copy ETag 변경 | 409 MEDIA_UPLOAD_005 |
| 스토리지 비활성 | 503 MEDIA_UPLOAD_006 |
| strong ETag 없음 · URL 설정 불일치 · 기타 스토리지 실패 | 503 MEDIA_UPLOAD_007 |
| 깨진 JSON | 400 PRODUCT_100 (서비스 공통 ValidationErrorSupport) |
| 요청 MIME 필수 · 형식, 길이 필수 · 최소 · 최대, 경로 키 형식 | MEDIA_UPLOAD_101~106 |

## 보관과 운영

확정 후 source를 즉시 삭제하지 않는다. COPY 응답이 유실되어도 동일 업로드 키로 재시도할 수 있어야 한다.
source는 기존 `uploads/` 1일 lifecycle(#451)의 대상이다. 운영자가 실제 MinIO/S3에 해당 정책을 적용해야 하며
애플리케이션이 정책을 설치하거나 삭제를 실행하지 않는다. 1일 경과는 삭제 대상이 되는 시점이며 스토리지 scan에 따라 실제 삭제는 지연될 수 있다.
정책이 미설정이면 미확정/확정 원본 모두 계속 쌓인다. 배포 후 lifecycle 적용과 삭제 추이를 운영에서 확인한다.

공개 copy는 참조 추적이 없어 미참조 · 이미지 교체 · 전시/룩 삭제 이후에도 자동 삭제하지 않는다.
누적 저장 비용을 관찰하고 보관/정리 정책을 별도로 운영한다. 고객 조회나 DB 행 삭제가 파일 삭제를 의미하지 않는다.
브라우저 PUT을 위해 스토리지 CORS에 실제 어드민 origin, PUT 및 반환된 서명 헤더를 허용해야 한다.
공개 미디어는 사용자 인증 쿠키와 분리된 origin으로 제공하고, CDN/스토리지에서 올바른 Content-Type과
`X-Content-Type-Options: nosniff`를 설정한다. 앱 코드가 이 외부 설정까지 적용했다고 간주하지 않는다.

## 감사와 계층

Controller → AdminUseCase → AdminFacade → Processor → StoragePort/Adapter, Info → Presenter → Response를 따른다.
Facade는 NOT_SUPPORTED로 호출자의 DB 트랜잭션도 중단하여 외부 I/O 동안 트랜잭션을 잡지 않는다.
storage-core 타입은 out adapter 내부에서만 쓴다. 스토리지 예외 원인 · SDK 메시지는 응답/로그에 넣지 않는다.
감사는 기존 ProductAdminAuditProcessor만 사용한다.
`MEDIA_IMAGE_UPLOAD_ISSUE`, `MEDIA_IMAGE_UPLOAD_CONFIRM`은 actorMemberId, action, target, result 네 칸을 기록한다.
target은 `media-upload:<UUID>`이고 식별자가 없는 실패는 `-`다. URL, ETag, 전체 키, MIME, 바이트, 헤더는 감사에 넣지 않는다.
요청 검증 실패 · 401 · 403은 UseCase에 도달하지 않으므로 업무 감사가 없다.

## 검증 범위

MediaImageStorageAdapterTest는 형식 · 크기 · ETag · 조건부 range/copy · 설정 불일치 · 재시도 · 동시 확정과 delete 무호출을 검증한다.
MediaImageUploadKeyTest와 MediaImageUploadAdminFacadeTest는 basename, MIME 정규화, 실패 번역, 감사와 민감정보 출력 방지를 검증한다.
MediaImageUploadFlowIntegrationTest는 실제 signed JWT와 H2로 발급/확정 후 치수 없이 전시 · 룩에 저장하고 고객 조회에 같은 URL이 나오는 흐름을 검증한다.
이 테스트는 ObjectStorageClient를 대체한다. 실제 스토리지의 CORS, CDN 헤더, lifecycle 실행 및 운영 삭제 시점은 검증하지 않는다.

MediaImageStorageRoundTripTest는 Docker가 있을 때 실제 MinIO와 서명 PUT → 확정 COPY → 익명 공개 GET을 왕복하고,
기존 products/* 공개 정책으로 products/media/*는 200, uploads/display-media/*는 403인 것을 검증한다.
서명 조건/바이트/MIME/cache와 원본 보존을 검증하는 magic-byte fixture이며 전체 이미지 디코딩 · 치수 추출 검증은 아니다.
Docker 없는 로컬은 건너뛰고 CI에서는 Docker가 필수다. 공개 factory의 HTTP loopback 제한을 따르므로
Testcontainers가 비루프백 원격 Docker 호스트를 반환하는 환경에서는 별도의 HTTPS/네트워크 구성이 필요하다.
