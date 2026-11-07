-- Cursor CREATE/comments/checks copied from Hibernate 7.2.24 PostgreSQL 17 output.
-- Existing schema only: do not run against an already upgraded database.
set client_encoding = 'UTF8';
begin;
    create table payment.order_reconciliation_cursor (
        lane varchar(16) not null check ((lane in ('RECONCILE','REJECTED'))),
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        approved_before timestamp(6) with time zone not null,
        cycle_upper_id bigint not null,
        last_id bigint not null,
        version bigint not null,
        primary key (lane),
        constraint ck_order_reconciliation_cursor_lane check (lane in ('RECONCILE','REJECTED')),
        constraint ck_order_reconciliation_cursor_bounds check (last_id >= 0 and cycle_upper_id >= last_id)
    );

    comment on table payment.order_reconciliation_cursor is
        '주문 회신 복구 lane별 영속 순회 커서';

    comment on column payment.order_reconciliation_cursor.lane is
        '독립 처리 lane (RECONCILE 또는 REJECTED)';

    comment on column payment.order_reconciliation_cursor.created_at is
        '생성 시각';

    comment on column payment.order_reconciliation_cursor.updated_at is
        '수정 시각';

    comment on column payment.order_reconciliation_cursor.approved_before is
        'cycle 시작 시 strict 승인 시각 cutoff (REJECTED는 사용 안 함)';

    comment on column payment.order_reconciliation_cursor.cycle_upper_id is
        'cycle 시작 시 결제 아이디 상한 (0이면 새 cycle)';

    comment on column payment.order_reconciliation_cursor.last_id is
        '예약을 커밋한 마지막 결제 아이디';

    comment on column payment.order_reconciliation_cursor.version is
        '커서 예약 낙관적 락 버전';

insert into payment.order_reconciliation_cursor
    (lane,created_at,updated_at,approved_before,cycle_upper_id,last_id,version)
values ('RECONCILE',current_timestamp,current_timestamp,current_timestamp,0,0,0),
       ('REJECTED',current_timestamp,current_timestamp,current_timestamp,0,0,0);

commit;
