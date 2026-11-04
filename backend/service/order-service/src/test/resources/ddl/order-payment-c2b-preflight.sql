-- #14 C2b 배포 전 읽기 전용 진단. 반환 행은 운영 확인 대상이며 자동 백필/복원하지 않는다.
SET client_encoding = 'UTF8';

-- 기존 C1에서 허용한 선점/claim/승인시각 누락 형태. DDL 적용 가능 여부와 자동 재개 가능 여부는 별개다.
SELECT id, order_id, payment_id, result, stock_reservation_id, restoration_claim_id, approved_at, restored_at
FROM orders.order_payment
WHERE (stock_reservation_id IS NOT NULL AND result = 'REJECTED_ORDER_CANCELED' AND restoration_claim_id IS NULL)
   OR (restoration_claim_id IS NOT NULL AND restored_at IS NULL AND approved_at IS NULL);

-- 새 CHECK로 허용할 수 없는 NOT_DONE 및 claim/선점 불일치.
SELECT id, order_id, payment_id, result
FROM orders.order_payment
WHERE (result = 'REJECTED_PAYMENT_NOT_DONE' AND stock_reservation_id IS NOT NULL
       AND (restoration_claim_id IS NULL OR approved_at IS NULL))
   OR (restoration_claim_id IS NOT NULL AND (stock_reservation_id IS NULL
       OR result NOT IN ('REJECTED_ORDER_CANCELED', 'REJECTED_PAYMENT_NOT_DONE')))
   OR (restored_at IS NOT NULL AND restoration_claim_id IS NULL);

-- 같은 선점에 여러 책임 또는 PAID와 복원 의도가 함께 존재하는 데이터.
SELECT order_id, stock_reservation_id, count(*) AS responsibility_count
FROM orders.order_payment
WHERE stock_reservation_id IS NOT NULL AND (result = 'PAID' OR restoration_claim_id IS NOT NULL)
GROUP BY order_id, stock_reservation_id
HAVING count(*) > 1;

-- 기결제 상태인데 PAID 기록이 없는 legacy. CANCELED에도 과거 결제 이력이 있을 수 있으므로 별도 확인한다.
SELECT o.id, o.status
FROM orders.orders o
WHERE o.status NOT IN ('PENDING_PAYMENT', 'EXPIRED')
  AND NOT EXISTS (SELECT 1 FROM orders.order_payment p WHERE p.order_id = o.id AND p.result = 'PAID');

-- pending 의도와 현재 주문 소유/금액/통화/상태 불일치. 식별자만 진단하고 개인정보 원문은 조회하지 않는다.
SELECT p.id, p.order_id, p.payment_id, p.result, o.status
FROM orders.order_payment p
LEFT JOIN orders.orders o ON o.id = p.order_id
WHERE p.restoration_claim_id IS NOT NULL AND p.restored_at IS NULL
  AND (o.id IS NULL OR o.member_id <> p.member_id OR o.total_amount <> p.total_amount OR o.total_currency <> p.total_currency
       OR o.status NOT IN ('PENDING_PAYMENT', 'EXPIRED', 'CANCELED')
       OR EXISTS (SELECT 1 FROM orders.order_payment paid WHERE paid.order_id = p.order_id AND paid.result = 'PAID'));
