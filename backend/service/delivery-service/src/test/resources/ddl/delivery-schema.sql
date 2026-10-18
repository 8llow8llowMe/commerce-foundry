
    create schema delivery;

    create table delivery.shipping_policy (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        base_fee_amount bigint not null,
        base_fee_currency varchar(3) not null check ((base_fee_currency in ('KRW','USD'))),
        free_shipping_threshold_amount bigint,
        free_shipping_threshold_currency varchar(3) check ((free_shipping_threshold_currency in ('KRW','USD'))),
        scope varchar(20) not null check ((scope in ('DOMESTIC'))),
        version bigint not null,
        primary key (id),
        constraint uk_shipping_policy_scope unique (scope),
        constraint ck_shipping_policy_base_fee_non_negative check (base_fee_amount >= 0),
        constraint ck_shipping_policy_free_shipping_threshold_pair check ((free_shipping_threshold_amount is null and free_shipping_threshold_currency is null) or (free_shipping_threshold_amount is not null and free_shipping_threshold_currency is not null)),
        constraint ck_shipping_policy_free_shipping_threshold_positive check (free_shipping_threshold_amount is null or free_shipping_threshold_amount > 0),
        constraint ck_shipping_policy_free_shipping_threshold_currency check (free_shipping_threshold_currency is null or free_shipping_threshold_currency = base_fee_currency)
    );

    comment on table delivery.shipping_policy is
        '배송비 정책 (배송 범위마다 1개)';

    comment on column delivery.shipping_policy.id is
        '배송비 정책 아이디 (Snowflake)';

    comment on column delivery.shipping_policy.created_at is
        '생성 시각';

    comment on column delivery.shipping_policy.updated_at is
        '수정 시각';

    comment on column delivery.shipping_policy.base_fee_amount is
        '기본 배송비 (0 이상, 최소 화폐 단위)';

    comment on column delivery.shipping_policy.base_fee_currency is
        '정책 통화 — 정책의 모든 금액이 이 통화다';

    comment on column delivery.shipping_policy.free_shipping_threshold_amount is
        '무료배송 기준 주문 금액 (할인 후 상품 합계, 0 초과, 최소 화폐 단위). 이 금액 이상이면 기본 배송비를 받지 않는다. null 이면 무료배송 없음';

    comment on column delivery.shipping_policy.free_shipping_threshold_currency is
        '무료배송 기준 통화 — 기본 배송비 통화와 같다';

    comment on column delivery.shipping_policy.scope is
        '배송 범위 (DOMESTIC). 범위마다 정책이 하나다';

    comment on column delivery.shipping_policy.version is
        '낙관적 락 버전. 교체마다 1 증가';

    create table delivery.shipping_remote_area (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        name varchar(50) not null,
        postal_code_from varchar(5) not null,
        postal_code_to varchar(5) not null,
        shipping_policy_id bigint not null,
        surcharge_amount bigint not null,
        surcharge_currency varchar(3) not null check ((surcharge_currency in ('KRW','USD'))),
        primary key (id),
        constraint ck_shipping_remote_area_postal_code_range check (postal_code_from <= postal_code_to),
        constraint ck_shipping_remote_area_surcharge_non_negative check (surcharge_amount >= 0)
    );

    comment on table delivery.shipping_remote_area is
        '도서산간 우편번호 구간 (배송비 정책별)';

    comment on column delivery.shipping_remote_area.id is
        '도서산간 구간 아이디 (Snowflake). 정책 교체마다 새로 발급';

    comment on column delivery.shipping_remote_area.created_at is
        '생성 시각';

    comment on column delivery.shipping_remote_area.updated_at is
        '수정 시각';

    comment on column delivery.shipping_remote_area.name is
        '구간 이름 (예: 제주)';

    comment on column delivery.shipping_remote_area.postal_code_from is
        '시작 우편번호 (포함, 숫자 5자리)';

    comment on column delivery.shipping_remote_area.postal_code_to is
        '끝 우편번호 (포함, 숫자 5자리)';

    comment on column delivery.shipping_remote_area.shipping_policy_id is
        '배송비 정책 아이디 (FK: shipping_policy.id)';

    comment on column delivery.shipping_remote_area.surcharge_amount is
        '추가 배송비 (0 이상, 최소 화폐 단위). 무료배송이어도 받는다';

    comment on column delivery.shipping_remote_area.surcharge_currency is
        '추가 배송비 통화 — 정책 통화와 같아야 한다';

    create index idx_shipping_remote_area_shipping_policy_id 
       on delivery.shipping_remote_area (shipping_policy_id);
