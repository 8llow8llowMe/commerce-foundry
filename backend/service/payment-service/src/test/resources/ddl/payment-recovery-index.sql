-- 트랜잭션 밖 / autocommit 연결에서 별도로 실행한다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_payment_status_approval_started_at_id
    ON payment.payment (status, approval_started_at, id);
