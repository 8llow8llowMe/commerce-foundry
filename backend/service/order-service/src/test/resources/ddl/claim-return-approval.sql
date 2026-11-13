-- #17 B1 운영 ALTER (배포 전 1회) — 반품 승인 · 거절 상태와 승인 칸(approved_at, approved_by_member_id, restock, deduction_amount).
-- 원본: ClaimEntity + ddl/order-schema.sql. 직전 운영 형태는 #16 PR-2a 1 · 2단계(claim-cancellation.sql · claim-cancellation-cleanup.sql) +
-- #17 A1(claim-admin-decision.sql) 적용 뒤다.
-- dev 포함 모든 환경에서 새 앱을 기동하기 전에 적용한다 — dev 의 ddl-auto=update 는 새 열은 만들지만 enum check · ck 제약은 바꾸지 않는다.
-- 기존 행은 그대로다 — 반품은 REQUESTED(결제 · 재시도 칸 null)뿐이고 새 칸은 모두 null 이라 새 check 를 모두 통과한다. B1 앱은 새 상태 행을 쓰지 않는다.
-- 결정 check(ck_claim_admin_decision)는 B2 의 반품 결정 · 거절 사유를 위해 여기서 미리 넓힌다(취소만 → 취소 또는 접수가 아닌 반품,
-- 거절 결정은 직전 실패 코드 없음 허용). 접수(REQUESTED) 반품의 결정은 계속 막는다.
-- B1 도메인은 이 결정을 만들지 않고 같은 규칙으로 읽기만 한다(만드는 전이는 B2).
-- 적용: psql -v ON_ERROR_STOP=1 -f claim-return-approval.sql. ALTER 는 한 트랜잭션이다 — 실패하면 아무것도 바뀌지 않는다. 성공 뒤 재실행하지 않는다.
SET client_encoding = 'UTF8';

-- preflight 1 (읽기 전용): 바꿀 기존 제약의 이름을 확인한다.
-- 기대 결과: 정확히 3행 — ck_claim_admin_decision, ck_claim_payment_cancellation_only, claim_status_check.
-- 하나라도 빠지거나 이름이 다르면 적용을 멈추고 실제 이름(\d orders.claim)으로 스크립트를 고친다(IF EXISTS 로 건너뛰지 않는다).
SELECT con.conname
FROM pg_constraint con
JOIN pg_class rel ON rel.oid = con.conrelid
JOIN pg_namespace ns ON ns.oid = rel.relnamespace
WHERE ns.nspname = 'orders' AND rel.relname = 'claim'
  AND con.conname IN ('claim_status_check', 'ck_claim_payment_cancellation_only', 'ck_claim_admin_decision')
ORDER BY 1;

-- preflight 2 (읽기 전용): 새 반품 check 에 걸릴 기존 반품 행. 기대 결과: 0행. 한 행이라도 나오면 적용을 멈추고 원인을 확인한다.
SELECT id FROM orders.claim
WHERE type = 'RETURN'
  AND (status <> 'REQUESTED' OR payment_id IS NOT NULL OR next_attempt_at IS NOT NULL OR failure_code IS NOT NULL OR decision_reason IS NOT NULL);

-- preflight 3 (읽기 전용): 이번에 만들 열 · 제약이 이미 있는지 확인한다. 기대 결과: 0행.
-- dev 에서 ddl-auto=update 인 새 앱이 이 스크립트보다 먼저 기동하면 열만 먼저 생겨 아래 ADD COLUMN 이 실패한다(한 트랜잭션이라 아무것도 바뀌지 않는다).
-- 열 행만 나오고 그 열이 모두 비어 있으면(SELECT count(*) FROM orders.claim WHERE approved_at IS NOT NULL OR approved_by_member_id IS NOT NULL
-- OR deduction_amount IS NOT NULL OR restock IS NOT NULL 이 0) ALTER TABLE orders.claim DROP COLUMN 으로 그 열을 지운 뒤 적용한다.
-- 값이 있거나 제약 행이 나오면 멈추고 원인을 확인한다.
SELECT 'column' AS kind, column_name::text AS name
FROM information_schema.columns
WHERE table_schema = 'orders' AND table_name = 'claim'
  AND column_name IN ('approved_at', 'approved_by_member_id', 'deduction_amount', 'restock')
