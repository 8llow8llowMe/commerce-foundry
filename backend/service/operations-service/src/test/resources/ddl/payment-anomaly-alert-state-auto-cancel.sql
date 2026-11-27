-- #477 PR 2. PostgreSQL 17 / operations schema. 결제 이상 사건 상태를 다섯 사건에서 여섯 사건(AUTO_CANCEL_MANUAL_REVIEW)으로 넓힌다.
-- 운영자가 새 operations 를 배포하기 전에 psql -v ON_ERROR_STOP=1 -f 로 적용한다(dev 포함 — ddl-auto update 는 기존 CHECK 를 바꾸지 않는다).
-- 순서: PAYMENT_ANOMALY_ALERT_ENABLED=false → 이 스크립트 → 새 operations 배포 → 게이트 ON (deploy-guide.md "결제 이상 알림 여섯 사건 DDL").
-- 내용: family·severity CHECK 교체, 테이블 주석 갱신, UNKNOWN 행이 있을 때만 그 공통 워터마크를 복사한 NORMAL 행 1개 추가.
-- 0행 DB 는 행을 넣지 않는다(첫 수집이 여섯 행을 INSERT 한다). 부분 행(1~4행)·다섯 행인데 새 키가 있는 등 예상 밖 모양이면 아무것도 바꾸지 않고 실패한다
-- — 애플리케이션도 부분 행을 자동 보충하지 않는다. 같은 최종 상태로 다시 실행할 수 있다(여섯 행이면 INSERT 하지 않는다).
-- 제약 이름은 Hibernate 생성 스냅숏(operations-schema.sql)의 이름 있는 CHECK 이며 최종 문안도 스냅숏과 같다.
-- Source: OperationsSchemaSnapshotTest -> build/ddl/operations-schema.sql.
SET client_encoding = 'UTF8';
BEGIN;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '30s';
-- 동시 수집 쓰기와 섞이지 않게 먼저 테이블 잠금을 잡는다(게이트 OFF 가 전제이며 잠금 대기 2초를 넘으면 실패한다).
LOCK TABLE operations.payment_anomaly_alert_state IN ACCESS EXCLUSIVE MODE;
DO $$
DECLARE
    found_constraints integer;
    row_total integer;
    new_rows integer;
    unknown_rows integer;
BEGIN
    SELECT count(*) INTO found_constraints
      FROM pg_constraint
     WHERE conrelid = 'operations.payment_anomaly_alert_state'::regclass AND contype = 'c'
       AND conname IN ('ck_payment_anomaly_alert_state_family', 'ck_payment_anomaly_alert_state_severity');
    IF found_constraints <> 2 THEN
        RAISE EXCEPTION 'operations.payment_anomaly_alert_state must have ck_payment_anomaly_alert_state_family and ck_payment_anomaly_alert_state_severity (found %)',
            found_constraints;
    END IF;
    SELECT count(*), count(*) FILTER (WHERE family = 'AUTO_CANCEL_MANUAL_REVIEW'), count(*) FILTER (WHERE family = 'UNKNOWN')
      INTO row_total, new_rows, unknown_rows
      FROM operations.payment_anomaly_alert_state;
    IF NOT (row_total = 0 OR row_total = 5 AND new_rows = 0 AND unknown_rows = 1 OR row_total = 6 AND new_rows = 1 AND unknown_rows = 1) THEN
        RAISE EXCEPTION 'operations.payment_anomaly_alert_state must have 0 rows, the 5 legacy rows or all 6 rows (found % rows, new %); partial rows are not repaired',
            row_total, new_rows;
    END IF;
END
$$;
ALTER TABLE operations.payment_anomaly_alert_state DROP CONSTRAINT ck_payment_anomaly_alert_state_family;
ALTER TABLE operations.payment_anomaly_alert_state ADD CONSTRAINT ck_payment_anomaly_alert_state_family
    CHECK (family in ('UNKNOWN','IN_PROGRESS','RETURNED_READY','LATE_APPROVED','DONE_ORDER_UNSYNCED','AUTO_CANCEL_MANUAL_REVIEW'));
ALTER TABLE operations.payment_anomaly_alert_state DROP CONSTRAINT ck_payment_anomaly_alert_state_severity;
ALTER TABLE operations.payment_anomaly_alert_state ADD CONSTRAINT ck_payment_anomaly_alert_state_severity
    CHECK (status = 'NORMAL' or family = 'UNKNOWN' or (family in ('IN_PROGRESS','RETURNED_READY') and severity = 'WARNING')
        or (family in ('LATE_APPROVED','DONE_ORDER_UNSYNCED','AUTO_CANCEL_MANUAL_REVIEW') and severity = 'CRITICAL'));
COMMENT ON TABLE operations.payment_anomaly_alert_state IS '결제 이상 여섯 사건의 수락 워터마크';
-- 공통 원천/로컬 워터마크는 모든 행이 같아야 한다(애플리케이션이 불일치를 손상으로 거부). UNKNOWN 행의 값을 그대로 복사한다.
-- version 0 은 Hibernate 의 최초 INSERT 값과 같다. 사건 시각·긴급도는 NORMAL 이라 비운다.
INSERT INTO operations.payment_anomaly_alert_state
    (family, created_at, updated_at, first_detected_at, last_accepted_local_at, last_alert_at, last_observed_at, severity, status, version)
SELECT 'AUTO_CANCEL_MANUAL_REVIEW', now(), now(), NULL, legacy.last_accepted_local_at, NULL, legacy.last_observed_at, NULL, 'NORMAL', 0
  FROM operations.payment_anomaly_alert_state legacy
 WHERE legacy.family = 'UNKNOWN'
   AND NOT EXISTS (SELECT 1 FROM operations.payment_anomaly_alert_state added WHERE added.family = 'AUTO_CANCEL_MANUAL_REVIEW');
COMMIT;
