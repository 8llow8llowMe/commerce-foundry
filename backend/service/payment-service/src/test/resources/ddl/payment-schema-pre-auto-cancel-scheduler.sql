
    create schema payment;

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

    create table payment.outbox (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        aggregate_id varchar(100) not null,
        aggregate_type varchar(50) not null,
        available_at timestamp(6) with time zone not null,
        event_type varchar(100) not null,
        last_error_code varchar(100),
        payload text not null,
        publish_attempts integer not null,
        published_at timestamp(6) with time zone,
        schema_version integer not null,
        version bigint not null,
        primary key (id)
    );

    comment on table payment.outbox is
        '이벤트 발행 대기열 (transactional outbox)';

    comment on column payment.outbox.id is
        'outbox 행 아이디 (Snowflake — 시간순, 같은 키의 발행 순서)';

    comment on column payment.outbox.created_at is
        '생성 시각';

    comment on column payment.outbox.updated_at is
        '수정 시각';

    comment on column payment.outbox.aggregate_id is
        '집합 아이디 — Kafka 키(같은 키는 같은 파티션 · 순서)';

    comment on column payment.outbox.aggregate_type is
        '집합 종류 (예: payment)';

    comment on column payment.outbox.available_at is
        '이 시각부터 보낸다 — 예약 · 재시도 백오프 같은 지연 발행';

    comment on column payment.outbox.event_type is
        '이벤트 종류 — 토픽 끝 토막 (예: payment-approved)';

    comment on column payment.outbox.last_error_code is
        '마지막 발행 실패 코드 (예외 타입 · Kafka 오류 코드만, 메시지 원문 없음)';

    comment on column payment.outbox.payload is
        '이벤트 봉투 JSON (개인정보 원문 없음)';

    comment on column payment.outbox.publish_attempts is
        '실패한 발행 시도 수';

    comment on column payment.outbox.published_at is
        'Kafka 에 보낸 시각. 아직이면 null';

    comment on column payment.outbox.schema_version is
        '페이로드 계약 버전 (1부터)';

    comment on column payment.outbox.version is
        '낙관적 락 버전 — 발행 표시 · 실패 기록이 올린다';

    create table payment.payment (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        abort_reason varchar(30) check ((abort_reason in ('REPREPARED','AMOUNT_CHANGED','ORDER_EXPIRED','ADMIN','RECOVERY_NOT_APPROVED'))),
        active_order_id bigint,
        approval_idempotency_key varchar(36),
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
        constraint uk_payment_member_approval_key unique (member_id, approval_idempotency_key),
        constraint ck_payment_total_amount_positive check (total_amount > 0),
        constraint ck_payment_pg_order_id_length check (length(pg_order_id) >= 6),
        constraint ck_payment_active_order_id_status check ((status in ('FAILED','ABORTED','LATE_APPROVED') and active_order_id is null) or (status = 'CANCELED' and (failure_code is not null or abort_reason is not null) and active_order_id is null) or (status not in ('FAILED','ABORTED','LATE_APPROVED') and not (status = 'CANCELED' and (failure_code is not null or abort_reason is not null)) and active_order_id is not null and active_order_id = order_id)),
        constraint ck_payment_approval_started_at_key check (approval_started_at is null or payment_key is not null),
        constraint ck_payment_approval_key_payment_key check (approval_idempotency_key is null or payment_key is not null),
        constraint ck_payment_in_progress_fields check (status not in ('IN_PROGRESS','UNKNOWN') or (payment_key is not null and approval_started_at is not null)),
        constraint ck_payment_approved_fields check ((status in ('DONE','LATE_APPROVED','PARTIAL_CANCELED','CANCELED') and payment_key is not null and method is not null and approved_at is not null) or (status not in ('DONE','LATE_APPROVED','PARTIAL_CANCELED','CANCELED') and method is null and approved_at is null and easy_pay_provider is null and pg_status is null)),
        constraint ck_payment_failed_fields check ((failure_message is null or failure_code is not null) and ((status = 'FAILED' and failure_code is not null) or status in ('LATE_APPROVED','CANCELED') or (status not in ('FAILED','LATE_APPROVED','CANCELED') and failure_code is null))),
        constraint ck_payment_unknown_fields check ((status = 'UNKNOWN' and unknown_reason is not null) or (status in ('READY','IN_PROGRESS','ABORTED','WAITING_FOR_DEPOSIT') and unknown_reason is null) or status in ('DONE','FAILED','LATE_APPROVED','PARTIAL_CANCELED','CANCELED')),
        constraint ck_payment_abort_reason_status check ((status = 'ABORTED' and abort_reason is not null) or status in ('LATE_APPROVED','CANCELED') or (status not in ('ABORTED','LATE_APPROVED','CANCELED') and abort_reason is null)),
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

    comment on column payment.payment.approval_idempotency_key is
        '마지막 승인 요청의 고객 Idempotency-Key (UUID). 승인 시작 전이면 null — (member_id, 이 값) 유니크로 같은 키의 재요청을 알아본다';

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
        'order 거절 회신(order-payment-rejected) 사유 코드. 회신 전 · order-paid 수신이면 null';

    comment on column payment.payment.order_synced is
        'order 회신(order-paid)을 받았는가. DONE 계열만 true — false 는 회신 대기 또는 거절 회신(order_sync_failure_code) (LATE_APPROVED 는 회신 대상 아님)';

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

    create table payment.payment_cancel (
        claim_id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        amount bigint not null,
        currency varchar(3) not null check ((currency in ('KRW','USD'))),
        payment_id bigint not null,
        status varchar(20) not null check ((status in ('PENDING','SUCCEEDED','REJECTED'))),
        version bigint,
        primary key (claim_id),
        constraint ck_payment_cancel_amount_positive check (amount > 0)
    );

    comment on column payment.payment_cancel.created_at is
        '생성 시각';

    comment on column payment.payment_cancel.updated_at is
        '수정 시각';

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

    create table payment.shedlock (
        name varchar(64) not null,
        lock_until timestamp(6) with time zone not null,
        locked_at timestamp(6) with time zone not null,
        locked_by varchar(255) not null,
        primary key (name)
    );

    comment on table payment.shedlock is
        '단일 실행 락 (ShedLock — outbox 릴레이 · 정리)';

    comment on column payment.shedlock.name is
        '락 이름';

    comment on column payment.shedlock.lock_until is
        '이 시각까지 락을 쥔다';

    comment on column payment.shedlock.locked_at is
        '락을 잡은 시각';

    comment on column payment.shedlock.locked_by is
        '락을 잡은 인스턴스 (호스트 이름)';

    create index idx_outbox_published_at_available_at
       on payment.outbox (published_at, available_at);

    create index idx_payment_status_approval_started_at_id
       on payment.payment (status, approval_started_at, id);

    create index idx_payment_order_id
       on payment.payment (order_id);

    create index idx_payment_member_id_id
       on payment.payment (member_id, id);

    create index idx_payment_status_order_synced_id
       on payment.payment (status, order_synced, id);

    create index idx_payment_status_order_synced_order_sync_failure_code_id
       on payment.payment (status, order_synced, order_sync_failure_code, id);

    create index idx_payment_auto_cancel_state_next_attempt_at_payment_id
       on payment.payment_auto_cancel (state, next_attempt_at, payment_id);

    create index idx_payment_cancel_payment_id
       on payment.payment_cancel (payment_id);

    create index idx_payment_webhook_inbox_state_available_at_id
       on payment.payment_webhook_inbox (state, available_at, id);

    create index idx_processed_event_processed_at
       on payment.processed_event (processed_at);
