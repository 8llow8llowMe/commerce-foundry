-- #17 B2c 운영 ALTER (배포 전 1회) — 반품 승인 때 운영자가 고른 귀책 칸(return_fault)과 승인 칸 동시 존재 check.
-- 원본: ClaimEntity + ddl/order-schema.sql. 직전 운영 형태는 #17 B1(claim-return-approval.sql) 적용 뒤다(B2a · B2b 는 DDL 없음).
-- dev 포함 모든 환경에서 새 앱을 기동하기 전에 적용한다 — dev 의 ddl-auto=update 는 새 열은 만들지만 ck 제약은 만들지 않는다.
-- 기존 행은 그대로다 — 승인 API(B2c-2) 전이라 승인 칸(approved_at)이 있는 반품이 없고, 새 칸은 모두 null 이라 새 check 를 모두 통과한다.
-- 이전 앱(B2b)은 새 칸을 모르지만 승인 행을 쓰지 않으므로(승인 칸 null + 귀책 null) 새 check 를 통과한다.
-- 적용: psql -v ON_ERROR_STOP=1 -f claim-return-fault.sql. ALTER 는 한 트랜잭션이다 — 실패하면 아무것도 바뀌지 않는다. 성공 뒤 재실행하지 않는다.
SET client_encoding = 'UTF8';

-- preflight 1 (읽기 전용): 승인 칸이 있는 클레임(승인 이후 반품). 기대 결과: 0행.
-- 한 행이라도 나오면 승인 API 가 이미 쓰인 것이다 — 그 행의 귀책은 복원할 수 없으므로 적용을 멈추고 원인을 확인한다.
SELECT id FROM orders.claim WHERE approved_at IS NOT NULL;

-- preflight 2 (읽기 전용): 이번에 만들 열 · 제약이 이미 있는지 확인한다. 기대 결과: 0행.
-- dev 에서 ddl-auto=update 인 새 앱이 이 스크립트보다 먼저 기동하면 열(과 그 값 check)만 먼저 생겨 아래 ADD COLUMN 이 실패한다(한 트랜잭션이라 아무것도 바뀌지 않는다).
-- 열 행만 나오고 그 열이 모두 비어 있으면(SELECT count(*) FROM orders.claim WHERE return_fault IS NOT NULL 이 0)
-- ALTER TABLE orders.claim DROP COLUMN return_fault 로 그 열(값 check 포함)을 지운 뒤 적용한다. 값이 있거나 ck_claim_return_fault 행이 나오면 멈추고 원인을 확인한다.
SELECT 'column' AS kind, column_name::text AS name
FROM information_schema.columns
WHERE table_schema = 'orders' AND table_name = 'claim' AND column_name = 'return_fault'
UNION ALL
SELECT 'constraint', con.conname::text
FROM pg_constraint con
JOIN pg_class rel ON rel.oid = con.conrelid
JOIN pg_namespace ns ON ns.oid = rel.relnamespace
WHERE ns.nspname = 'orders' AND rel.relname = 'claim'
  AND con.conname IN ('ck_claim_return_fault', 'claim_return_fault_check')
ORDER BY 1, 2;

SET lock_timeout = '5s';
BEGIN;
-- 값 check 는 열과 같이 만든다 — 이름은 PostgreSQL 기본(claim_return_fault_check)으로, 새로 만든 스키마(order-schema.sql)와 같다
ALTER TABLE orders.claim ADD COLUMN return_fault varchar(16) CHECK (return_fault IN ('CUSTOMER', 'SELLER'));
ALTER TABLE orders.claim ADD CONSTRAINT ck_claim_return_fault CHECK ((approved_at is null) = (return_fault is null));

COMMENT ON COLUMN orders.claim.return_fault IS '반품 승인 때 운영자가 고른 귀책 (CUSTOMER / SELLER) — 환불액 계산 근거. 승인 이후 반품에만';
COMMIT;
