
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

    create index idx_knowledge_document_locale_published_revision_id
       on support.knowledge_document (locale, published_revision, id);

    create index idx_knowledge_revision_status_valid_from_valid_to_id
       on support.knowledge_revision (status, valid_from, valid_to, id);
