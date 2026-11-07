-- #579 자동 취소 전용 커서. 기존 #591 lane·커서·두 seed·가드는 변경하지 않는다.
-- 단일 게이트 OFF와 기존 실행자 drain 후 한 번 적용하며 롤백 시 진행값을 보존한다.
set client_encoding = 'UTF8';

begin;
set local lock_timeout = '2s';
set local statement_timeout = '30s';

    create table payment.auto_cancel_cursor (
        lane varchar(16) not null check ((lane in ('REJECTED','LATE_APPROVED','PENDING'))),
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        cycle_upper_id bigint not null,
        due_before timestamp(6) with time zone not null,
        last_id bigint not null,
        version bigint not null,
        primary key (lane),
        constraint ck_auto_cancel_cursor_lane check (lane in ('REJECTED','LATE_APPROVED','PENDING')),
        constraint ck_auto_cancel_cursor_bounds check (last_id >= 0 and cycle_upper_id >= last_id)
    );

    comment on table payment.auto_cancel_cursor is
        'Automatic cancellation discovery and execution cursors';

    comment on column payment.auto_cancel_cursor.created_at is
        '생성 시각';

    comment on column payment.auto_cancel_cursor.updated_at is
        '수정 시각';

    create index idx_payment_status_id
       on payment.payment (status, id);

insert into payment.auto_cancel_cursor (lane,created_at,updated_at,cycle_upper_id,due_before,last_id,version)
values ('REJECTED',current_timestamp,current_timestamp,0,current_timestamp,0,0),
       ('LATE_APPROVED',current_timestamp,current_timestamp,0,current_timestamp,0,0),
       ('PENDING',current_timestamp,current_timestamp,0,current_timestamp,0,0);

commit;
