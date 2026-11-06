-- #16 PR-2a 운영 ALTER 1단계 (배포 전 적용) — 결제 후 고객 취소 클레임(CANCELLATION)과 환불 · 재고 복원 재시도 칸.
-- 원본: ClaimEntity · ClaimItemEntity + ddl/order-schema.sql. 2단계(배포 후 정리)는 claim-cancellation-cleanup.sql 이다.
-- dev 포함 모든 환경에서 새 앱을 기동하기 전에 적용한다 — dev 의 ddl-auto=update 는 새 열은 만들지만 enum check · ck 제약은 바꾸지 않아,
-- 먼저 기동하면 새 상태 · 유형 행이 옛 check 에 막히거나 제약이 빠진 채 남는다.
-- 기존 반품(RETURN/REQUESTED) 행은 그대로다(attempt_count=0, 나머지 새 칸 null). attempt_count 의 DEFAULT 0 은 이전 앱(이 열을 모르는 INSERT)이
-- 롤링 배포 중에도 쓸 수 있게 1단계에서는 남겨 둔다.
-- 적용: psql -v ON_ERROR_STOP=1 -f claim-cancellation.sql. ALTER 는 한 트랜잭션이다 — 실패하면 아무것도 바뀌지 않는다.
SET client_encoding = 'UTF8';

-- preflight (읽기 전용): Hibernate 가 열 제약으로 만든 enum check 의 PostgreSQL 기본 이름(<table>_<column>_check)을 확인한다.
-- 기대 결과: 아래 3행이 정확히 나와야 한다 — claim | claim_status_check, claim | claim_type_check, claim_item | claim_item_reason_check.
-- 하나라도 빠지거나 이름이 다르면 적용을 멈추고 실제 이름(\d orders.claim)으로 스크립트를 고친다(IF EXISTS 로 건너뛰지 않는다).
SELECT rel.relname AS table_name, con.conname AS constraint_name
FROM pg_constraint con
JOIN pg_class rel ON rel.oid = con.conrelid
JOIN pg_namespace ns ON ns.oid = rel.relnamespace
WHERE ns.nspname = 'orders' AND con.conname IN ('claim_status_check', 'claim_type_check', 'claim_item_reason_check')
ORDER BY 1, 2;

SET lock_timeout = '5s';
BEGIN;
ALTER TABLE orders.claim ADD COLUMN attempt_count integer NOT NULL DEFAULT 0;
ALTER TABLE orders.claim ADD COLUMN failure_code varchar(40);
ALTER TABLE orders.claim ADD COLUMN next_attempt_at timestamp(6) with time zone;
ALTER TABLE orders.claim ADD COLUMN payment_id bigint;

ALTER TABLE orders.claim DROP CONSTRAINT claim_status_check;
ALTER TABLE orders.claim ADD CONSTRAINT claim_status_check CHECK (status IN ('REQUESTED', 'REFUNDED', 'COMPLETED', 'REFUND_FAILED', 'RESTORATION_FAILED'));
ALTER TABLE orders.claim DROP CONSTRAINT claim_type_check;
ALTER TABLE orders.claim ADD CONSTRAINT claim_type_check CHECK (type IN ('RETURN', 'CANCELLATION'));
ALTER TABLE orders.claim ADD CONSTRAINT ck_claim_attempt_count_non_negative CHECK (attempt_count >= 0);
ALTER TABLE orders.claim ADD CONSTRAINT ck_claim_cancellation_payment CHECK (
    type <> 'CANCELLATION' OR (payment_id IS NOT NULL AND payment_id > 0 AND expected_refund_amount IS NOT NULL AND expected_refund_amount > 0));
ALTER TABLE orders.claim ADD CONSTRAINT ck_claim_payment_cancellation_only CHECK (type = 'CANCELLATION' OR payment_id IS NULL);
ALTER TABLE orders.claim ADD CONSTRAINT ck_claim_cancellation_next_attempt CHECK (
    type <> 'CANCELLATION' OR ((status IN ('REQUESTED', 'REFUNDED')) = (next_attempt_at IS NOT NULL)));
ALTER TABLE orders.claim ADD CONSTRAINT ck_claim_cancellation_failure_code CHECK (
    type <> 'CANCELLATION' OR ((status IN ('REFUND_FAILED', 'RESTORATION_FAILED')) = (failure_code IS NOT NULL)));
CREATE INDEX idx_claim_type_next_attempt_at_id ON orders.claim (type, next_attempt_at, id);

ALTER TABLE orders.claim_item DROP CONSTRAINT claim_item_reason_check;
ALTER TABLE orders.claim_item ADD CONSTRAINT claim_item_reason_check CHECK (
    reason IN ('CHANGE_OF_MIND', 'DEFECTIVE', 'WRONG_ITEM', 'ORDER_MISTAKE', 'PAYMENT_ISSUE', 'OTHER'));

COMMENT ON COLUMN orders.claim.attempt_count IS '지금 단계(환불 · 재고 복원)의 미확정 재시도 횟수';
COMMENT ON COLUMN orders.claim.failure_code IS '종결 실패 사유 코드 (REFUND_FAILED · RESTORATION_FAILED). 아니면 null';
COMMENT ON COLUMN orders.claim.next_attempt_at IS '재시도 스케줄러가 다시 집을 시각. 종결이면 null';
COMMENT ON COLUMN orders.claim.payment_id IS '환불할 결제 아이디 (외부 FK: payment-service payment.id). 취소 클레임에만';
COMMENT ON COLUMN orders.claim.request_key IS '주문별 멱등 접수 키 (UUID). 전체 취소는 주문 아이디로 만든 결정적 키';
COMMENT ON COLUMN orders.claim.status IS '클레임 상태 (REQUESTED / REFUNDED / COMPLETED / REFUND_FAILED / RESTORATION_FAILED)';
COMMENT ON COLUMN orders.claim.type IS '클레임 유형 (RETURN / CANCELLATION)';
COMMENT ON TABLE orders.claim_item IS '클레임 항목 스냅숏';
COMMENT ON COLUMN orders.claim_item.quantity IS '클레임 수량';
COMMENT ON COLUMN orders.claim_item.reason IS '접수 사유 (반품 CHANGE_OF_MIND / DEFECTIVE / WRONG_ITEM, 취소 CHANGE_OF_MIND / ORDER_MISTAKE / PAYMENT_ISSUE / OTHER)';
COMMIT;
