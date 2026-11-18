-- #582 PR-1 운영 DDL. 결제 반영 재고 복원 작업 테이블 + 기존 복원 대기 결제 기록 backfill.
-- 원본: OrderStockRestorationEntity + ddl/order-schema.sql. 기존 테이블은 바꾸지 않는다. 다시 실행해도 된다(IF NOT EXISTS · ON CONFLICT).
-- A는 저장 기반만 추가한다. 후속 producer 배포 → 구버전 트랜잭션 drain → backfill 재실행 → 스윕 활성화 순서다.
SET client_encoding = 'UTF8';
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
CREATE TABLE IF NOT EXISTS orders.order_stock_restoration (
    id bigint not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    attempts integer not null,
    claim_id bigint not null,
    hold_code varchar(30),
    next_attempt_at timestamp(6) with time zone,
    order_id bigint not null,
    origin varchar(30) not null check ((origin in ('PAYMENT_RECORD'))),
    payment_id bigint not null,
    restored_at timestamp(6) with time zone,
    status varchar(20) not null check ((status in ('RESTORE_PENDING','RESTORED','HELD'))),
    stock_reservation_id bigint not null,
    primary key (id),
    constraint uk_order_stock_restoration_stock_reservation_id unique (stock_reservation_id),
    constraint uk_order_stock_restoration_claim_id unique (claim_id),
    constraint ck_order_stock_restoration_attempts_non_negative check (attempts >= 0),
    constraint ck_order_stock_restoration_state_consistent check ((status = 'RESTORE_PENDING' and next_attempt_at is not null and restored_at is null and hold_code is null) or (status = 'RESTORED' and next_attempt_at is null and restored_at is not null and hold_code is null) or (status = 'HELD' and next_attempt_at is null and restored_at is null and hold_code is not null))
);
COMMENT ON TABLE orders.order_stock_restoration IS '결제 반영 재고 복원 작업 (확정 선점 1건당 1행, 스윕이 같은 클레임으로 복원)';
COMMENT ON COLUMN orders.order_stock_restoration.id IS '복원 작업 아이디 (Snowflake)';
COMMENT ON COLUMN orders.order_stock_restoration.created_at IS '생성 시각';
COMMENT ON COLUMN orders.order_stock_restoration.updated_at IS '수정 시각';
COMMENT ON COLUMN orders.order_stock_restoration.attempts IS '스윕이 선점한 횟수';
COMMENT ON COLUMN orders.order_stock_restoration.claim_id IS '재고 복원 클레임 아이디 (product stock-restorations 멱등 키, 결제 기록 출처면 order_payment.restoration_claim_id)';
COMMENT ON COLUMN orders.order_stock_restoration.hold_code IS 'HELD 사유 (상품 오류 코드 또는 OWNERSHIP_MISMATCH). HELD 가 아니면 null';
COMMENT ON COLUMN orders.order_stock_restoration.next_attempt_at IS '스윕이 다음에 집을 시각. 대기 상태에서만 있다';
COMMENT ON COLUMN orders.order_stock_restoration.order_id IS '주문 아이디 (FK: orders.id)';
COMMENT ON COLUMN orders.order_stock_restoration.origin IS '복원 의도 출처 (PAYMENT_RECORD)';
COMMENT ON COLUMN orders.order_stock_restoration.payment_id IS '결제 아이디 (외부 FK: payment-service payment.id)';
COMMENT ON COLUMN orders.order_stock_restoration.restored_at IS '재고 복원 200 을 받은 시각. RESTORED 에서만 있다';
COMMENT ON COLUMN orders.order_stock_restoration.status IS '작업 상태 (RESTORE_PENDING / RESTORED / HELD)';
COMMENT ON COLUMN orders.order_stock_restoration.stock_reservation_id IS '되돌릴 확정 선점 아이디 (외부 FK: product-service stock_reservation.id)';
CREATE INDEX IF NOT EXISTS idx_order_stock_restoration_status_next_attempt_at_id ON orders.order_stock_restoration (status, next_attempt_at, id);
-- 검증과 INSERT 사이 원천·작업 변경도 차단한다. 온라인 배포 중 짧은 쓰기 중단이 필요하다.
LOCK TABLE orders.order_payment IN SHARE MODE;
LOCK TABLE orders.order_stock_restoration IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM orders.order_payment p
        WHERE p.result IN ('REJECTED_ORDER_CANCELED', 'REJECTED_PAYMENT_NOT_DONE')
          AND p.restoration_claim_id IS NOT NULL AND p.stock_reservation_id IS NOT NULL AND p.restored_at IS NULL
        GROUP BY p.restoration_claim_id HAVING count(*) > 1
    ) OR EXISTS (
        SELECT 1 FROM orders.order_payment p JOIN orders.order_stock_restoration r ON r.claim_id = p.restoration_claim_id
        WHERE p.result IN ('REJECTED_ORDER_CANCELED', 'REJECTED_PAYMENT_NOT_DONE')
          AND p.restoration_claim_id IS NOT NULL AND p.stock_reservation_id IS NOT NULL AND p.restored_at IS NULL
          AND (r.order_id <> p.order_id OR r.payment_id <> p.payment_id OR r.stock_reservation_id <> p.stock_reservation_id
               OR r.origin <> 'PAYMENT_RECORD')
    ) THEN
        RAISE EXCEPTION 'restoration backfill claim ownership mismatch';
    END IF;
END
$$;
-- backfill: 복원 대기 결제 기록(확정 뒤 취소 · 미승인 거절, 복원 시각 없음). 아이디는 결제 기록 아이디를 그대로 쓴다 — 같은 Snowflake 생성기가 과거에 낸 값이라
-- 앞으로 생성될 작업 아이디와 겹치지 않는다. 다음 시각은 지금 — 스윕을 켜면 바로 집는다.
-- 재실행은 같은 claim_id 만 건너뛴다. 같은 선점을 다른 claim 이 가리키는 이상 데이터는 숨기지 않고 실패시킨다 — 적용 전 아래 점검이 0건이어야 한다:
--   select stock_reservation_id, count(*) from orders.order_payment
--   where result in ('REJECTED_ORDER_CANCELED','REJECTED_PAYMENT_NOT_DONE') and restoration_claim_id is not null
--     and stock_reservation_id is not null and restored_at is null group by 1 having count(*) > 1;
INSERT INTO orders.order_stock_restoration (id, created_at, updated_at, attempts, claim_id, hold_code, next_attempt_at, order_id, origin, payment_id,
    restored_at, status, stock_reservation_id)
SELECT p.id, now(), now(), 0, p.restoration_claim_id, null, now(), p.order_id, 'PAYMENT_RECORD', p.payment_id, null, 'RESTORE_PENDING', p.stock_reservation_id
FROM orders.order_payment p
WHERE p.result IN ('REJECTED_ORDER_CANCELED', 'REJECTED_PAYMENT_NOT_DONE')
  AND p.restoration_claim_id IS NOT NULL AND p.stock_reservation_id IS NOT NULL AND p.restored_at IS NULL
ON CONFLICT (claim_id) DO NOTHING;
COMMIT;
