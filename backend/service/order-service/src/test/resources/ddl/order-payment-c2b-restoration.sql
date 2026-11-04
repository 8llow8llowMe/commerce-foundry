-- #14 C2b 운영 ALTER. 게이트 OFF, consumer/원격 호출 drain 및 preflight 확인 후 사용한다.
-- 원본: OrderPaymentEntity + ddl/order-schema.sql. 기존 C1 유효 형태는 보존한다.
SET client_encoding = 'UTF8';
BEGIN;
ALTER TABLE orders.order_payment DROP CONSTRAINT ck_order_payment_reservation_scope;
ALTER TABLE orders.order_payment DROP CONSTRAINT ck_order_payment_restoration_consistent;
ALTER TABLE orders.order_payment ADD CONSTRAINT ck_order_payment_reservation_scope CHECK (
    stock_reservation_id IS NULL OR result IN ('PAID', 'REJECTED_ORDER_CANCELED')
    OR (result = 'REJECTED_PAYMENT_NOT_DONE' AND restoration_claim_id IS NOT NULL AND approved_at IS NOT NULL));
ALTER TABLE orders.order_payment ADD CONSTRAINT ck_order_payment_restoration_consistent CHECK (
    (restoration_claim_id IS NULL AND restored_at IS NULL)
    OR (restoration_claim_id IS NOT NULL AND result IN ('REJECTED_ORDER_CANCELED', 'REJECTED_PAYMENT_NOT_DONE') AND stock_reservation_id IS NOT NULL));
COMMENT ON COLUMN orders.order_payment.stock_reservation_id IS '확정 선점 아이디 (외부 FK: product-service stock_reservation.id). PAID · ORDER_CANCELED 또는 원승인 시각과 복원 클레임이 있는 NOT_DONE';
COMMENT ON COLUMN orders.order_payment.restoration_claim_id IS '확정 뒤 ORDER_CANCELED 또는 NOT_DONE 거절의 재고 복원 클레임 아이디 (product stock-restorations 멱등 키). 없으면 null';
COMMIT;
