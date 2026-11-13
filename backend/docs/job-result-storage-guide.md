# 작업 결과 파일 스토리지 (#212 PR-E)

## 책임과 보안 계약

operations-service의 job 컨텍스트가 파일 생성 결과의 예약·확정·관리자 다운로드·보관 만료를 소유한다.
파일은 `JobOutcome(resultJson, JobResultContent)`로 반환하며 기존 `new JobOutcome(json)`·`empty()`는 그대로 쓴다.
핸들러는 객체 키를 지정하지 않는다. 서버가 `job-results/{jobId}/{attempt}/{UUID}`를 만든다.
`JobResultContent`는 바이트를 방어 복사하며 내용·파일명을 `toString()`에 쓰지 않는다.
파일명은 ASCII 영문·숫자·점·밑줄·하이픈의 일반 이름(최대 120자)이며 경로·제어문자·`..`를 거절한다.
파일명·MIME·JSON·오류·파일 내용에 개인정보 원문을 넣지 않는 것은 핸들러 계약이다.
`sensitive`는 개인정보 원문을 허용하는 플래그가 아니라 더 짧은 보관을 선택한다.

스토리지는 반드시 **비공개 전용 버킷·전용 credential**로 운영한다. 공개 상품 이미지 버킷·CDN과 공유하지 않는다.
익명 GET을 차단하고 서버 credential은 해당 버킷의 PUT·HEAD·GET 서명·DELETE에 필요한 범위만 준다.
공용 storage-core 기동 가드가 요구하는 `storage.public-base-url`은 유효한 HTTPS 설정값을 유지하되,
작업 결과에는 `publicUrlOf`를 호출하거나 공개 URL을 반환하지 않는다. 해당 URL의 존재가 버킷 공개를 의미하지 않는다.
공용 guard·서명 계약은 변경하지 않았다.

## 원장과 트랜잭션

`job_result_file`은 별도 테이블이며 JPA 연관관계 없이 job ID·attempt·worker ID를 저장한다.
상태는 `STAGED → AVAILABLE → RETIRED`, 미확정 파일은 `STAGED → RETIRED`다.
객체 키는 유일하고 크기는 양수 CHECK, 상태 쓰기는 `@Version`으로 경합한다.
`(next_delete_at,id)`는 정리 keyset, `(job_id,attempt)`는 현재 시도 첨부 조회 인덱스다.
같은 시도 번호에 STAGED/RETIRED가 여러 개 남을 수 있다. graceful release는 시도 번호를 되돌리므로
`(job_id,attempt)`를 유일하게 만들지 않는다. 매번 새 UUID 키를 쓰고 원장을 재사용하지 않는다.
작업의 버전·worker·attempt·유효 lease 확인으로 성공은 한 번만 커밋되고 AVAILABLE 파일도 한 개만 생긴다.

1. **TxA 예약**: 현재 워커·시도·유효 lease를 확인하고 STAGED를 커밋한다. 실패하면 PUT하지 않는다.
2. **트랜잭션 밖 업로드**: `put(key,bytes,MIME,"private, no-store")` 후 HEAD 크기·MIME·비어 있지 않은 ETag를 검증한다.
3. **TxB 성공**: `JobRunProcessor.succeed(...,prepared)`가 현재 fencing·유효 lease와 JSON 상한을 확인한다.
   Job SUCCEEDED·파일 AVAILABLE·기존 JobFinishedEvent를 같은 DB 트랜잭션으로 확정한다.
   JSON 과대는 기존 FAILED 규칙을 따르고 첨부를 확정하지 않는다.
4. PUT·HEAD 실패는 안전한 `RESULT_STORAGE_UNAVAILABLE` 재시도 코드로 바꾼다. 크기 과대는
   `RESULT_FILE_TOO_LARGE` 비재시도 오류다. 업로드 이후 성공 DB 기록 실패·lease 상실은 STAGED 원장을 남겨 정리한다.

기존 JSON 전용 `succeed(jobId,workerId,attempt,json)`의 동작은 유지한다.
`JobWorkerFacade.record()`의 boolean 결과나 예외 전파에 정리를 의존하지 않는다.
파일 보관 기간은 **성공 시점이 아니라 업로드 예약 시점**부터 센다. 업로드가 늦어져도 보관 상한을 늘리지 않는다.
STAGED 확정 기한은 보관 만료와 staging timeout 중 이른 시각이며, 기한이 지난 업로드는 성공 확정할 수 없다.

## 관리자 다운로드와 FE 계약

