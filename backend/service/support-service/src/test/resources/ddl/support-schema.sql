
    create schema support;

    create table support.knowledge_document (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        latest_revision integer not null,
        locale varchar(2) not null check ((locale in ('KO','EN'))),
        published_revision integer,
        type varchar(20) not null check ((type in ('FAQ','POLICY','TEMPLATE'))),
        version bigint not null,
        primary key (id),
        constraint ck_knowledge_document_revision check (latest_revision > 0 and (published_revision is null or (published_revision > 0 and published_revision <= latest_revision)))
    );

    comment on table support.knowledge_document is
        '상담 지식 문서';

    comment on column support.knowledge_document.id is
        '문서 아이디 (Snowflake)';

    comment on column support.knowledge_document.created_at is
        '생성 시각';

    comment on column support.knowledge_document.updated_at is
        '수정 시각';

    comment on column support.knowledge_document.latest_revision is
        '최신 리비전 번호';

    comment on column support.knowledge_document.locale is
        '정확한 게시 언어';

    comment on column support.knowledge_document.published_revision is
        '현재 게시 리비전 번호 (FK: knowledge_revision.document_id + revision_number)';

    comment on column support.knowledge_document.type is
        '지식 유형';

    comment on column support.knowledge_document.version is
        '문서 낙관적 락 버전';

    create table support.knowledge_revision (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        approved_at timestamp(6) with time zone,
        approved_by bigint,
        body varchar(8000) not null,
        category varchar(30) not null check ((category in ('SHIPPING','SHIPPING_FEE','INTERNATIONAL_SHIPPING','EXCHANGE_REFUND','SIZE','MATERIAL','PRODUCTION_TIME','CUSTOMIZATION','OTHER'))),
        document_id bigint not null,
        published_at timestamp(6) with time zone,
        revision_number integer not null,
        status varchar(20) not null check ((status in ('DRAFT','APPROVED','PUBLISHED','ARCHIVED'))),
        title varchar(200) not null,
        valid_from timestamp(6) with time zone not null,
        valid_to timestamp(6) with time zone,
        version bigint not null,
        primary key (id),
        constraint uk_knowledge_revision_document_id_revision_number unique (document_id, revision_number),
        constraint ck_knowledge_revision_number check (revision_number > 0 and document_id > 0),
        constraint ck_knowledge_revision_content check (length(trim(title)) > 0 and length(trim(body)) > 0),
        constraint ck_knowledge_revision_period check (valid_to is null or valid_to > valid_from),
        constraint ck_knowledge_revision_approval check ((approved_by is null and approved_at is null) or (approved_by is not null and approved_by > 0 and approved_at is not null)),
        constraint ck_knowledge_revision_status_metadata check ((status <> 'DRAFT' or approved_by is null) and (status not in ('APPROVED','PUBLISHED') or (approved_by is not null and approved_at is not null)) and (status <> 'PUBLISHED' or published_at is not null) and (status not in ('DRAFT','APPROVED') or published_at is null) and (published_at is null or approved_at is not null))
    );

    comment on table support.knowledge_revision is
        '상담 지식 리비전';

    comment on column support.knowledge_revision.id is
        '리비전 아이디 (Snowflake)';

    comment on column support.knowledge_revision.created_at is
        '생성 시각';

    comment on column support.knowledge_revision.updated_at is
        '수정 시각';

    comment on column support.knowledge_revision.approved_at is
        '승인 시각';

    comment on column support.knowledge_revision.approved_by is
        '승인 관리자 아이디 (외부 FK: auth-service member.id)';

    comment on column support.knowledge_revision.body is
        '승인 대상 본문';

    comment on column support.knowledge_revision.category is
        '지식 카테고리';

    comment on column support.knowledge_revision.document_id is
        '문서 아이디 (FK: knowledge_document.id)';

    comment on column support.knowledge_revision.published_at is
        '게시 시각';

    comment on column support.knowledge_revision.revision_number is
        '문서 내 리비전 번호';

    comment on column support.knowledge_revision.status is
        '리비전 상태';

    comment on column support.knowledge_revision.title is
        '승인 대상 제목';

    comment on column support.knowledge_revision.valid_from is
        '유효 시작 시각 (포함)';

    comment on column support.knowledge_revision.valid_to is
        '유효 종료 시각 (제외), null이면 무기한';

    comment on column support.knowledge_revision.version is
        '리비전 낙관적 락 버전';

    create table support.support_conversation (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        channel varchar(20) not null check ((channel in ('WEB'))),
        guest_session_id bigint,
        last_seq bigint not null,
        locale varchar(2) not null check ((locale in ('KO','EN'))),
        member_id bigint,
        status varchar(20) not null check ((status in ('AI_ACTIVE','WAITING_AGENT','HUMAN_ACTIVE','RESOLVED','CLOSED'))),
        version bigint not null,
        primary key (id),
        constraint ck_support_conversation_state check (id > 0 and last_seq >= 0 and ((member_id is not null and member_id > 0 and guest_session_id is null) or (member_id is null and guest_session_id is not null and guest_session_id > 0)))
    );

    comment on table support.support_conversation is
        '고객 상담 대화';

    comment on column support.support_conversation.id is
        '대화 아이디 (Snowflake)';

    comment on column support.support_conversation.created_at is
        '생성 시각';

    comment on column support.support_conversation.updated_at is
        '수정 시각';

    comment on column support.support_conversation.channel is
        '상담 채널';

    comment on column support.support_conversation.guest_session_id is
        '비회원 세션 아이디 (FK: support_guest_session.id)';

    comment on column support.support_conversation.last_seq is
        '마지막 메시지 순번, 신규 대화는 0';

    comment on column support.support_conversation.locale is
        '상담 언어';

    comment on column support.support_conversation.member_id is
        '회원 아이디 (외부 FK: auth-service member.id)';

    comment on column support.support_conversation.status is
        '상담 상태';

    comment on column support.support_conversation.version is
        '낙관적 락 버전';

    create table support.support_guest_session (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        expires_at timestamp(6) with time zone not null,
        revoked_at timestamp(6) with time zone,
        token_hash varchar(43) not null,
        version bigint not null,
        primary key (id),
        constraint uk_support_guest_session_token_hash unique (token_hash),
        constraint ck_support_guest_session_state check (id > 0 and length(token_hash) = 43)
    );

    comment on table support.support_guest_session is
        '비회원 상담 자격 증명';

    comment on column support.support_guest_session.id is
        '비회원 세션 아이디 (Snowflake)';

    comment on column support.support_guest_session.created_at is
        '생성 시각';

    comment on column support.support_guest_session.updated_at is
        '수정 시각';

    comment on column support.support_guest_session.expires_at is
        '자격 증명 절대 만료 시각 (UTC)';

    comment on column support.support_guest_session.revoked_at is
        '자격 증명 폐기 시각 (UTC)';

    comment on column support.support_guest_session.token_hash is
        'SHA-256 자격 증명 지문, 토큰 원문 미저장';

    comment on column support.support_guest_session.version is
        '낙관적 락 버전';

    create table support.support_message (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        author_guest_session_id bigint,
        author_member_id bigint,
        author_type varchar(20) not null check ((author_type in ('CUSTOMER'))),
        client_message_id varchar(36) not null,
        content varchar(2000) not null,
        conversation_id bigint not null,
        seq bigint not null,
        status varchar(20) not null check ((status in ('COMPLETED'))),
        primary key (id),
        constraint uk_support_message_conversation_id_seq unique (conversation_id, seq),
        constraint uk_support_message_conversation_id_client_message_id unique (conversation_id, client_message_id),
        constraint ck_support_message_state check (id > 0 and conversation_id > 0 and seq > 0 and length(trim(content)) > 0 and ((author_member_id is not null and author_member_id > 0 and author_guest_session_id is null) or (author_member_id is null and author_guest_session_id is not null and author_guest_session_id > 0)))
    );

    comment on table support.support_message is
        '고객 상담 메시지';

    comment on column support.support_message.id is
        '메시지 아이디 (Snowflake)';

    comment on column support.support_message.created_at is
        '생성 시각';

    comment on column support.support_message.updated_at is
        '수정 시각';

    comment on column support.support_message.author_guest_session_id is
        '원 작성 비회원 세션 아이디 (FK: support_guest_session.id)';

    comment on column support.support_message.author_member_id is
        '원 작성 회원 아이디 (외부 FK: auth-service member.id)';

    comment on column support.support_message.author_type is
        '원 작성자 유형';

    comment on column support.support_message.client_message_id is
        '대화별 멱등 요청 키 (canonical lowercase UUID)';

    comment on column support.support_message.content is
        'NFKC 정규화 및 개인정보 마스킹된 본문';

    comment on column support.support_message.conversation_id is
        '대화 아이디 (FK: support_conversation.id)';

    comment on column support.support_message.seq is
        '대화별 단조 증가 순번';

    comment on column support.support_message.status is
        '메시지 완료 상태';

    create index idx_knowledge_document_locale_published_revision_id
       on support.knowledge_document (locale, published_revision, id);

    create index idx_knowledge_revision_status_valid_from_valid_to_id
       on support.knowledge_revision (status, valid_from, valid_to, id);
