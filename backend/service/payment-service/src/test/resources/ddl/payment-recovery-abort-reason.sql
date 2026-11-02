-- 운영자가 recovery 활성화 전에 실행한다. 자동 마이그레이션이 아니다.
-- 임의 이름의 기존 Hibernate enum check만 찾아 원자 교체한다. 교차 필드 제약은 건드리지 않는다.
DO $migration$
DECLARE
    column_number smallint;
    checks integer;
    enum_check record;
    normalized text;
    actual_values text[];
    old_values constant text[] := ARRAY['ADMIN','AMOUNT_CHANGED','ORDER_EXPIRED','REPREPARED'];
    new_values constant text[] := ARRAY['ADMIN','AMOUNT_CHANGED','ORDER_EXPIRED','RECOVERY_NOT_APPROVED','REPREPARED'];
BEGIN
    SELECT attnum INTO STRICT column_number
      FROM pg_attribute
     WHERE attrelid = 'payment.payment'::regclass AND attname = 'abort_reason' AND NOT attisdropped;
    SELECT count(*) INTO checks FROM pg_constraint
     WHERE conrelid = 'payment.payment'::regclass AND contype = 'c'
       AND conkey = ARRAY[column_number]::smallint[];
    IF checks <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one single-column abort_reason enum check';
    END IF;
    SELECT oid, conname, pg_get_expr(conbin, conrelid) AS expression INTO STRICT enum_check
      FROM pg_constraint WHERE conrelid = 'payment.payment'::regclass AND contype = 'c'
       AND conkey = ARRAY[column_number]::smallint[];
    normalized := regexp_replace(enum_check.expression, '::(character varying|text)(\[\])?', '', 'g');
    normalized := regexp_replace(normalized, '[[:space:]()]', '', 'g');
    IF normalized !~ '^abort_reason=ANYARRAY\[(''[A-Z_]+''[,]?)+\]$' THEN
        RAISE EXCEPTION 'Unexpected abort_reason check shape';
    END IF;
    SELECT array_agg(value ORDER BY value) INTO actual_values
      FROM (SELECT (regexp_matches(enum_check.expression, '''([^'']*)''', 'g'))[1] AS value) values_found;
    IF actual_values <> old_values AND actual_values <> new_values THEN
        RAISE EXCEPTION 'Unexpected abort_reason enum values';
    END IF;
    IF enum_check.conname = 'ck_payment_abort_reason_values' AND actual_values = new_values THEN
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'payment.payment'::regclass
                AND conname = 'ck_payment_abort_reason_values' AND oid <> enum_check.oid) THEN
        RAISE EXCEPTION 'Target abort_reason constraint name is already in use';
    END IF;
    EXECUTE format('ALTER TABLE payment.payment DROP CONSTRAINT %I', enum_check.conname);
    ALTER TABLE payment.payment ADD CONSTRAINT ck_payment_abort_reason_values
        CHECK (abort_reason IN ('ADMIN','AMOUNT_CHANGED','ORDER_EXPIRED','RECOVERY_NOT_APPROVED','REPREPARED'));
END
$migration$;
