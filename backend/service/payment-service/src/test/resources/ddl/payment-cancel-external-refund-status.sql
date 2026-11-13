-- #693 외부 확인 환불 상태 EXTERNAL_PG_REFUNDED · OFF_PG_REFUNDED 를 payment.payment_cancel.status check 에 더한다.
-- 운영자가 새 애플리케이션 기동 전에 psql -v ON_ERROR_STOP=1 -f 로 적용한다(dev 포함). 애플리케이션 자동 마이그레이션이 아니다.
-- #691 VOIDED 스크립트(payment-cancel-voided-status.sql)를 적용했든 안 했든 그대로 적용한다 — 최종 check 는 기존 값을 모두 포함한 6값이다.
-- 제약은 Hibernate 가 이름 없이 만든 칼럼 check 라 PostgreSQL 기본 이름(payment_cancel_status_check)이다.
-- 아래 자기 점검이 status 를 다루는 check 가 정확히 1개이고 그 이름이 payment_cancel_status_check 인지 보고, 아니면 아무것도 바꾸지 않고 실패한다.
-- 이름이 다르면 이 파일의 이름을 그 이름으로 고친 뒤 적용한다(payment-cancellation-ddl.md "#693 외부 확인 환불 상태").
-- 같은 최종 제약으로 다시 실행할 수 있다. 적용 뒤에는 VOIDED 스크립트를 다시 돌리지 않는다 — 돌려도 그 스크립트의 자기 점검이 막는다.
SET client_encoding = 'UTF8';
BEGIN;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '30s';
DO $$
DECLARE
    found_count integer;
    found_name text;
BEGIN
    SELECT count(*), min(conname::text) INTO found_count, found_name
      FROM pg_constraint
     WHERE conrelid = 'payment.payment_cancel'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) ILIKE '%status%';
    IF found_count <> 1 OR found_name <> 'payment_cancel_status_check' THEN
        RAISE EXCEPTION 'payment.payment_cancel status check must be exactly one constraint named payment_cancel_status_check (found %: %)',
            found_count, coalesce(found_name, '-');
    END IF;
END
$$;
ALTER TABLE payment.payment_cancel DROP CONSTRAINT payment_cancel_status_check;
ALTER TABLE payment.payment_cancel ADD CONSTRAINT payment_cancel_status_check
    CHECK (status IN ('PENDING','SUCCEEDED','REJECTED','VOIDED','EXTERNAL_PG_REFUNDED','OFF_PG_REFUNDED'));
COMMIT;