`POST /api/v1/admin/jobs/{jobId}/result-download`는 `AdminAuthority.REPORT_READ`(ADMIN · OWNER · MANAGER, `security-guide.md` §3-1)다. 본문은 없다.
감사 주체는 access token의 memberId이며 요청 body로 받지 않는다.
응답은 `Response<{url,expiresAt,fileName,contentType,sizeBytes}>`와 `Cache-Control: no-store`다.
성공한 현재 시도의 AVAILABLE만 대상으로 HEAD 존재·ETag·크기·MIME를 확인한다.
서명 TTL은 `min(download-ttl, storage.presign-ttl, 보관 잔여 시간)`을 정수 초로 내림한다.
1초 미만이면 발급하지 않으며 실제 서명 만료가 보관 만료를 넘는 응답도 거절한다.
서명 후 DB 상태·버전·보관 만료를 다시 확인하고 반환한다.

- 없는 작업은 `JOB_001 / 404`, 결과 없음·미확정은 `JOB_004 / 404`다.
- 만료되었거나 확정 후 RETIRED로 정리된 결과는 `JOB_023 / 410`이다. 미확정 orphan은 결과 없음이다.
- HEAD 정합성 실패·저장소 장애는 `JOB_501 / 503`, 읽는 사이 버전 경합은 `JOB_021 / 409`다.
- 잘못된 jobId는 기존 `JOB_104`·`JOB_198` 검증 규칙을 따른다.
- FE는 다운로드 버튼 클릭 때마다 URL을 발급받고 즉시 사용한다. URL을 상태 목록·브라우저 저장소·분석 도구·로그에 저장하지 않는다.
  URL은 만료 전 재사용 가능한 bearer 권한이며 일회용 링크가 아니다.
- 실제 GET은 storage-core의 `attachment`, `application/octet-stream`, `private, no-store` 서명 응답이다.
  응답의 fileName/contentType은 화면 안내용 메타데이터다. 서버 지정 파일명으로 Content-Disposition을 보장하지 않는다.
- 성공·실패 감사는 `AdminAuditLog`로 `action=JOB_RESULT_DOWNLOAD actorMemberId=<id> target=job:<id> result=SUCCESS|FAILURE` 네 칸만 남긴다.
  URL·객체 키·ETag·파일 내용은 감사와 예외 로그에 싣지 않는다.

## 만료·orphan·늦은 PUT 정리

스케줄러 → UseCase → 무트랜잭션 Facade → Processor 구조다. 기본 1분마다 최대 10배치 × 100건을 읽는다.
후보는 STAGED의 확정 기한, AVAILABLE의 보관 만료, RETIRED의 다음 삭제 시각이 지난 행이다.
TxC에서 먼저 RETIRED로 바꾸고 커밋하여 성공 확정·다운로드를 막는다. 성공 확정과 정리는 같은 파일 `@Version`에서 하나만 이긴다.
외부의 멱등 DELETE 뒤 TxD로 삭제 기록·다음 삭제 시각을 쓴다. 한 행의 실패는 다음 행을 막지 않는다.
실패는 30초부터 최대 1시간의 지수 백오프, 성공은 기본 1시간 뒤 다시 삭제한다.

**RETIRED 행은 첫 삭제 성공 뒤에도 영구 보존하며 주기적으로 재삭제한다.**
PUT이 지연되어 첫 DELETE 이후 도착하는 순서를 복구하기 위한 원장이다.
삭제 실패·삭제 성공 후 DB 기록 실패·중복 스윕·늦은 PUT 모두 다음 스윕으로 수렴한다.
삭제 확인은 서버 측 API 성공 기준이며, 영구 행·반복 DELETE 호출의 장기 비용이 있다.
이 비용은 현재 안전 정책의 의도된 한계이며 보존 원장을 임의 삭제하거나 첫 성공 뒤 반복 삭제를 끄면 안 된다.
스토리지 또는 cleanup이 꺼져 있으면 원장을 변경하지 않고 skip한다. 다시 켜면 밀린 due 행을 정리한다.

## 설정과 배포

`JobResultStorageConfig`가 `StorageConfigurer`·`StoragePropertiesConfig`를 import하고 결과 파일 properties를 등록한다.
`application.yml`의 새 storage 블록이 기존 표준 `STORAGE_*` 환경 키를 명시적으로 연결한다.
`storage.enabled`는 미설정 시 false로 기동하며 JSON 전용 작업은 계속 동작한다.
파일 업로드는 스토리지가 꺼져 있으면 안전한 재시도 오류를 남기며, 정리는 외부 호출 없이 skip한다.

