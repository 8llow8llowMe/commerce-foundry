-- #308 C3a-1 회신 저장소 기반 (생산 리스너 없음). cleanup OFF라도 배포 전에 운영자가 psql -v ON_ERROR_STOP=1 -f <파일> 로 적용한다.
-- 새 테이블 · 인덱스를 만들고 payment 의 두 컬럼 comment 를 바꾼다 — 기존 행 검사는 없다. Hibernate 스냅숏(ddl/payment-schema.sql)의 processed_event 와 같은 모양이다.
set client_encoding = 'UTF8';
begin;

create table payment.processed_event (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        consumer_group varchar(200) not null,
        event_id uuid not null,
        event_type varchar(100) not null,
        processed_at timestamp(6) with time zone not null,
        primary key (id),
        constraint uk_processed_event_consumer_group_event_id unique (consumer_group, event_id)
    );

comment on table payment.processed_event is
        '처리한 이벤트 기록 (멱등 컨슈머 inbox)';

comment on column payment.processed_event.id is
        '처리 기록 아이디 (Snowflake)';

comment on column payment.processed_event.created_at is
        '생성 시각';

comment on column payment.processed_event.updated_at is
        '수정 시각';

comment on column payment.processed_event.consumer_group is
        '컨슈머 그룹 (예: commerce-foundry.prod.payment.order-payment-result)';

comment on column payment.processed_event.event_id is
        '이벤트 아이디 (봉투 eventId)';

comment on column payment.processed_event.event_type is
        '이벤트 종류 (예: order-paid)';

comment on column payment.processed_event.processed_at is
        '처리 시각 — 보존 기간 정리 기준';

create index idx_processed_event_processed_at
       on payment.processed_event (processed_at);

-- 기존 payment 열 · 제약 · 인덱스 · 행은 보존하고 두 컬럼 설명만 바꾼다.
comment on column payment.payment.order_synced is
        'order 회신(order-paid)을 받았는가. DONE 계열만 true — false 는 회신 대기 또는 거절 회신(order_sync_failure_code) (LATE_APPROVED 는 회신 대상 아님)';

comment on column payment.payment.order_sync_failure_code is
        'order 거절 회신(order-payment-rejected) 사유 코드. 회신 전 · order-paid 수신이면 null';

commit;
