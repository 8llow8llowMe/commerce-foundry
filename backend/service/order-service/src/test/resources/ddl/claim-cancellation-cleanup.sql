-- #16 PR-2a 운영 ALTER 2단계 (배포 후 정리) — claim-cancellation.sql(1단계) 뒤, 모든 인스턴스가 새 앱(attempt_count 를 직접 쓰는 버전)으로
-- 바뀐 다음에 적용한다. 이전 앱이 하나라도 남아 있으면 그 앱의 반품 INSERT 가 NOT NULL 에 막힌다.
-- 원본: ClaimEntity + ddl/order-schema.sql(기본값 없는 attempt_count). 적용 뒤 claim 의 열 · 제약 · 인덱스는 스냅숏과 같다.
-- 적용: psql -v ON_ERROR_STOP=1 -f claim-cancellation-cleanup.sql.
SET client_encoding = 'UTF8';
SET lock_timeout = '5s';
BEGIN;
ALTER TABLE orders.claim ALTER COLUMN attempt_count DROP DEFAULT;
COMMIT;