| 프로퍼티 | 기본값·상한 |
|---|---|
| `operations.jobs.result-file.max-bytes` | 10485760 (10MiB), 1~10MiB |
| `retention` | 7d, 최대 7d |
| `sensitive-retention` | 1h, 최대 1h 및 일반 보관 이하 |
| `download-ttl` | 60s, 최대 60s |
| `staging-timeout` | 10m, 양수 |
| `delete-repeat` | 1h, 양수 |
| `batch-size` | 100, 1~100 |
| `max-batches` | 10, 1~100 |
| `cleanup-enabled` | true (storage 꺼짐이면 항상 skip) |
| `cleanup-initial-delay` · `cleanup-delay` | 각각 1m (스케줄러 placeholder) |

표의 짧은 이름도 모두 `operations.jobs.result-file.` 접두를 붙인다.
Boot 환경변수 직접 바인딩은 대시를 제거한다. 예: `OPERATIONS_JOBS_RESULTFILE_MAXBYTES=10485760`,
`OPERATIONS_JOBS_RESULTFILE_DOWNLOADTTL=60s`, `OPERATIONS_JOBS_RESULTFILE_CLEANUPENABLED=true`.
값을 넣는 것 외에 배포 프로세스가 해당 환경변수를 operations 컨테이너까지 전달해야 한다.
현재 저장소의 operations 배포 compose 연결은 제공하지 않으며 이 변경이 배포를 활성화하지 않는다.
시크릿은 Vault에 저장하고 서비스에 주입한다. 기존 `.env.example`의 키 값은 비워 둔다.
스토리지는 기존 `STORAGE_ENABLED`, `STORAGE_ENDPOINT`, `STORAGE_REGION`, `STORAGE_BUCKET`,
`STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY`, `STORAGE_PUBLIC_BASE_URL`을 사용한다.
연결·읽기·전체 API timeout 기본값은 2s·5s·8s, 서명 최대 TTL은 5m, path-style은 true다.
배포가 위 환경 키를 컨테이너로 전달해야 하며 `.env.example`에 적는 것만으로 주입되지는 않는다.

## 검증 경계

`JobResultContentTest`는 파일명·MIME·방어 복사·로그 비노출·JSON 생성자 호환을 확인한다.
`JobWorkerFacadeTest`는 예약 실패 때 PUT하지 않는 경로와 안전한 재시도 코드를 확인한다.
`JobResultFileFlowTest`는 실제 H2·Spring 트랜잭션과 fake 저장소로 업로드/성공, 확정 실패 롤백, lease·시도·JSON 상한,
PUT/HEAD orphan, 만료·삭제 실패, 삭제 후 DB 실패, 중복 삭제, 첫 삭제 후 늦은 PUT, TTL·HEAD 정합성,
graceful release의 같은 attempt 재사용을 재생한다.
`JobResultFilePersistenceTest`는 PostgreSQL DockerOrCi 계약으로 Hibernate 생성 DDL validate·due JPQL·버전 충돌을 검증한다.
`JobResultDownloadControllerTest`는 ADMIN 200·익명401·고객403·no-store·오류 봉투·주체 전달을 확인한다.
`JobResultAuditLogAdapterTest`는 감사 네 칸을 고정한다. 실제 S3 서명·MinIO 왕복은 storage-core의 기존 계약 테스트가 담당한다.
DDL 스냅숏은 `OperationsSchemaSnapshotTest`가 생성한 PostgreSQL SQL을 복사하여 갱신한다. 손으로 SQL을 만들지 않는다.

## 결과 파일을 쓰지 않는 작업

작은 구조화 결과만 내는 작업은 결과 파일 없이 `new JobOutcome(json)` 으로 `job.result_json`(엔진 상한 `operations.jobs.result.max-json-bytes`, 기본 64KiB)에 둔다.
이 문서의 예약 · 확정 · 서명 다운로드 · 만료 정리는 적용되지 않고, 보관은 작업 행과 같다. 끝난 작업 보존 정리(`JOB_RETENTION_CLEANUP`, 90일 예정, #212 PR-D)는
아직 없어 지금은 지워지지 않는다.

| 작업 종류 | 결과 | 자체 상한 | 읽는 쪽 |
|-----------|------|-----------|---------|
| `SIZE_CHART_GENERATION` (#217) | 검증한 사이즈표 후보 · 경고 | 32KiB | product 결과 조회 internal |
| `PRODUCT_DRAFT_GENERATE` (#359 PR-2b) | AI 상품 문구 제안 · 확인이 필요한 속성 · 경고(스키마는 `api-design-guide.md` §7-3) | 32KiB | product 결과 조회 internal(2b-2 · PR-3) |

결과 JSON 에 운영자 입력 사실 · 프롬프트 · provider 원문 · 이미지 바이트를 싣지 않는다. 보존 정리를 넣을 때 보관 기간은 product 초안 보관(7일)보다 길게 둬
초안이 살아 있는 동안 결과가 먼저 사라지지 않게 한다.
