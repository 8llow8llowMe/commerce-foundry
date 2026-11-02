# 작업 결과 파일 스토리지 (#212 PR-E1)

## 비공개 다운로드 서명

`ObjectStorageClient.presignGet(key, ttl)`은 비공개 객체를 다운로드할 GET 서명 URL을 로컬에서 만든다.
`ttl`은 1초 이상의 정수 초이며 `storage.presign-ttl` 이하로 요청한다. 기본 설정을 넘거나 잘못된 TTL은
`IllegalArgumentException`으로 거절한다. 객체 존재 여부는 조회하지 않는다. 스토리지가 꺼져 있으면 `DISABLED / PRESIGN_GET`이다.

응답은 `PresignedDownload(downloadUrl, expiresAt)`이다. URL 전체는 접근 권한이므로 로그·감사 본문·작업 JSON에 저장하지 않는다.
`toString()`은 URL과 객체 키를 표시하지 않는다. 다운로드는 서명된 `attachment`, `application/octet-stream`,
`private, no-store` 응답 정책으로 제공한다. URL의 응답 정책·객체 키·유효기간을 바꾸면 서명이 유효하지 않다.
업로드용 `presignPut` 계약과 공개 이미지 주소 `publicUrlOf`는 그대로 사용한다.

## 서비스 연결 시 지킬 계약 (후속 PR-E2)

이 PR은 공용 스토리지 기능만 제공한다. operations-service의 결과 파일 등록·다운로드 API·만료 정리는 아직 구현하지 않았다.

- 버킷의 `job-results/`는 비공개로 유지한다. 공개 이미지용 CDN 정책을 결과 파일 prefix에 적용하지 않는다.
- 키는 서비스가 `job-results/{jobId}/{attempt}/...`로 만들며 사용자 입력 키를 직접 서명하지 않는다.
- 서비스 out-port 어댑터만 클라이언트를 사용하고 SDK 타입을 도메인에 노출하지 않는다.
- ADMIN 권한·결과 상태·소유 정책·결과 보관 만료를 먼저 확인하고, 남은 보관 기간 이하인 짧은 TTL을 요청한다.
  남은 시간이 1초 미만이면 발급하지 않는다. URL은 만료 전 재사용할 수 있으므로 일회용 링크로 설명하지 않는다.
- 발급 행위는 관리자 ID·작업 ID·동작·시각으로 감사하고 URL·키·결과 본문을 로그에 남기지 않는다.
- 보관 만료는 별도 파일 메타데이터와 삭제 작업으로 관리한다. 서명 만료는 객체를 삭제하지 않는다.
- MinIO 왕복 테스트는 익명 읽기 거부, 서명 GET 성공, 응답 정책과 서명 변조 거부를 확인한다. Docker 없는 로컬은 건너뛰며 CI에서 실행한다.
