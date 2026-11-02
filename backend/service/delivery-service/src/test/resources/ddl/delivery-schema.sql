
    create schema delivery;

    create table delivery.shipment (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        carrier varchar(20) not null check ((carrier in ('CJ_LOGISTICS','KOREA_POST','LOTTE','HANJIN','LOGEN','EMS','DHL','FEDEX','UPS','OTHER'))),
        delivered_at timestamp(6) with time zone,
        member_id bigint,
        order_id bigint not null,
        order_synced boolean not null,
        shipped_at timestamp(6) with time zone not null,
        shipping_scope varchar(20) check ((shipping_scope in ('DOMESTIC','INTERNATIONAL'))),
        status varchar(20) not null check ((status in ('SHIPPED','IN_TRANSIT','DELIVERED'))),
        tracking_number varchar(50) not null,
        version bigint not null,
        primary key (id),
        constraint uk_shipment_order_id unique (order_id),
        constraint ck_shipment_synced_member check (order_synced = false or member_id is not null),
        constraint ck_shipment_delivered_at_status check ((status = 'DELIVERED' and delivered_at is not null) or (status <> 'DELIVERED' and delivered_at is null))
    );

    comment on table delivery.shipment is
        '송장(배송) — 주문당 1건';

    comment on column delivery.shipment.id is
        '송장(배송) 아이디 (Snowflake) — order 에 deliveryId 로 보낸다';

    comment on column delivery.shipment.created_at is
        '생성 시각';

    comment on column delivery.shipment.updated_at is
        '수정 시각';

    comment on column delivery.shipment.carrier is
        '택배사 코드 (CarrierCode)';

    comment on column delivery.shipment.delivered_at is
        '배송 완료 시각. 배송 완료 상태에만 있다';

    comment on column delivery.shipment.member_id is
        '주문한 회원 아이디 (외부 FK: auth-service member.id) — 출고 반영 응답의 스냅숏. 반영 전에는 null';

    comment on column delivery.shipment.order_id is
        '주문 아이디 (외부 FK: order-service orders.id). 주문당 1건';

    comment on column delivery.shipment.order_synced is
        '주문 상태 반영 여부. 반영된 송장은 지우거나 다른 송장으로 바꾸지 않는다';

    comment on column delivery.shipment.shipped_at is
        '출고 시각 — order 의 shipped_at 과 같은 값';

    comment on column delivery.shipment.shipping_scope is
        '배송 범위 (DOMESTIC · INTERNATIONAL) — 출고 반영 응답의 배송 국가로 정한다. 반영 전이거나 국가를 읽지 못했으면 null';

    comment on column delivery.shipment.status is
        '배송 상태 (SHIPPED · IN_TRANSIT · DELIVERED)';

    comment on column delivery.shipment.tracking_number is
        '송장 번호 (영문 · 숫자 · 하이픈)';

    comment on column delivery.shipment.version is
        '낙관적 락 버전';

    create table delivery.shipment_status_history (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        changed_by varchar(20) not null check ((changed_by in ('ADMIN','SYSTEM'))),
        changed_by_id bigint,
        from_status varchar(20) check ((from_status in ('SHIPPED','IN_TRANSIT','DELIVERED'))),
        shipment_id bigint not null,
        to_status varchar(20) not null check ((to_status in ('SHIPPED','IN_TRANSIT','DELIVERED'))),
        primary key (id),
        constraint ck_shipment_status_history_admin_actor check (changed_by <> 'ADMIN' or changed_by_id is not null)
    );

    comment on table delivery.shipment_status_history is
        '배송 상태 이력 (append-only)';

    comment on column delivery.shipment_status_history.id is
        '배송 상태 이력 아이디 (Snowflake)';

    comment on column delivery.shipment_status_history.created_at is
        '생성 시각';

    comment on column delivery.shipment_status_history.updated_at is
        '수정 시각';

    comment on column delivery.shipment_status_history.changed_by is
        '변경 주체 (ADMIN · SYSTEM)';

    comment on column delivery.shipment_status_history.changed_by_id is
        '바꾼 관리자 회원 아이디 (외부 FK: auth-service member.id) — access 토큰의 memberId. 자동 처리면 null';

    comment on column delivery.shipment_status_history.from_status is
        '바뀌기 전 배송 상태. 등록 줄이면 null';

    comment on column delivery.shipment_status_history.shipment_id is
        '송장 아이디 (FK: shipment.id)';

    comment on column delivery.shipment_status_history.to_status is
        '바뀐 배송 상태';

    create table delivery.shipping_policy (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        base_fee_amount bigint not null,
        base_fee_currency varchar(3) not null check ((base_fee_currency in ('KRW','USD'))),
        customs_notice varchar(1000),
        free_shipping_threshold_amount bigint,
        free_shipping_threshold_currency varchar(3) check ((free_shipping_threshold_currency in ('KRW','USD'))),
        scope varchar(20) not null check ((scope in ('DOMESTIC','INTERNATIONAL'))),
        version bigint not null,
        primary key (id),
        constraint uk_shipping_policy_scope unique (scope),
        constraint ck_shipping_policy_base_fee_non_negative check (base_fee_amount >= 0),
        constraint ck_shipping_policy_free_shipping_threshold_pair check ((free_shipping_threshold_amount is null and free_shipping_threshold_currency is null) or (free_shipping_threshold_amount is not null and free_shipping_threshold_currency is not null)),
        constraint ck_shipping_policy_free_shipping_threshold_positive check (free_shipping_threshold_amount is null or free_shipping_threshold_amount > 0),
        constraint ck_shipping_policy_free_shipping_threshold_currency check (free_shipping_threshold_currency is null or free_shipping_threshold_currency = base_fee_currency),
        constraint ck_shipping_policy_customs_notice_scope check (customs_notice is null or scope = 'INTERNATIONAL')
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

    comment on column delivery.shipping_policy.customs_notice is
        '관세 · 부가세 고객 부담 안내 문구 (해외 정책만). 없으면 null';

    comment on column delivery.shipping_policy.free_shipping_threshold_amount is
        '무료배송 기준 주문 금액 (할인 후 상품 합계, 0 초과, 최소 화폐 단위). 이 금액 이상이면 기본 배송비를 받지 않는다. null 이면 무료배송 없음';

    comment on column delivery.shipping_policy.free_shipping_threshold_currency is
        '무료배송 기준 통화 — 기본 배송비 통화와 같다';

    comment on column delivery.shipping_policy.scope is
        '배송 범위 (DOMESTIC · INTERNATIONAL). 범위마다 정책이 하나다';

    comment on column delivery.shipping_policy.version is
        '낙관적 락 버전. 교체마다 1 증가';

    create table delivery.shipping_policy_history (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        admin_member_id bigint not null,
        after_base_fee_amount bigint not null,
        after_base_fee_currency varchar(3) not null check ((after_base_fee_currency in ('KRW','USD'))),
        after_free_shipping_threshold_amount bigint,
        after_free_shipping_threshold_currency varchar(3) check ((after_free_shipping_threshold_currency in ('KRW','USD'))),
        after_version bigint not null,
        before_base_fee_amount bigint,
        before_base_fee_currency varchar(3) check ((before_base_fee_currency in ('KRW','USD'))),
        before_free_shipping_threshold_amount bigint,
        before_free_shipping_threshold_currency varchar(3) check ((before_free_shipping_threshold_currency in ('KRW','USD'))),
        before_version bigint,
        scope varchar(20) not null check ((scope in ('DOMESTIC','INTERNATIONAL'))),
        shipping_policy_id bigint not null,
        primary key (id),
        constraint ck_shipping_policy_history_before_snapshot check ((before_version is null and before_base_fee_amount is null and before_base_fee_currency is null and before_free_shipping_threshold_amount is null and before_free_shipping_threshold_currency is null) or (before_version is not null and before_base_fee_amount is not null and before_base_fee_currency is not null)),
        constraint ck_shipping_policy_history_before_base_fee_non_negative check (before_base_fee_amount is null or before_base_fee_amount >= 0),
        constraint ck_shipping_policy_history_before_threshold_pair check ((before_free_shipping_threshold_amount is null and before_free_shipping_threshold_currency is null) or (before_free_shipping_threshold_amount is not null and before_free_shipping_threshold_currency is not null)),
        constraint ck_shipping_policy_history_before_threshold_positive check (before_free_shipping_threshold_amount is null or before_free_shipping_threshold_amount > 0),
        constraint ck_shipping_policy_history_before_threshold_currency check (before_free_shipping_threshold_currency is null or before_free_shipping_threshold_currency = before_base_fee_currency),
        constraint ck_shipping_policy_history_after_base_fee_non_negative check (after_base_fee_amount >= 0),
        constraint ck_shipping_policy_history_after_threshold_pair check ((after_free_shipping_threshold_amount is null and after_free_shipping_threshold_currency is null) or (after_free_shipping_threshold_amount is not null and after_free_shipping_threshold_currency is not null)),
        constraint ck_shipping_policy_history_after_threshold_positive check (after_free_shipping_threshold_amount is null or after_free_shipping_threshold_amount > 0),
        constraint ck_shipping_policy_history_after_threshold_currency check (after_free_shipping_threshold_currency is null or after_free_shipping_threshold_currency = after_base_fee_currency),
        constraint ck_shipping_policy_history_version_order check (before_version is null or before_version < after_version)
    );

    comment on table delivery.shipping_policy_history is
        '배송비 정책 교체 이력 (append-only)';

    comment on column delivery.shipping_policy_history.id is
        '배송비 정책 이력 아이디 (Snowflake)';

    comment on column delivery.shipping_policy_history.created_at is
        '생성 시각';

    comment on column delivery.shipping_policy_history.updated_at is
        '수정 시각';

    comment on column delivery.shipping_policy_history.admin_member_id is
        '교체한 어드민 회원 아이디 (외부 FK: auth-service member.id)';

    comment on column delivery.shipping_policy_history.after_base_fee_amount is
        '교체 후 기본 배송비 (0 이상, 최소 화폐 단위)';

    comment on column delivery.shipping_policy_history.after_base_fee_currency is
        '교체 후 정책 통화';

    comment on column delivery.shipping_policy_history.after_free_shipping_threshold_amount is
        '교체 후 무료배송 기준 주문 금액 (0 초과, 최소 화폐 단위). null 이면 무료배송 없음';

    comment on column delivery.shipping_policy_history.after_free_shipping_threshold_currency is
        '교체 후 무료배송 기준 통화';

    comment on column delivery.shipping_policy_history.after_version is
        '교체 후 정책 버전';

    comment on column delivery.shipping_policy_history.before_base_fee_amount is
        '교체 전 기본 배송비 (0 이상, 최소 화폐 단위). 처음 만든 교체면 null';

    comment on column delivery.shipping_policy_history.before_base_fee_currency is
        '교체 전 정책 통화. 처음 만든 교체면 null';

    comment on column delivery.shipping_policy_history.before_free_shipping_threshold_amount is
        '교체 전 무료배송 기준 주문 금액 (0 초과, 최소 화폐 단위). null 이면 무료배송 없음 또는 처음 만든 교체';

    comment on column delivery.shipping_policy_history.before_free_shipping_threshold_currency is
        '교체 전 무료배송 기준 통화';

    comment on column delivery.shipping_policy_history.before_version is
        '교체 전 정책 버전. 정책을 처음 만든 교체면 null';

    comment on column delivery.shipping_policy_history.scope is
        '배송 범위 (DOMESTIC · INTERNATIONAL). 지금은 DOMESTIC 만 남긴다';

    comment on column delivery.shipping_policy_history.shipping_policy_id is
        '배송비 정책 아이디 (FK: shipping_policy.id)';

    create table delivery.shipping_policy_history_area (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        name varchar(50) not null,
        postal_code_from varchar(5) not null,
        postal_code_to varchar(5) not null,
        shipping_policy_history_id bigint not null,
        snapshot_side varchar(10) not null check ((snapshot_side in ('BEFORE','AFTER'))),
        surcharge_amount bigint not null,
        surcharge_currency varchar(3) not null check ((surcharge_currency in ('KRW','USD'))),
        primary key (id),
        constraint ck_shipping_policy_history_area_postal_code_format check (char_length(postal_code_from) = 5 and ltrim(postal_code_from, '0123456789') = '' and char_length(postal_code_to) = 5 and ltrim(postal_code_to, '0123456789') = ''),
        constraint ck_shipping_policy_history_area_postal_code_range check (postal_code_from <= postal_code_to),
        constraint ck_shipping_policy_history_area_surcharge_non_negative check (surcharge_amount >= 0)
    );

    comment on table delivery.shipping_policy_history_area is
        '배송비 정책 이력의 도서산간 구간 스냅숏 (append-only)';

    comment on column delivery.shipping_policy_history_area.id is
        '이력 구간 아이디 (Snowflake)';

    comment on column delivery.shipping_policy_history_area.created_at is
        '생성 시각';

    comment on column delivery.shipping_policy_history_area.updated_at is
        '수정 시각';

    comment on column delivery.shipping_policy_history_area.name is
        '구간 이름 (예: 제주)';

    comment on column delivery.shipping_policy_history_area.postal_code_from is
        '시작 우편번호 (포함, 숫자 5자리)';

    comment on column delivery.shipping_policy_history_area.postal_code_to is
        '끝 우편번호 (포함, 숫자 5자리)';

    comment on column delivery.shipping_policy_history_area.shipping_policy_history_id is
        '배송비 정책 이력 아이디 (FK: shipping_policy_history.id)';

    comment on column delivery.shipping_policy_history_area.snapshot_side is
        '어느 쪽 구간인가 (BEFORE 교체 전 · AFTER 교체 후)';

    comment on column delivery.shipping_policy_history_area.surcharge_amount is
        '추가 배송비 (0 이상, 최소 화폐 단위)';

    comment on column delivery.shipping_policy_history_area.surcharge_currency is
        '추가 배송비 통화';

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
        constraint ck_shipping_remote_area_postal_code_format check (char_length(postal_code_from) = 5 and ltrim(postal_code_from, '0123456789') = '' and char_length(postal_code_to) = 5 and ltrim(postal_code_to, '0123456789') = ''),
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

    create table delivery.shipping_zone (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        display_order integer not null,
        name varchar(50) not null,
        shipping_policy_id bigint not null,
        primary key (id),
        constraint uk_shipping_zone_shipping_policy_id_name unique (shipping_policy_id, name),
        constraint ck_shipping_zone_display_order_non_negative check (display_order >= 0)
    );

    comment on table delivery.shipping_zone is
        '해외 배송 국가 그룹 (해외 배송비 정책별)';

    comment on column delivery.shipping_zone.id is
        '국가 그룹 아이디 (Snowflake). 정책 교체마다 새로 발급';

    comment on column delivery.shipping_zone.created_at is
        '생성 시각';

    comment on column delivery.shipping_zone.updated_at is
        '수정 시각';

    comment on column delivery.shipping_zone.display_order is
        '표시 순서 (0 부터, 오름차순)';

    comment on column delivery.shipping_zone.name is
        '국가 그룹 이름 (예: 아시아). 정책 안에서 유일';

    comment on column delivery.shipping_zone.shipping_policy_id is
        '배송비 정책 아이디 (FK: shipping_policy.id)';

    create table delivery.shipping_zone_country (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        country_code varchar(2) not null,
        shipping_policy_id bigint not null,
        shipping_zone_id bigint not null,
        primary key (id),
        constraint uk_shipping_zone_country_shipping_policy_id_country_code unique (shipping_policy_id, country_code),
        constraint ck_shipping_zone_country_country_code_format check (char_length(country_code) = 2 and upper(country_code) = country_code),
        constraint ck_shipping_zone_country_country_code_not_domestic check (country_code <> 'KR')
    );

    comment on table delivery.shipping_zone_country is
        '해외 배송 국가 그룹의 국가 (해외 배송비 정책별, 정책 안에서 국가는 한 그룹에만)';

    comment on column delivery.shipping_zone_country.id is
        '국가 그룹 국가 아이디 (Snowflake). 정책 교체마다 새로 발급';

    comment on column delivery.shipping_zone_country.created_at is
        '생성 시각';

    comment on column delivery.shipping_zone_country.updated_at is
        '수정 시각';

    comment on column delivery.shipping_zone_country.country_code is
        '국가 코드 (ISO 3166-1 alpha-2 대문자, KR 제외)';

    comment on column delivery.shipping_zone_country.shipping_policy_id is
        '배송비 정책 아이디 (FK: shipping_policy.id)';

    comment on column delivery.shipping_zone_country.shipping_zone_id is
        '국가 그룹 아이디 (FK: shipping_zone.id)';

    create table delivery.shipping_zone_rate (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        fee_amount bigint not null,
        fee_currency varchar(3) not null check ((fee_currency in ('KRW','USD'))),
        max_weight_grams integer not null,
        shipping_zone_id bigint not null,
        primary key (id),
        constraint uk_shipping_zone_rate_shipping_zone_id_max_weight_grams unique (shipping_zone_id, max_weight_grams),
        constraint ck_shipping_zone_rate_max_weight_grams_positive check (max_weight_grams > 0),
        constraint ck_shipping_zone_rate_fee_non_negative check (fee_amount >= 0)
    );

    comment on table delivery.shipping_zone_rate is
        '해외 배송 국가 그룹의 무게 구간 요금 (상한 포함)';

    comment on column delivery.shipping_zone_rate.id is
        '무게 구간 아이디 (Snowflake). 정책 교체마다 새로 발급';

    comment on column delivery.shipping_zone_rate.created_at is
        '생성 시각';

    comment on column delivery.shipping_zone_rate.updated_at is
        '수정 시각';

    comment on column delivery.shipping_zone_rate.fee_amount is
        '무게 요금 (0 이상, 최소 화폐 단위)';

    comment on column delivery.shipping_zone_rate.fee_currency is
        '무게 요금 통화 — 정책 통화와 같아야 한다';

    comment on column delivery.shipping_zone_rate.max_weight_grams is
        '상한 무게 (그램, 1 이상, 포함). 바로 아래 구간 상한 + 1g 부터 이 구간이다';

    comment on column delivery.shipping_zone_rate.shipping_zone_id is
        '국가 그룹 아이디 (FK: shipping_zone.id)';

    create index idx_shipment_status_id 
       on delivery.shipment (status, id);

    create index idx_shipment_order_synced_id 
       on delivery.shipment (order_synced, id);

    create index idx_shipment_status_history_shipment_id_id 
       on delivery.shipment_status_history (shipment_id, id);

    create index idx_shipping_policy_history_scope_id 
       on delivery.shipping_policy_history (scope, id);

    create index idx_shipping_policy_history_area_shipping_policy_history_id 
       on delivery.shipping_policy_history_area (shipping_policy_history_id);

    create index idx_shipping_remote_area_shipping_policy_id 
       on delivery.shipping_remote_area (shipping_policy_id);