UNION ALL
SELECT 'constraint', con.conname::text
FROM pg_constraint con
JOIN pg_class rel ON rel.oid = con.conrelid
JOIN pg_namespace ns ON ns.oid = rel.relnamespace
WHERE ns.nspname = 'orders' AND rel.relname = 'claim'
  AND con.conname IN ('ck_claim_return_approval', 'ck_claim_cancellation_not_approved', 'ck_claim_return_next_attempt', 'ck_claim_return_failure_code')
ORDER BY 1, 2;

SET lock_timeout = '5s';
BEGIN;
ALTER TABLE orders.claim ADD COLUMN approved_at timestamp(6) with time zone;
ALTER TABLE orders.claim ADD COLUMN approved_by_member_id bigint;
ALTER TABLE orders.claim ADD COLUMN deduction_amount bigint;
ALTER TABLE orders.claim ADD COLUMN restock boolean;

ALTER TABLE orders.claim DROP CONSTRAINT claim_status_check;
ALTER TABLE orders.claim ADD CONSTRAINT claim_status_check CHECK (
    status IN ('REQUESTED', 'APPROVED', 'REFUNDED', 'COMPLETED', 'REJECTED', 'REFUND_FAILED', 'RESTORATION_FAILED'));
ALTER TABLE orders.claim DROP CONSTRAINT ck_claim_payment_cancellation_only;
ALTER TABLE orders.claim ADD CONSTRAINT ck_claim_cancellation_not_approved CHECK (type <> 'CANCELLATION' or status <> 'APPROVED');
ALTER TABLE orders.claim ADD CONSTRAINT ck_claim_return_approval CHECK (
    (type = 'RETURN' and status not in ('REQUESTED', 'REJECTED')
        and payment_id is not null and payment_id > 0 and expected_refund_amount is not null and expected_refund_amount > 0
        and approved_at is not null and approved_by_member_id is not null and approved_by_member_id > 0 and restock is not null
        and deduction_amount is not null and deduction_amount >= 0)
    or ((type <> 'RETURN' or (status in ('REQUESTED', 'REJECTED') and payment_id is null))
        and approved_at is null and approved_by_member_id is null and restock is null and deduction_amount is null));
ALTER TABLE orders.claim ADD CONSTRAINT ck_claim_return_next_attempt CHECK (
    type <> 'RETURN' or ((status in ('APPROVED', 'REFUNDED')) = (next_attempt_at is not null)));
ALTER TABLE orders.claim ADD CONSTRAINT ck_claim_return_failure_code CHECK (
    type <> 'RETURN' or ((status in ('REFUND_FAILED', 'RESTORATION_FAILED')) = (failure_code is not null)));
ALTER TABLE orders.claim DROP CONSTRAINT ck_claim_admin_decision;
ALTER TABLE orders.claim ADD CONSTRAINT ck_claim_admin_decision CHECK (
    (decision_reason is null and decided_by_member_id is null and decided_at is null and decision_failure_code is null)
    or ((type = 'CANCELLATION' or status <> 'REQUESTED') and decision_reason is not null and length(trim(decision_reason)) > 0
        and decided_by_member_id is not null and decided_by_member_id > 0 and decided_at is not null
        and ((decision_failure_code is not null and length(trim(decision_failure_code)) > 0) or (decision_failure_code is null and status = 'REJECTED'))));

COMMENT ON COLUMN orders.claim.approved_at IS '반품 승인 시각 — 반품 환불의 결제 취소 키 기한 기준. 승인 이후 반품에만';
COMMENT ON COLUMN orders.claim.approved_by_member_id IS '반품 승인 관리자 아이디 (외부 FK: auth-service member.id). 승인 이후 반품에만';
COMMENT ON COLUMN orders.claim.deduction_amount IS '반품 정책 금액에서 뺀 최소 단위 금액 (반품 배송비 등, 통화는 expected_refund_currency). 승인 이후 반품에만';
COMMENT ON COLUMN orders.claim.restock IS '검수 뒤 재고로 되돌릴지. 승인 이후 반품에만';
COMMENT ON COLUMN orders.claim.payment_id IS '환불할 결제 아이디 (외부 FK: payment-service payment.id). 취소 클레임 · 승인 이후 반품에만';
COMMENT ON COLUMN orders.claim.status IS '클레임 상태 (REQUESTED / APPROVED / REFUNDED / COMPLETED / REJECTED / REFUND_FAILED / RESTORATION_FAILED)';
COMMIT;
