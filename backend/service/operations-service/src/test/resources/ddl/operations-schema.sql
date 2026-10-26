
    create schema operations;

    create table operations.job (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        active_dedup_key varchar(200),
        attempt integer not null,
        cancel_requested boolean not null,
        cancel_requested_by_member_id bigint,
        dedup_key varchar(200),
        error_code varchar(50),
        error_message varchar(500),
        finished_at timestamp(6) with time zone,
        lease_expires_at timestamp(6) with time zone,
        max_attempts integer not null,
        payload_json text not null,
        priority integer not null,
        progress_message varchar(200),
        progress_percent integer,
        requested_by_member_id bigint,
        result_json text,
        scheduled_at timestamp(6) with time zone not null,
        started_at timestamp(6) with time zone,
        status varchar(20) not null check ((status in ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED'))),
        type varchar(64) not null,
        version bigint not null,
        worker_id varchar(100),
        primary key (id),
        constraint uk_job_type_active_dedup_key unique (type, active_dedup_key),
        constraint ck_job_attempt_range check (attempt >= 0 and attempt <= max_attempts),
        constraint ck_job_max_attempts_positive check (max_attempts > 0),
        constraint ck_job_progress_percent_range check (progress_percent is null or (progress_percent >= 0 and progress_percent <= 100))
    );

    comment on table operations.job is
        '백그라운드 작업';

    comment on column operations.job.id is
        '작업 아이디 (Snowflake)';

    comment on column operations.job.created_at is
        '생성 시각';

    comment on column operations.job.updated_at is
        '수정 시각';

    comment on column operations.job.active_dedup_key is
        '활성 작업의 멱등 키 — QUEUED · RUNNING 동안만 dedup_key 와 같고 끝나면 null (키당 활성 작업 1건)';

    comment on column operations.job.attempt is
        '가져간 시도 수 (0 부터). RUNNING 이면 실행 중인 시도 번호';

    comment on column operations.job.cancel_requested is
        '취소 요청을 받았다 — RUNNING 이면 다음 checkpoint 에서 멈춘다';

    comment on column operations.job.cancel_requested_by_member_id is
        '취소를 요청한 어드민 (외부 FK: auth-service member.id). 없으면 null';

    comment on column operations.job.dedup_key is
        '멱등 키. 없으면 null';

    comment on column operations.job.error_code is
        '마지막 실패 코드 — 재시도 대기 중에도 남고 성공하면 지운다';

    comment on column operations.job.error_message is
        '마지막 실패 설명 (개인정보 원문 없음)';

    comment on column operations.job.finished_at is
        '종료 시각 (SUCCEEDED / FAILED / CANCELLED 일 때만)';

    comment on column operations.job.lease_expires_at is
        'lease 만료 시각 (RUNNING 일 때만) — 지나면 만료 판정 트리거가 회수한다';

    comment on column operations.job.max_attempts is
        '시도 횟수 상한 (첫 실행 포함)';

    comment on column operations.job.payload_json is
        '핸들러 입력 JSON (16KiB 이하, 개인정보 원문 없음)';

    comment on column operations.job.priority is
        '우선순위 — 등록 기록용. 실행 순서는 Kafka 파티션 도착 순서다';

    comment on column operations.job.progress_message is
        '진행 문구 (개인정보 원문 없음)';

    comment on column operations.job.progress_percent is
        '진행률 0 ~ 100. 보고 전이면 null';

    comment on column operations.job.requested_by_member_id is
        '등록한 어드민 (외부 FK: auth-service member.id). 시스템 등록이면 null';

    comment on column operations.job.result_json is
        '작은 결과 JSON (기본 64KiB 이하, 개인정보 원문 없음)';

    comment on column operations.job.scheduled_at is
        '이 시각부터 실행할 수 있다 — 재시도면 백오프가 더해진 시각';

    comment on column operations.job.started_at is
        '이번 시도를 가져간 시각';

    comment on column operations.job.status is
        '작업 상태 (QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED)';

    comment on column operations.job.type is
        '작업 종류 — 핸들러 이름 ([A-Z0-9_]{1,64})';

    comment on column operations.job.version is
        '낙관적 락 버전 — 상태 전이와 lease · 진행률 · 취소 요청 갱신이 모두 올린다';

    comment on column operations.job.worker_id is
        '작업을 쥔 워커 ({앱}:{호스트}:{pid}:{난수}). 끝난 작업은 마지막 워커, QUEUED 는 null';

    create table operations.notification (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        kind varchar(32) not null check ((kind in ('JOB_SUCCEEDED','JOB_FAILED','JOB_CANCELLED','ANOMALY','LOW_STOCK','CS_HANDOFF'))),
        link_path varchar(200),
        read_at timestamp(6) with time zone,
        recipient_member_id bigint not null,
        severity varchar(16) not null check ((severity in ('INFO','WARNING','CRITICAL'))),
        source_id bigint not null,
        source_type varchar(32) not null,
        title varchar(120) not null,
        version bigint not null,
        primary key (id),
        constraint uk_notification_source_type_source_id_kind unique (source_type, source_id, kind)
    );

    comment on table operations.notification is
        '어드민 알림';

    comment on column operations.notification.id is
        '알림 아이디 (Snowflake)';

    comment on column operations.notification.created_at is
        '생성 시각';

    comment on column operations.notification.updated_at is
        '수정 시각';

    comment on column operations.notification.kind is
        '알림 종류';

    comment on column operations.notification.link_path is
        '어드민 화면 경로. 없으면 null';

    comment on column operations.notification.read_at is
        '읽은 시각. 아직이면 null';

    comment on column operations.notification.recipient_member_id is
        '받는 어드민 (외부 FK: auth-service member.id)';

    comment on column operations.notification.severity is
        '심각도';

    comment on column operations.notification.source_id is
        '출처 아이디. 작업이면 job.id';

    comment on column operations.notification.source_type is
        '출처 종류 (JOB 등)';

    comment on column operations.notification.title is
        '제목 (개인정보 원문 없음)';

    comment on column operations.notification.version is
        '낙관적 락 버전';

    create table operations.outbox (
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

    comment on table operations.outbox is
        '이벤트 발행 대기열 (transactional outbox)';

    comment on column operations.outbox.id is
        'outbox 행 아이디 (Snowflake — 시간순, 같은 키의 발행 순서)';

    comment on column operations.outbox.created_at is
        '생성 시각';

    comment on column operations.outbox.updated_at is
        '수정 시각';

    comment on column operations.outbox.aggregate_id is
        '집합 아이디 — Kafka 키(같은 키는 같은 파티션 · 순서)';

    comment on column operations.outbox.aggregate_type is
        '집합 종류 (예: job)';

    comment on column operations.outbox.available_at is
        '이 시각부터 보낸다 — 예약 · 재시도 백오프 같은 지연 발행';

    comment on column operations.outbox.event_type is
        '이벤트 종류 — 토픽 끝 토막 (예: job-run)';

    comment on column operations.outbox.last_error_code is
        '마지막 발행 실패 코드 (예외 타입 · Kafka 오류 코드만, 메시지 원문 없음)';

    comment on column operations.outbox.payload is
        '이벤트 봉투 JSON (개인정보 원문 없음)';

    comment on column operations.outbox.publish_attempts is
        '실패한 발행 시도 수';

    comment on column operations.outbox.published_at is
        'Kafka 에 보낸 시각. 아직이면 null';

    comment on column operations.outbox.schema_version is
        '페이로드 계약 버전 (1부터)';

    comment on column operations.outbox.version is
        '낙관적 락 버전 — 발행 표시 · 실패 기록이 올린다';

    create table operations.shedlock (
        name varchar(64) not null,
        lock_until timestamp(6) with time zone not null,
        locked_at timestamp(6) with time zone not null,
        locked_by varchar(255) not null,
        primary key (name)
    );

    comment on table operations.shedlock is
        '단일 실행 락 (ShedLock — outbox 릴레이)';

    comment on column operations.shedlock.name is
        '락 이름';

    comment on column operations.shedlock.lock_until is
        '이 시각까지 락을 쥔다';

    comment on column operations.shedlock.locked_at is
        '락을 잡은 시각';

    comment on column operations.shedlock.locked_by is
        '락을 잡은 인스턴스 (호스트 이름)';

    create index idx_job_status_lease_expires_at 
       on operations.job (status, lease_expires_at);

    create index idx_job_type_id 
       on operations.job (type, id);

    create index idx_notification_recipient_member_id_id 
       on operations.notification (recipient_member_id, id);

    create index idx_notification_recipient_member_id_read_at 
       on operations.notification (recipient_member_id, read_at);

    create index idx_notification_created_at 
       on operations.notification (created_at);

    create index idx_outbox_published_at_available_at 
       on operations.outbox (published_at, available_at);
