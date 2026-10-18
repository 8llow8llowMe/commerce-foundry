    create schema auth;

    create table auth.member (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        email varchar(254) not null,
        name varchar(50) not null,
        password_hash varchar(100) not null,
        phone varchar(20),
        role varchar(20) not null check ((role in ('CUSTOMER','ADMIN'))),
        version bigint not null,
        primary key (id),
        constraint uk_member_email unique (email),
        constraint ck_member_email_normalized check (email = lower(email))
    );

    comment on table auth.member is
        '회원 (고객 · 관리자)';

    comment on column auth.member.id is
        '회원 아이디 (Snowflake)';

    comment on column auth.member.created_at is
        '생성 시각';

    comment on column auth.member.updated_at is
        '수정 시각';

    comment on column auth.member.email is
        '로그인 이메일 — 앞뒤 공백 제거 + 소문자로 정규화한 값';

    comment on column auth.member.name is
        '이름';

    comment on column auth.member.password_hash is
        '비밀번호 BCrypt 해시. 원문은 저장하지 않는다';

    comment on column auth.member.phone is
        '휴대폰 번호 — 숫자만 (01012345678). 없으면 null';

    comment on column auth.member.role is
        '역할 (CUSTOMER / ADMIN) — access 토큰 role claim 값';

    comment on column auth.member.version is
        '낙관적 락 버전';

    create table auth.member_agreement (
        id bigint not null,
        agreed boolean not null,
        agreement_type varchar(30) not null check ((agreement_type in ('TERMS_OF_SERVICE','PRIVACY_POLICY','MARKETING'))),
        created_at timestamp(6) with time zone not null,
        member_id bigint not null,
        terms_version varchar(20) not null,
        primary key (id)
    );

    comment on table auth.member_agreement is
        '회원 동의 이력 (추가만 한다 — 현재 상태는 회원 · 유형별 최근 행)';

    comment on column auth.member_agreement.id is
        '동의 이력 아이디 (Snowflake)';

    comment on column auth.member_agreement.agreed is
        '동의 여부 — false 는 거절 · 철회';

    comment on column auth.member_agreement.agreement_type is
        '동의 유형 (TERMS_OF_SERVICE / PRIVACY_POLICY / MARKETING)';

    comment on column auth.member_agreement.created_at is
        '동의 · 철회 시각';

    comment on column auth.member_agreement.member_id is
        '회원 아이디 (FK: member.id)';

    comment on column auth.member_agreement.terms_version is
        '동의 당시 시행 중이던 약관 버전 (auth.agreement.versions.*)';

    create index idx_member_agreement_member_id_agreement_type 
       on auth.member_agreement (member_id, agreement_type);
