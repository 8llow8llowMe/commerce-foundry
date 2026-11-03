set client_encoding = 'UTF8';
begin;

create table payment.payment_webhook_inbox (
        id bigint not null,
        attempts integer not null,
        available_at timestamp(6) with time zone not null,
        claim_token varchar(36),
        fingerprint varchar(64) not null,
        payment_id bigint not null,
        reason varchar(30) not null check ((reason in ('NONE','SNAPSHOT_CHANGED','PG_RETRY','PG_CONTRACT','PAYMENT_MISSING','UNSUPPORTED_STATE','LATE_APPROVAL','EXHAUSTED'))),
        received_at timestamp(6) with time zone not null,
        state varchar(20) not null check ((state in ('PENDING','PROCESSING','RETRY','COMPLETED','MANUAL_REVIEW'))),
        updated_at timestamp(6) with time zone not null,
        version bigint not null,
        primary key (id),
        constraint uk_payment_webhook_inbox_fingerprint unique (fingerprint),
        constraint ck_payment_webhook_inbox_attempts check (attempts >= 0),
        constraint ck_payment_webhook_inbox_claim check ((state = 'PROCESSING' and claim_token is not null) or (state <> 'PROCESSING' and claim_token is null))
    );

comment on table payment.payment_webhook_inbox is
        '검증된 로컬 결제 바인딩의 웹훅 조회 작업. 원문 미보관';

comment on column payment.payment_webhook_inbox.id is
        '수신 작업 아이디 (Snowflake)';

comment on column payment.payment_webhook_inbox.available_at is
        '다음 시도 시각. 처리 중이면 lease 만료 시각';

comment on column payment.payment_webhook_inbox.fingerprint is
        '정규화된 이벤트 SHA-256. 로그 출력 금지';

comment on column payment.payment_webhook_inbox.payment_id is
        '로컬 결제 아이디 (FK: payment.id)';

create index idx_payment_webhook_inbox_state_available_at_id
       on payment.payment_webhook_inbox (state, available_at, id);

commit;
