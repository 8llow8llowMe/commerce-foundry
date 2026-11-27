-- #582 C. B2 원장 적용 뒤, 새 소비자를 배포하기 전에 실행한다.
SET client_encoding = 'UTF8';
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE orders.order_stock_restoration
    DROP CONSTRAINT order_stock_restoration_origin_check,
    ADD CONSTRAINT order_stock_restoration_origin_check
        CHECK (origin IN ('ABANDONED_CONFIRMATION', 'CONFIRMATION_ATTEMPT', 'PAYMENT_RECORD'));
COMMENT ON COLUMN orders.order_stock_restoration.origin IS '작업 출처 (결제 기록 · 확정 시도 · 버려진 확정)';
CREATE TABLE IF NOT EXISTS orders.order_stock_confirmation_participant (
    payment_id bigint not null,
    work_id bigint not null,
    primary key (work_id, payment_id)
);
COMMENT ON TABLE orders.order_stock_confirmation_participant IS '동일 재고 확정 후보의 추가 참여 결제';
COMMENT ON COLUMN orders.order_stock_confirmation_participant.work_id IS '확정 후보 아이디 (FK: order_stock_restoration.id)';
COMMENT ON COLUMN orders.order_stock_confirmation_participant.payment_id IS '참여 결제 아이디 (외부 FK: payment-service payment.id)';

COMMIT;
