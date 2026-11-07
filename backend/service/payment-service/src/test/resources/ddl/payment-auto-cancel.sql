-- #579 자동 취소 실행 메타데이터. 단일 게이트 OFF와 기존 실행자 drain 후 한 번 적용한다.
-- 롤백·재배포에서도 의도와 커밋된 claimId·PG 키를 보존한다.
set client_encoding = 'UTF8';

begin;
set local lock_timeout = '2s';
set local statement_timeout = '30s';

    create table payment.payment_auto_cancel (
        payment_id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        amount bigint,
        attempts integer not null,
        claim_id bigint,
        currency varchar(3) check ((currency in ('KRW','USD'))),
        first_attempt_at timestamp(6) with time zone,
        lease_token varchar(36),
        lease_until timestamp(6) with time zone,
        manual_reason varchar(40),
        next_attempt_at timestamp(6) with time zone not null,
        pg_key varchar(64) not null,
        state varchar(20) not null check ((state in ('PENDING','SUCCEEDED','MANUAL_REVIEW'))),
        transaction_key varchar(64),
        version bigint,
        primary key (payment_id),
        constraint uk_payment_auto_cancel_claim_id unique (claim_id),
        constraint ck_payment_auto_cancel_amount check ((amount is null and currency is null) or (amount is not null and amount > 0 and currency is not null)),
        constraint ck_payment_auto_cancel_claim check ((claim_id is null and amount is null) or (claim_id is not null and claim_id > 0 and amount is not null)),
        constraint ck_payment_auto_cancel_attempts check (attempts >= 0),
        constraint ck_payment_auto_cancel_lease check ((lease_until is null and lease_token is null) or (lease_until is not null and lease_token is not null))
    );

    comment on column payment.payment_auto_cancel.payment_id is
        'Payment ID reference to payment.id (no database FK constraint)';

    comment on column payment.payment_auto_cancel.created_at is
        '생성 시각';

    comment on column payment.payment_auto_cancel.updated_at is
        '수정 시각';

    create index idx_payment_auto_cancel_state_next_attempt_at_payment_id
       on payment.payment_auto_cancel (state, next_attempt_at, payment_id);

    create index idx_payment_auto_cancel_state_lease_until_payment_id
       on payment.payment_auto_cancel (state, lease_until, payment_id);

commit;
