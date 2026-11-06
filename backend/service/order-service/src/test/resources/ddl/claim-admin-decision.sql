-- #17 PR-A 운영 ALTER (배포 전 적용, 1단계뿐) — 결제 후 취소 클레임의 운영자 수습(거절 · 수동 환불 확정 · 복원 생략 종결) 결정 칸과
-- 상태 APPROVED(반품 승인, PR-B 용) · REJECTED(거절). 원본: ClaimEntity + ddl/order-schema.sql.
-- 선행: claim-cancellation.sql · claim-cancellation-cleanup.sql (#16 PR-2a). 적용 뒤 claim 의 열 · 제약 · 인덱스 · 코멘트는 스냅숏과 같다.
-- dev 포함 모든 환경에서 새 앱을 기동하기 전에 적용한다 — dev 의 ddl-auto=update 는 새 열은 만들지만 enum check 를 바꾸지 않아,
-- 먼저 기동하면 거절(REJECTED)이 옛 check 에 막힌다.
-- 배포 후 정리 단계는 없다 — 새 칸은 모두 null 허용이고 이전 앱의 UPDATE(merge)는 이 칸을 모르니 건드리지 않는다. 롤링 배포 중 이전 앱은
-- REJECTED 행을 읽지 못하므로(모르는 enum 값) 배포가 끝나기 전에는 거절 API 를 부르지 않는다.
-- 적용: psql -v ON_ERROR_STOP=1 -f claim-admin-decision.sql. ALTER 는 한 트랜잭션이다 — 실패하면 아무것도 바뀌지 않는다.
SET client_encoding = 'UTF8';

-- preflight (읽기 전용): 상태 check 의 이름을 확인한다(claim-cancellation.sql 이 만든 이름).
-- 기대 결과: claim | claim_status_check 1행. 없거나 이름이 다르면 적용을 멈추고 실제 이름(\d orders.claim)으로 스크립트를 고친다(IF EXISTS 로 건너뛰지 않는다).
SELECT rel.relname AS table_name, con.conname AS constraint_name
FROM pg_constraint con
JOIN pg_class rel ON rel.oid = con.conrelid
JOIN pg_namespace ns ON ns.oid = rel.relnamespace
WHERE ns.nspname = 'orders' AND con.conname = 'claim_status_check'
ORDER BY 1, 2;

SET lock_timeout = '5s';
BEGIN;
ALTER TABLE orders.claim ADD COLUMN decided_at timestamp(6) with time zone;
ALTER TABLE orders.claim ADD COLUMN decided_by_member_id bigint;
ALTER TABLE orders.claim ADD COLUMN decision_reason varchar(200);

ALTER TABLE orders.claim DROP CONSTRAINT claim_status_check;
ALTER TABLE orders.claim ADD CONSTRAINT claim_status_check CHECK (
    status IN ('REQUESTED', 'REFUNDED', 'COMPLETED', 'REFUND_FAILED', 'RESTORATION_FAILED', 'APPROVED', 'REJECTED'));
ALTER TABLE orders.claim ADD CONSTRAINT ck_claim_decision_all_or_none CHECK (
    (decision_reason IS NULL AND decided_by_member_id IS NULL AND decided_at IS NULL)
    OR (decision_reason IS NOT NULL AND decided_by_member_id IS NOT NULL AND decided_by_member_id > 0 AND decided_at IS NOT NULL));
ALTER TABLE orders.claim ADD CONSTRAINT ck_claim_rejected_decision CHECK (status <> 'REJECTED' OR decided_at IS NOT NULL);

COMMENT ON COLUMN orders.claim.decided_at IS '운영자 수습 결정 시각. 없으면 null';
COMMENT ON COLUMN orders.claim.decided_by_member_id IS '운영자 수습 결정을 한 관리자 회원 아이디 (외부 FK: auth-service member.id). 없으면 null';
COMMENT ON COLUMN orders.claim.decision_reason IS '운영자 수습 결정 사유 (거절 · 수동 환불 확정 · 복원 생략 종결). 없으면 null';
COMMENT ON COLUMN orders.claim.status IS '클레임 상태 (REQUESTED / REFUNDED / COMPLETED / REFUND_FAILED / RESTORATION_FAILED / APPROVED / REJECTED)';
COMMIT;
