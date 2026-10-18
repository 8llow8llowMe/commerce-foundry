
    create schema orders;

    create table orders.cart_item (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        custom_option_hash varchar(64) not null,
        member_id bigint not null,
        quantity integer not null,
        sku_id bigint not null,
        version bigint not null,
        primary key (id),
        constraint uk_cart_item_member_id_sku_id_custom_option_hash unique (member_id, sku_id, custom_option_hash),
        constraint ck_cart_item_quantity_positive check (quantity >= 1)
    );

    comment on table orders.cart_item is
        '장바구니 줄 (회원 × SKU × 맞춤 제작 입력)';

    comment on column orders.cart_item.id is
        '장바구니 줄 아이디 (Snowflake)';

    comment on column orders.cart_item.created_at is
        '생성 시각';

    comment on column orders.cart_item.updated_at is
        '수정 시각';

    comment on column orders.cart_item.custom_option_hash is
        '맞춤 제작 입력의 정규화 SHA-256 hex. 입력이 없으면 빈 문자열 — 유니크 키에서 null 은 중복을 막지 못한다';

    comment on column orders.cart_item.member_id is
        '회원 아이디 (외부 FK: auth-service member.id)';

    comment on column orders.cart_item.quantity is
        '수량 (1 이상, 99 · 상품 1회 주문 최대 수량 이하)';

    comment on column orders.cart_item.sku_id is
        'SKU 아이디 (외부 FK: product-service sku.id)';

    comment on column orders.cart_item.version is
        '낙관적 락 버전';

    create table orders.cart_item_custom_option (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        cart_item_id bigint not null,
        display_order integer not null,
        field_key varchar(50) not null,
        field_value varchar(500) not null,
        primary key (id),
        constraint uk_cart_item_custom_option_cart_item_id_field_key unique (cart_item_id, field_key)
    );

    comment on table orders.cart_item_custom_option is
        '장바구니 줄의 맞춤 제작 입력';

    comment on column orders.cart_item_custom_option.id is
        '맞춤 제작 입력 아이디 (Snowflake)';

    comment on column orders.cart_item_custom_option.created_at is
        '생성 시각';

    comment on column orders.cart_item_custom_option.updated_at is
        '수정 시각';

    comment on column orders.cart_item_custom_option.cart_item_id is
        '장바구니 줄 아이디 (FK: cart_item.id)';

    comment on column orders.cart_item_custom_option.display_order is
        '표시 순서 (담을 때 받은 순서)';

    comment on column orders.cart_item_custom_option.field_key is
        '상품 입력 항목 키 (chest, memo)';

    comment on column orders.cart_item_custom_option.field_value is
        '입력값 (앞뒤 공백을 자른 값)';
