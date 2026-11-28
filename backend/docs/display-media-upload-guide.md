# 전시 · 룩 미디어 업로드 (#508 이미지 · #796 영상)

product-service의 mediaupload 컨텍스트가 어드민 공용 이미지 업로드와 메인 전시 영상 업로드의 발급 · 확정을 맡는다.
기존 전시 · 룩 저장 API는 공개 URL을 받는다. 상품 등록 · 수정의 이미지 첨부 계약(#9)과 별도다. DB 변경, 발급 원장, 참조 추적, GC 스케줄러는 없다.

## API와 FE 흐름

| | 이미지 (#508) | 영상 (#796) |
|---|---|---|
| 발급 | `POST /api/v1/admin/displays/image-uploads` | `POST /api/v1/admin/displays/video-uploads` |
| 확정 | `POST /api/v1/admin/displays/image-uploads/{uploadKey}/confirm` | `POST /api/v1/admin/displays/video-uploads/{uploadKey}/confirm` |
| 형식 | JPEG(image/jpeg) · PNG(image/png) · WebP(image/webp) | MP4(video/mp4)만. webm 등은 후속 |
| 크기 | 1바이트~10MiB | 1바이트~100MiB |
| 업로드 키 | `{UUID}.{jpg\|png\|webp}` | `{UUID}.mp4` |
| 시그니처 | storage-core `ImageFormat` 앞 12바이트 | ISO BMFF `ftyp` box, 앞 128바이트 (`Mp4Signature`) |
| 쓰는 곳 | 전시 MEDIA IMAGE의 pcUrl/mobileUrl, 전시 VIDEO의 pcPosterUrl/mobilePosterUrl, 룩 image.url | 전시 MEDIA VIDEO의 pcUrl/mobileUrl |
| 감사 | `MEDIA_IMAGE_UPLOAD_ISSUE` · `MEDIA_IMAGE_UPLOAD_CONFIRM` | `MEDIA_VIDEO_UPLOAD_ISSUE` · `MEDIA_VIDEO_UPLOAD_CONFIRM` |

1. `CATALOG_MANAGE` 권한이 있는 운영자(ADMIN · OWNER) JWT로 발급 API에 `{contentType, contentLength}`를 보낸다.
2. 201 `Response` 봉투의 `{uploadKey, uploadUrl, method, headers, expiresAt}`를 읽고, 반환한 메서드와 헤더로 파일을 스토리지에 직접 PUT한다.
3. 같은 운영자 JWT로 확정 API를 호출한다. 본문은 없다.
4. 200 `Response` 봉투의 `{publicUrl, contentType, contentLength}`에서 publicUrl을 위 표의 쓰는 곳에 넣는다.

발급 · 확정 성공 응답은 `Cache-Control: no-store`다. 서명 URL, 헤더, 업로드 키를 로그에 남기지 않는다.
치수 width/height는 기존 선택 필드다. 서버가 파일의 치수를 해석하지 않으므로 모르면 생략한다.
MIME은 앞뒤 공백 제거 · 소문자 정규화 후 서명한다. 파라미터(`;codecs=…`)가 붙은 MIME은 받지 않는다.
SVG, GIF, QuickTime(mov), webm, 기타 파일은 지원 대상이 아니다. 확정 전에 전시/룩을 저장하지 않는다. FE 계약 검토 담당은 seonghoho다.

### 영상 VIDEO 저장 흐름과 대표 사진

전시 VIDEO는 영상 주소와 PC 대표 사진(pcPosterUrl, 필수)이 있어야 저장된다. **대표 사진(포스터)은 이미지 업로드 API를 그대로 쓴다** — 포스터 전용 API는 없다.

1. 영상 파일을 영상 API로 발급 → PUT → 확정해 publicUrl(`.mp4`)을 받는다.
2. PC · 모바일 대표 사진을 이미지 API로 발급 → PUT → 확정해 publicUrl을 받는다.
3. 전시 섹션 저장 요청의 MEDIA에 `mediaType=VIDEO`, `pcUrl`(모바일이 따로 있으면 `mobileUrl`)에 영상 URL,
   `pcPosterUrl`(모바일이 따로 있으면 `mobilePosterUrl`)에 사진 URL을 넣는다.

두 API의 공개 URL은 모두 `store.media.public-base-url` 아래라 전시 저장의 MediaUrlPolicy와 500자 제한을 통과한다.
전시 · 룩 저장은 주소의 확장자와 mediaType을 대조하지 않는다 — 영상 URL을 IMAGE · 포스터 칸이나 룩 image.url에 넣지 않게 FE가 칸별로 맞는 API의 결과만 넣는다.

## 소유권과 검증

클라이언트에는 lowercase canonical UUID와 종류별 확장자로 된 basename만 돌려준다.
source는 서버가 JWT actorId로 `uploads/display-media/{actorId}/{UUID}.{ext}`를 재구성한다.
다른 관리자의 키를 써도 자신의 namespace만 조회하므로 없는 객체와 같은 404다.
임의 사용자 ID, 전체 스토리지 경로, 외부 URL을 입력받지 않는다.

확정은 HEAD 존재 여부, 실제 크기(종류별 상한), 키 확장자와 HEAD MIME 일치, 비어 있지 않은 strong quoted ETag를 검증한다.
magic bytes는 조건부 range로 읽고 판별 결과를 키 형식 · HEAD MIME과 비교한다. 발급 때의 contentLength와 확정 때의 HEAD 크기 모두 상한을 본다
(서명에 길이가 들어가도 강제 여부가 스토리지마다 달라서다).
range와 copy 모두 HEAD의 opaque ETag를 따옴표까지 그대로 조건에 전달한다.
두 호출 중 원본이 바뀌면 409이며 다시 확정해야 한다. 검증하지 않은 바이트를 공개하지 않는다.

### MP4 시그니처 (`Mp4Signature`)

앞 128바이트를 읽어 ISO BMFF `ftyp` box를 본다. 확장자 · HEAD MIME만 믿지 않는다.

- 오프셋 0~3 box size(big-endian), 4~7 `ftyp`, 8~11 major brand, 12~15 minor version, 16~ compatible brand 4바이트씩
- box size는 16 이상 128 이하이고 4의 배수이며 box 전체가 읽은 범위 안에 있어야 한다. size 0(파일 끝까지) · 1(64비트 largesize)은 거절한다.
  일반 인코더(ffmpeg · HandBrake · Premiere)의 ftyp은 32바이트 안팎이고 128바이트면 compatible brand 28개까지 담는다.
- major brand는 4바이트 출력 가능 ASCII여야 한다. 출력 불가 compatible brand(0 패딩 등)는 거절하지 않고 판정에서 뺀다.
- major brand가 MP4 계열이면 받는다. 아니면 major가 MP4가 아닌 같은 계열 형식이 아니고 compatible brand 중 하나가 MP4 계열일 때 받는다
  (예: Sony XAVC S의 major `XAVC` + compatible `mp42`).

| 구분 | brand | 근거 |
|---|---|---|
| MP4 계열 | `isom` · `iso2`~`iso9` | ISO/IEC 14496-12 (ISO BMFF) 기본 brand와 판(edition)별 brand |
| | `mp41` · `mp42` | ISO/IEC 14496-14 (MP4 file format) v1 · v2 |
| | `avc1` | ISO/IEC 14496-15 (AVC file format) |
| | `dash` | ISO/IEC 23009-1 (MPEG-DASH) |
| | `M4V ` · `M4VH` · `M4VP` | Apple iTunes 영상 (MP4RA 등록) |
| | `MSNV` | Sony PSP MP4 (MP4RA 등록) |
| major이면 거절 | `qt  ` | QuickTime (video/quicktime) — 아이폰 기본 녹화(mov)는 MP4로 내보내야 한다 |
| | `M4A ` · `M4B ` · `M4P ` | iTunes 음성 (audio/mp4) |
| | `mif1` · `msf1` · `heic` · `heix` · `hevc` · `hevx` · `heim` · `heis` · `hevm` · `hevs` · `avif` · `avis` | HEIF · AVIF 정지 이미지 · 이미지 시퀀스 (ISO/IEC 23008-12 등) |
| | `crx ` · `jp2 ` · `jpx ` · `jpm ` · `mjp2` | Canon RAW, JPEG 2000 계열 (ISO/IEC 15444) |
| | `3g` 로 시작 | 3GPP · 3GPP2 (video/3gpp) |

이 판별은 브라우저가 재생할 만한 MP4 컨테이너인지 거르는 것이지 전체 디코딩 · 코덱(H.264/H.265/AV1) 검증이 아니다.
맨 앞이 ftyp이어야 하므로 HTML · SVG로 시작하는 폴리글롯은 통과하지 못한다. 그 뒤의 방어는 이미지와 같다 — 복사본 Content-Type 고정, 공개 도메인의 nosniff · origin 분리.
moov box가 파일 끝에 있으면(faststart 아님) 재생 시작이 늦을 수 있다. 서버는 이를 검사하지 않으므로 운영자는 웹용(faststart)으로 내보낸다.

### 공개 키

공개 키는 `products/media/{actorId}/{UUID}/{SHA256(opaque ETag UTF-8)}.{ext}`다.
`media`는 숫자 상품 ID와 구분되는 예약 segment다. 이 해시는 ETag의 namespace 구분값이며 콘텐츠 checksum이 아니다.
같은 ETag로 순차 · 동시 확정하면 같은 URL과 같은 representation을 만들고, 다른 ETag는 새 URL을 만든다.
COPY는 검증된 정규 MIME과 `public, max-age=31536000, immutable`로 메타데이터를 교체한다.
복사 전에 URL 500자 제한과 기존 MediaUrlPolicy를 확인한다. storage.public-base-url과 store.media.public-base-url이 어긋나면 503이며 복사하지 않는다.

**영상도 이미지와 같은 prefix(`uploads/display-media/` · `products/media/`)를 쓴다 (#796 결정).** 확장자(`.mp4`)로만 구분한다.

- 안전하다 — UUID는 발급마다 서버가 새로 만들고, 각 API의 basename 검증이 자기 종류의 확장자만 받는다. 이미지 API로 `.mp4` 키를, 영상 API로 이미지 키를 확정할 수 없다(400).
  확정은 키 형식 · HEAD MIME · 시그니처가 모두 맞아야 하므로 같은 prefix에 다른 종류의 바이트를 올려도 공개되지 않는다.
- 운영 정책을 새로 만들지 않는다 — 원본은 기존 `uploads/` 1일 lifecycle, 공개본은 기존 `products/*` 익명 GET 정책을 그대로 탄다. 새 예약 segment도 늘지 않는다.
- 상품 초안 이미지(#359)의 관리 키 패턴은 `.{jpg|png|webp}`만 받으므로 같은 prefix의 `.mp4`는 상품 이미지로 고정되지 않는다(`031`).
- 영상만 따로 저장량을 보려면 prefix가 아니라 접미 `.mp4`로 집계한다.

| 상태 | 이미지 | 영상 |
|---|---|---|
| 잘못된 basename | HTTP 경로 검증은 400 MEDIA_UPLOAD_106, 내부 호출의 방어 검증은 MEDIA_UPLOAD_002 | 400 MEDIA_UPLOAD_110, 방어 검증은 MEDIA_UPLOAD_002 |
| 형식 위반 (MIME · 확장자 · 시그니처) | 400 MEDIA_UPLOAD_003 | 400 MEDIA_UPLOAD_009 |
| 크기 위반 | 400 MEDIA_UPLOAD_004 | 400 MEDIA_UPLOAD_010 |
| 객체 없음 · 다른 관리자 namespace | 404 MEDIA_UPLOAD_001 | 404 MEDIA_UPLOAD_008 |
| range/copy ETag 변경 | 409 MEDIA_UPLOAD_005 | 409 MEDIA_UPLOAD_005 |
| 스토리지 비활성 | 503 MEDIA_UPLOAD_006 | 503 MEDIA_UPLOAD_011 |
| strong ETag 없음 · URL 설정 불일치 · 기타 스토리지 실패 | 503 MEDIA_UPLOAD_007 | 503 MEDIA_UPLOAD_012 |
| 깨진 JSON | 400 PRODUCT_100 (서비스 공통 ValidationErrorSupport) | 같음 |
| 요청 MIME 필수 · 형식 | MEDIA_UPLOAD_101 · 102 | MEDIA_UPLOAD_107 · 108 |
| 요청 길이 필수 · 최소 · 최대 | MEDIA_UPLOAD_103 · 104 · 105 | MEDIA_UPLOAD_103 · 104 · 109 |

001~007 · 101~106은 이미지 API의 기존 계약 그대로다. 영상은 문구가 종류를 말하는 코드를 따로 두고, 종류와 무관한 002 · 005 · 103 · 104는 같이 쓴다.

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

### 영상의 저장 · 전송 비용 (#796)

파일당 상한이 이미지(10MiB)의 10배인 100MiB라 같은 운영 방식에서도 비용이 크게 늘어난다.

- **저장** — 확정 한 번에 원본(lifecycle 전까지)과 공개본이 함께 있어 최대 약 200MiB를 잠시 차지하고, lifecycle 뒤에도 공개본 100MiB가 남는다.
  영상을 바꿀 때마다 새 공개본이 생기고 **GC가 없어 지난 공개본은 지워지지 않는다.** 미확정 업로드도 lifecycle이 없으면 그대로 쌓인다.
- **전송** — 메인 HERO 영상은 방문마다 내려받는다. 100MiB 영상이면 방문 1,000회에 최대 약 100GiB의 egress다.
  immutable cache와 CDN이 반복 전송을 줄이지만 첫 전송 · 캐시 만료 · 다른 기기는 다시 내려받는다. 브라우저 재생은 Range 요청을 쓰므로 CDN/스토리지가 Range를 지원해야 한다.
- **운영 권장** — 상한은 안전 한도이지 권장 크기가 아니다. HERO용 짧은 루프 영상은 H.264 · 웹용(faststart)으로 압축해 수 MiB~수십 MiB로 올린다.
  저장 용량과 egress를 대시보드에서 관찰하고, 미참조 공개본 정리(GC)는 별도 이슈로 다룬다.
- **서버 측 복사** — 확정의 COPY는 스토리지 안에서 일어나지만 CopyObject 응답은 복사가 끝난 뒤 오므로,
  100MiB 객체는 `storage.read-timeout`(product-service 5초)과 `storage.api-call-timeout`(8초) 안에 끝나야 한다(대략 20MiB/s 이상).
  실제 dev 스토리지의 100MiB 확정 시간은 아직 재지 않았다 — 재어 보고 모자라면 copy 전용 타임아웃을 후속으로 둔다.
  느린 스토리지에서 시간이 넘으면 503(MEDIA_UPLOAD_012)이고, 같은 업로드 키로 다시 확정하면 같은 공개 키로 복사를 다시 시도한다.

## 감사와 계층

Controller → AdminUseCase → AdminFacade → Processor → StoragePort/Adapter, Info → Presenter → Response를 따른다.
이미지 · 영상은 Controller · UseCase · Facade · Presenter · 요청/응답 DTO를 따로 두고, `MediaUploadProcessor` · `MediaStoragePort` · `MediaStorageAdapter`를 같이 쓴다.
종류별 차이 중 형식 · 크기 상한 · 키 확장자 · 시그니처는 domain의 `MediaKind` · `MediaFormat` · `MediaUploadKey` · `Mp4Signature`가 정하고, 종류별 오류 코드 선택은 application의 `MediaUploadErrorCode`가 정한다.
확정 검증(크기 · MIME · 시그니처)은 HEAD → 조건부 range → 조건부 copy를 같은 ETag로 묶어야 하므로 어댑터가 한 번에 한다.
Facade는 NOT_SUPPORTED로 호출자의 DB 트랜잭션도 중단하여 외부 I/O 동안 트랜잭션을 잡지 않는다.
storage-core 타입은 out adapter 내부에서만 쓴다. 스토리지 예외 원인 · SDK 메시지는 응답/로그에 넣지 않는다.
감사는 기존 ProductAdminAuditProcessor만 사용한다.
`MEDIA_IMAGE_UPLOAD_*` · `MEDIA_VIDEO_UPLOAD_*`는 actorMemberId, action, target, result 네 칸을 기록한다.
target은 `media-upload:<UUID>`이고 식별자가 없는 실패는 `-`다. URL, ETag, 전체 키, MIME, 바이트, 헤더는 감사에 넣지 않는다.
요청 검증 실패 · 401 · 403은 UseCase에 도달하지 않으므로 업무 감사가 없다.

## 검증 범위

MediaStorageAdapterTest는 이미지 · 영상의 형식 · 크기 · ETag · 조건부 range/copy · 설정 불일치 · 재시도 · 동시 확정과 delete 무호출, 종류별 오류 코드를 검증한다.
Mp4SignatureTest는 허용 · 거절 brand, box size 경계(16 · 128 · 4의 배수 · size 0/1), 잘린 box, 비 ASCII brand, PNG · HTML · WebM 시그니처 거절을 검증한다.
MediaUploadKeyTest와 MediaImageUploadAdminFacadeTest · MediaVideoUploadAdminFacadeTest는 basename, 종류 간 키 · MIME 분리, MIME 정규화, 실패 번역,
감사와 민감정보 출력 방지를 검증한다.
MediaImageUploadFlowIntegrationTest는 실제 signed JWT와 H2로 발급/확정 후 치수 없이 전시 · 룩에 저장하고 고객 조회에 같은 URL이 나오는 흐름을 검증한다.
MediaVideoUploadFlowIntegrationTest는 영상 요청 검증 코드(107~110 · 103 · 104)와 100MiB 경계, 영상 확정 + 이미지 API로 올린 대표 사진으로
전시 VIDEO를 저장하고 고객 조회에 같은 영상 · 포스터 URL이 나오는 흐름, 다른 관리자 404 · HEAD 크기 초과 · 시그니처 위반을 검증한다.
이 테스트들은 ObjectStorageClient를 대체한다. 실제 스토리지의 CORS, CDN 헤더, lifecycle 실행 및 운영 삭제 시점은 검증하지 않는다.

MediaStorageRoundTripTest는 Docker가 있을 때 실제 MinIO와 서명 PUT → 확정 COPY → 익명 공개 GET을 이미지(PNG)와 영상(MP4) 각각 왕복하고,
기존 products/* 공개 정책으로 products/media/*는 200, uploads/display-media/*는 403인 것을 검증한다.
서명 조건/바이트/MIME/cache와 원본 보존을 검증하는 magic-byte fixture이며 전체 이미지 · 영상 디코딩 · 치수 추출 검증은 아니다.
Docker 없는 로컬은 건너뛰고 CI에서는 Docker가 필수다. 공개 factory의 HTTP loopback 제한을 따르므로
Testcontainers가 비루프백 원격 Docker 호스트를 반환하는 환경에서는 별도의 HTTPS/네트워크 구성이 필요하다.
