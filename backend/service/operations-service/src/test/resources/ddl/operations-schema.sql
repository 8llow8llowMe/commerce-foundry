
    create schema operations;

    create table operations.ai_usage_call (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        cache_creation_input_tokens bigint not null,
        cache_read_input_tokens bigint not null,
        called_at timestamp(6) with time zone not null,
        caller_ref varchar(100),
        cost_currency varchar(3) not null,
        cost_micros bigint,
        elapsed_ms integer,
        estimate_cost_micros bigint not null,
        feature varchar(63) not null,
        http_status integer,
        image_count integer not null,
        input_chars integer not null,
        input_tokens bigint not null,
        message_id varchar(64),
        model varchar(64),
        output_tokens bigint not null,
        period varchar(7) not null,
        priced_by varchar(16) check ((priced_by in ('CONFIG','MAX_FALLBACK'))),
        pricing_version varchar(20),
        prompt_version integer,
        reason varchar(32),
        requested_model varchar(64) not null,
        settled_at timestamp(6) with time zone,
        status varchar(16) not null check ((status in ('PENDING','COMPLETED','UNUSABLE','FAILED','TIMEOUT','NOT_SENT','ABANDONED'))),
        structured boolean not null,
        primary key (id),
        constraint ck_ai_usage_call_non_negative check (image_count >= 0 and input_chars >= 0 and estimate_cost_micros >= 0 and input_tokens >= 0 and output_tokens >= 0 and cache_creation_input_tokens >= 0 and cache_read_input_tokens >= 0 and (cost_micros is null or cost_micros >= 0)),
        constraint ck_ai_usage_call_settled check ((status = 'PENDING' and cost_micros is null and settled_at is null) or (status <> 'PENDING' and cost_micros is not null and settled_at is not null)),
        constraint ck_ai_usage_call_period_format check (length(period) = 7 and substr(period, 5, 1) = '-' and ltrim(replace(period, '-', ''), '0123456789') = '')
    );

    comment on table operations.ai_usage_call is
        'AI 호출 사용량 · 비용 원장 (호출 단위)';

    comment on column operations.ai_usage_call.id is
        'AI 호출 아이디 (Snowflake)';

    comment on column operations.ai_usage_call.created_at is
        '생성 시각';

    comment on column operations.ai_usage_call.updated_at is
        '수정 시각';

    comment on column operations.ai_usage_call.cache_creation_input_tokens is
        '실측 캐시 쓰기 입력 토큰. 응답이 없으면 0';

    comment on column operations.ai_usage_call.cache_read_input_tokens is
        '실측 캐시 읽기 입력 토큰. 응답이 없으면 0';

    comment on column operations.ai_usage_call.called_at is
        '예약 시각 — 호출 직전';

    comment on column operations.ai_usage_call.caller_ref is
        '호출한 작업 식별자 (예 job:123, 내부 PK 만). 없으면 null';

    comment on column operations.ai_usage_call.cost_currency is
        '비용 통화 — 언제나 USD';

    comment on column operations.ai_usage_call.cost_micros is
        '확정 비용 (micro-USD, cost_currency, 합계 올림). PENDING 이면 null';

    comment on column operations.ai_usage_call.elapsed_ms is
        '자리를 잡은 뒤 응답 · 실패까지 걸린 시간 (ms, SDK 재시도 포함). PENDING · ABANDONED 면 null';

    comment on column operations.ai_usage_call.estimate_cost_micros is
        '예약한 예상 비용 (micro-USD, cost_currency) — TIMEOUT · ABANDONED 는 이 값이 확정 비용이다';

    comment on column operations.ai_usage_call.feature is
        '기능 이름 (ai-core 요청의 feature)';

    comment on column operations.ai_usage_call.http_status is
        'API 오류 응답의 HTTP 상태 코드. 오류 응답이 아니면 null';

    comment on column operations.ai_usage_call.image_count is
        '이미지 장수';

    comment on column operations.ai_usage_call.input_chars is
        '마스킹 뒤 입력 글자 수 — 예상 비용의 입력 몫';

    comment on column operations.ai_usage_call.input_tokens is
        '실측 입력 토큰 (캐시 제외). 응답이 없으면 0';

    comment on column operations.ai_usage_call.message_id is
        'API 메시지 ID. 응답이 없으면 null';

    comment on column operations.ai_usage_call.model is
        '답한 모델 ID. 응답이 없으면 요청한 모델, PENDING · ABANDONED 면 null';

    comment on column operations.ai_usage_call.output_tokens is
        '실측 출력 토큰. 응답이 없으면 0';

    comment on column operations.ai_usage_call.period is
        '비용을 매기는 월 YYYY-MM (예약 시각, operations.ai.budget.zone 기준). ai_usage_month.period 와 같다';

    comment on column operations.ai_usage_call.priced_by is
        '단가 출처 — CONFIG(설정 단가) / MAX_FALLBACK(설정 밖 모델, 항목별 최고 단가)';

    comment on column operations.ai_usage_call.pricing_version is
        '비용 계산에 쓴 단가표 버전 (operations.ai.pricing.version). PENDING 이면 예상치의 버전';

    comment on column operations.ai_usage_call.prompt_version is
        '프롬프트 템플릿 버전. 템플릿 없이 만든 요청이면 null';

    comment on column operations.ai_usage_call.reason is
        '실패 원인 (ai-core AiException.Reason). 없으면 null';

    comment on column operations.ai_usage_call.requested_model is
        '요청한 모델 ID — 예상 비용의 단가';

    comment on column operations.ai_usage_call.settled_at is
        '닫은 시각 (정산 · 회수). PENDING 이면 null';

    comment on column operations.ai_usage_call.status is
        '상태 (PENDING / COMPLETED / UNUSABLE / FAILED / TIMEOUT / NOT_SENT / ABANDONED)';

    comment on column operations.ai_usage_call.structured is
        '구조화 출력 호출이다';

    create table operations.ai_usage_month (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        call_count bigint not null,
        cost_currency varchar(3) not null,
        exceeded_at timestamp(6) with time zone,
        limit_micros_snapshot bigint not null,
        period varchar(7) not null,
        reserved_micros bigint not null,
        settled_micros bigint not null,
        version bigint not null,
        warned_at timestamp(6) with time zone,
        primary key (id),
        constraint uk_ai_usage_month_period unique (period),
        constraint ck_ai_usage_month_non_negative check (reserved_micros >= 0 and settled_micros >= 0 and call_count >= 0 and limit_micros_snapshot >= 0),
        constraint ck_ai_usage_month_reserved_covers_settled check (reserved_micros >= settled_micros),
        constraint ck_ai_usage_month_period_format check (length(period) = 7 and substr(period, 5, 1) = '-' and ltrim(replace(period, '-', ''), '0123456789') = '')
    );

    comment on table operations.ai_usage_month is
        'AI 사용 월 카운터 (월 한도 예약 · 정산 누계)';

    comment on column operations.ai_usage_month.id is
        '월 카운터 아이디 (Snowflake)';

    comment on column operations.ai_usage_month.created_at is
        '생성 시각';

    comment on column operations.ai_usage_month.updated_at is
        '수정 시각';

    comment on column operations.ai_usage_month.call_count is
        '닫힌 호출 수 (보내지 않은 NOT_SENT 제외)';

    comment on column operations.ai_usage_month.cost_currency is
        '금액 칸의 통화 — 언제나 USD';

    comment on column operations.ai_usage_month.exceeded_at is
        '한도를 처음 넘은 시각(첫 예약 거절 또는 정산 뒤 누계 ≥ 한도). 기간당 한 번. 아직이면 null';

    comment on column operations.ai_usage_month.limit_micros_snapshot is
        '마지막 예약 때의 월 한도 (micro-USD, cost_currency) — 설정을 바꾸면 다음 예약부터 바뀐다';

    comment on column operations.ai_usage_month.period is
        '월 YYYY-MM (operations.ai.budget.zone 기준)';

    comment on column operations.ai_usage_month.reserved_micros is
        '예약 누계 (micro-USD, cost_currency) — 확정 비용 + 진행 중 예약의 예상치. 한도 판정 대상';

    comment on column operations.ai_usage_month.settled_micros is
        '확정 비용 누계 (micro-USD, cost_currency)';

    comment on column operations.ai_usage_month.version is
        '낙관적 락 버전 — 조건부 UPDATE 마다 올린다';

    comment on column operations.ai_usage_month.warned_at is
        '경고 임계(한도 × warn-ratio)를 처음 넘은 시각. 기간당 한 번. 아직이면 null';

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

    create table operations.onboarding_tour_progress (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        current_step integer,
        finished_at timestamp(6) with time zone,
        guide_key varchar(64) not null,
        guide_version integer not null,
        member_id bigint not null,
        status varchar(16) not null check ((status in ('NOT_STARTED','IN_PROGRESS','COMPLETED','SKIPPED'))),
        version bigint not null,
        primary key (id),
        constraint uk_onboarding_tour_progress_member_id_guide_key unique (member_id, guide_key),
        constraint ck_onboarding_tour_progress_status check (status in ('IN_PROGRESS','COMPLETED','SKIPPED')),
        constraint ck_onboarding_tour_progress_current_step check (current_step is null or current_step >= 0)
    );

    comment on table operations.onboarding_tour_progress is
        '운영자별 안내 투어 진행 기록';

    comment on column operations.onboarding_tour_progress.id is
        '진행 기록 아이디 (Snowflake)';

    comment on column operations.onboarding_tour_progress.created_at is
        '생성 시각';

    comment on column operations.onboarding_tour_progress.updated_at is
        '수정 시각';

    comment on column operations.onboarding_tour_progress.current_step is
        '마지막으로 본 단계 (0부터). 모르면 null';

    comment on column operations.onboarding_tour_progress.finished_at is
        '완료 · 건너뜀이 된 시각. 끝나지 않았으면 null';

    comment on column operations.onboarding_tour_progress.guide_key is
        '안내 키 — 설정 허용 목록의 key ([a-z][a-z0-9-]{0,63})';

    comment on column operations.onboarding_tour_progress.guide_version is
        '기록한 안내 버전 (1부터). 설정 버전과 다르면 미시작으로 보인다';

    comment on column operations.onboarding_tour_progress.member_id is
        '운영자 (외부 FK: auth-service member.id)';

    comment on column operations.onboarding_tour_progress.status is
        '안내 상태 (IN_PROGRESS / COMPLETED / SKIPPED). 미시작은 행이 없다';

    comment on column operations.onboarding_tour_progress.version is
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
        '단일 실행 락 (ShedLock — outbox 릴레이 · 정기 작업 플래너)';

    comment on column operations.shedlock.name is
        '락 이름';

    comment on column operations.shedlock.lock_until is
        '이 시각까지 락을 쥔다';

    comment on column operations.shedlock.locked_at is
        '락을 잡은 시각';

    comment on column operations.shedlock.locked_by is
        '락을 잡은 인스턴스 (호스트 이름)';

    create index idx_ai_usage_call_called_at_id 
       on operations.ai_usage_call (called_at, id);

    create index idx_ai_usage_call_status_called_at 
       on operations.ai_usage_call (status, called_at);

    create index idx_job_status_lease_expires_at 
       on operations.job (status, lease_expires_at);

    create index idx_job_type_dedup_key 
       on operations.job (type, dedup_key);

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
