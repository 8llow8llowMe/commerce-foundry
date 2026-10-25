    create schema auth;

    create table auth.admin_audit_log (
        id bigint not null,
        action varchar(40) not null,
        actor_member_id bigint,
        created_at timestamp(6) with time zone not null,
        result varchar(10) not null,
        target varchar(200),
        primary key (id),
        constraint ck_admin_audit_log_result check (result in ('SUCCESS', 'FAILURE'))
    );

    comment on table auth.admin_audit_log is
        '관리자 감사 로그 (추가만 한다 — admin.audit 로거 한 줄과 같은 네 칸)';

    comment on column auth.admin_audit_log.id is
        '감사 로그 아이디 (Snowflake — 시각순)';

    comment on column auth.admin_audit_log.action is
        '행위 (ADMIN_LOGIN / MEMBER_SEARCH …) — 로그 검색 키와 같은 이름';

    comment on column auth.admin_audit_log.actor_member_id is
        '행위한 관리자 회원 아이디 (FK: member.id). 로그인 실패처럼 모르면 null';

    comment on column auth.admin_audit_log.created_at is
        '행위 시각 (동기 기록이라 기록 시각과 같다)';

    comment on column auth.admin_audit_log.result is
        '결과 (SUCCESS / FAILURE) — 실패 이유는 나누지 않는다';

    comment on column auth.admin_audit_log.target is
        '대상 (member:<id> · members?role=..&page=..&size=..). 없으면 null. 개인정보 없음';

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

    create table auth.shipping_address (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        address_line1 varchar(200) not null,
        address_line2 varchar(200),
        city varchar(100),
        country_code varchar(2) not null,
        default_member_id bigint,
        label varchar(30),
        member_id bigint not null,
        postal_code varchar(20),
        recipient_name varchar(50) not null,
        recipient_phone varchar(20) not null,
        state_province varchar(100),
        version bigint not null,
        primary key (id),
        constraint uk_shipping_address_default_member_id unique (default_member_id),
        constraint ck_shipping_address_country_code check (char_length(country_code) = 2 and country_code = upper(country_code)),
        constraint ck_shipping_address_default_member_id check (default_member_id is null or default_member_id = member_id)
    );

    comment on table auth.shipping_address is
        '회원 배송지 주소록';

    comment on column auth.shipping_address.id is
        '배송지 아이디 (Snowflake)';

    comment on column auth.shipping_address.created_at is
        '생성 시각';

    comment on column auth.shipping_address.updated_at is
        '수정 시각';

    comment on column auth.shipping_address.address_line1 is
        '주소 (국내 도로명 · 지번 주소, 해외 street address)';

    comment on column auth.shipping_address.address_line2 is
        '상세 주소 (동 · 호수 등). 없으면 null';

    comment on column auth.shipping_address.city is
        '도시 — 해외 주소는 필수, 국내는 없으면 null';

    comment on column auth.shipping_address.country_code is
        '국가 코드 (ISO 3166-1 alpha-2 대문자)';

    comment on column auth.shipping_address.default_member_id is
        '기본 배송지면 member_id 와 같은 값, 아니면 null — 회원당 기본 하나를 유니크 키로 강제한다 (FK: member.id — member_id 와 같은 값의 복제 칸)';

    comment on column auth.shipping_address.label is
        '배송지 별칭 (집 · 회사). 없으면 null';

    comment on column auth.shipping_address.member_id is
        '회원 아이디 (FK: member.id)';

    comment on column auth.shipping_address.postal_code is
        '우편번호 — KR 은 숫자 5자리 필수, 해외는 없으면 null';

    comment on column auth.shipping_address.recipient_name is
        '받는 사람 이름';

    comment on column auth.shipping_address.recipient_phone is
        '받는 사람 연락처 — 하이픈 · 공백을 뺀 값 (국내 숫자 · 해외 E.164)';

    comment on column auth.shipping_address.state_province is
        '주 · 도 (state / province). 없으면 null';

    comment on column auth.shipping_address.version is
        '낙관적 락 버전';

    create index idx_admin_audit_log_action_id 
       on auth.admin_audit_log (action, id);

    create index idx_admin_audit_log_actor_member_id_id 
       on auth.admin_audit_log (actor_member_id, id);

    create index idx_admin_audit_log_target_id 
       on auth.admin_audit_log (target, id);

    create index idx_member_agreement_member_id_agreement_type 
       on auth.member_agreement (member_id, agreement_type);

    create index idx_shipping_address_member_id 
       on auth.shipping_address (member_id);
