-- 운영자가 배포 전에 psql -v ON_ERROR_STOP=1 -f 로 적용한다. 애플리케이션 자동 마이그레이션이 아니다.
-- 동일한 최종 제약으로 재실행할 수 있다. 누락된 제약이나 부정 데이터는 실패시키며 원래 제약을 보존한다.
SET client_encoding = 'UTF8';
BEGIN;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE payment.payment DROP CONSTRAINT ck_payment_failed_fields;
ALTER TABLE payment.payment ADD CONSTRAINT ck_payment_failed_fields CHECK (
    (failure_message IS NULL OR failure_code IS NOT NULL)
    AND ((status = 'FAILED' AND failure_code IS NOT NULL)
        OR status IN ('LATE_APPROVED','CANCELED')
        OR (status NOT IN ('FAILED','LATE_APPROVED','CANCELED') AND failure_code IS NULL))
);
COMMIT;
