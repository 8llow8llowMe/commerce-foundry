-- 서비스별 스키마 (service-inventory.md §4).
-- 한 인스턴스를 나눠 쓰지만 다른 서비스 스키마를 직접 조회하지 않는다 — 필요한 데이터는 내부 API 로 받는다.
-- `order` 는 예약어라 주문 스키마는 `orders` 다.
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS product;
CREATE SCHEMA IF NOT EXISTS orders;
CREATE SCHEMA IF NOT EXISTS payment;
CREATE SCHEMA IF NOT EXISTS delivery;
