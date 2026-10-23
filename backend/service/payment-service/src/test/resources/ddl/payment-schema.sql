
    create schema payment;

    create table payment.payment (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        abort_reason varchar(30) check ((abort_reason in ('REPREPARED','AMOUNT_CHANGED','ORDER_EXPIRED','ADMIN'))),
        active_order_id bigint,
        approval_started_at timestamp(6) with time zone,
        approved_at timestamp(6) with time zone,
        easy_pay_provider varchar(50),
        failure_code varchar(100),
        failure_message varchar(510),
        gateway varchar(20) not null check ((gateway in ('TOSS'))),
        member_id bigint not null,
        method varchar(30) check ((method in ('CARD','EASY_PAY','TRANSFER','VIRTUAL_ACCOUNT','MOBILE_PHONE','GIFT_CERTIFICATE','OTHER'))),
        order_id bigint not null,
        order_name varchar(100) not null,
        order_sync_failure_code varchar(30),
        order_synced boolean not null,
        payment_key varchar(200),
        pg_order_id varchar(64) not null,
        pg_status varchar(30),
        status varchar(20) not null check ((status in ('READY','IN_PROGRESS','DONE','FAILED','UNKNOWN','ABORTED','LATE_APPROVED','PARTIAL_CANCELED','CANCELED','WAITING_FOR_DEPOSIT'))),
        total_amount bigint not null,
        total_currency varchar(3) not null check ((total_currency in ('KRW','USD'))),
        unknown_reason varchar(40) check ((unknown_reason in ('TIMEOUT','SERVER_ERROR','ALREADY_PROCESSED','CONTRACT','APPROVAL_MISMATCH','STALE_IN_PROGRESS','MANUAL_REVIEW'))),
        version bigint not null,
        primary key (id),
        constraint uk_payment_pg_order_id unique (pg_order_id),
        constraint uk_payment_payment_key unique (payment_key),
        constraint uk_payment_active_order_id unique (active_order_id),
        constraint ck_payment_total_amount_positive check (total_amount > 0),
        constraint ck_payment_pg_order_id_length check (length(pg_order_id) >= 6),
        constraint ck_payment_active_order_id_status check ((status in ('FAILED','ABORTED','LATE_APPROVED') and active_order_id is null) or (status not in ('FAILED','ABORTED','LATE_APPROVED') and active_order_id is not null and active_order_id = order_id)),
        constraint ck_payment_approval_started_at_key check (approval_started_at is null or payment_key is not null),
        constraint ck_payment_in_progress_fields check (status not in ('IN_PROGRESS','UNKNOWN') or (payment_key is not null and approval_started_at is not null)),
        constraint ck_payment_approved_fields check ((status in ('DONE','LATE_APPROVED','PARTIAL_CANCELED','CANCELED') and payment_key is not null and method is not null and approved_at is not null) or (status not in ('DONE','LATE_APPROVED','PARTIAL_CANCELED','CANCELED') and method is null and approved_at is null and easy_pay_provider is null and pg_status is null)),
        constraint ck_payment_failed_fields check ((failure_message is null or failure_code is not null) and ((status = 'FAILED' and failure_code is not null) or status = 'LATE_APPROVED' or (status not in ('FAILED','LATE_APPROVED') and failure_code is null))),
        constraint ck_payment_unknown_fields check ((status = 'UNKNOWN' and unknown_reason is not null) or (status in ('READY','IN_PROGRESS','ABORTED','WAITING_FOR_DEPOSIT') and unknown_reason is null) or status in ('DONE','FAILED','LATE_APPROVED','PARTIAL_CANCELED','CANCELED')),
        constraint ck_payment_abort_reason_status check ((status = 'ABORTED' and abort_reason is not null) or status = 'LATE_APPROVED' or (status not in ('ABORTED','LATE_APPROVED') and abort_reason is null)),
        constraint ck_payment_late_approved_origin check (status <> 'LATE_APPROVED' or (failure_code is not null and abort_reason is null) or (failure_code is null and abort_reason is not null)),
        constraint ck_payment_order_synced_status check (order_synced = false or status in ('DONE','PARTIAL_CANCELED','CANCELED')),
        constraint ck_payment_order_sync_failure_code_status check (order_sync_failure_code is null or (order_synced = false and status in ('DONE','PARTIAL_CANCELED','CANCELED')))
    );

    comment on table payment.payment is
        '결제 시도 (시도 하나에 한 행, 실패 뒤 재시도는 새 행)';

    comment on column payment.payment.id is
        '결제 시도 아이디 (Snowflake)';

    comment on column payment.payment.created_at is
        '생성 시각';

    comment on column payment.payment.updated_at is
        '수정 시각';

    comment on column payment.payment.abort_reason is
        '결제 시도 중단 사유 (ABORTED 와 ABORTED 에서 온 LATE_APPROVED 만)';

    comment on column payment.payment.active_order_id is
        '활성 결제 시도의 주문 아이디 — FAILED · ABORTED · LATE_APPROVED 면 null, 그 밖은 order_id (주문당 활성 결제 시도 1건)';

    comment on column payment.payment.approval_started_at is
        '마지막 승인 요청 시작 시각 — IN_PROGRESS 로 남은 행을 복구하는 단서. 되돌린 READY 에도 흔적으로 남는다';

    comment on column payment.payment.approved_at is
        'PG 승인 시각';

    comment on column payment.payment.easy_pay_provider is
        '간편결제 제공자 (예: 카카오페이). 간편결제가 아니면 null';

    comment on column payment.payment.failure_code is
        'PG 거절 코드 (FAILED 와 FAILED 에서 온 LATE_APPROVED 만)';

    comment on column payment.payment.failure_message is
        'PG 거절 메시지 (거절 코드가 있을 때만, 510자에서 자른다)';

    comment on column payment.payment.gateway is
        'PG (TOSS)';

    comment on column payment.payment.member_id is
        '결제한 회원 아이디 (외부 FK: auth-service member.id)';

    comment on column payment.payment.method is
        '승인된 결제수단. 승인 전이면 null';

    comment on column payment.payment.order_id is
        '주문 아이디 (외부 FK: order-service orders.id)';

    comment on column payment.payment.order_name is
        'PG 결제창 주문명 (1~100자)';

    comment on column payment.payment.order_sync_failure_code is
        '주문 반영 종결 실패 시 order-service 오류 코드. 반영 전 · 반영 성공이면 null';

    comment on column payment.payment.order_synced is
        '결제 완료를 order-service 에 반영했는가. DONE 계열만 true — false 인 DONE 은 재전송 대상 (LATE_APPROVED 는 반영하지 않는다)';

    comment on column payment.payment.payment_key is
        'PG 결제키 (승인 시작부터). 승인 시작 전이면 null';

    comment on column payment.payment.pg_order_id is
        'PG 에 보낸 주문번호 (6~64자, 영문 · 숫자 · - · _). 결제 시도 아이디 문자열이다 — 승인 · 취소된 번호는 PG 가 재사용을 거절한다';

    comment on column payment.payment.pg_status is
        '승인 때 PG 가 준 원본 상태 문자열 (대사 · 장애 분석용)';

    comment on column payment.payment.status is
        '결제 상태 (READY / IN_PROGRESS / DONE / FAILED / UNKNOWN / ABORTED / LATE_APPROVED / PARTIAL_CANCELED / CANCELED / WAITING_FOR_DEPOSIT)';

    comment on column payment.payment.total_amount is
        '결제 금액 (0 초과, 최소 화폐 단위). order-service 가 계산한 주문 금액';

    comment on column payment.payment.total_currency is
        '결제 금액 통화';

    comment on column payment.payment.unknown_reason is
        '결과 미확정(UNKNOWN)으로 둔 이유. 재조회로 확정한 뒤(DONE · FAILED 계열)에도 이력으로 남는다';

    comment on column payment.payment.version is
        '낙관적 락 버전';

    create index idx_payment_order_id 
       on payment.payment (order_id);

    create index idx_payment_member_id_id 
       on payment.payment (member_id, id);

    create index idx_payment_status_order_synced_id 
       on payment.payment (status, order_synced, id);
