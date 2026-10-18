    create schema product;

    create table product.category (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        display_order integer not null,
        name varchar(50) not null,
        parent_id bigint,
        visible boolean not null,
        primary key (id)
    );

    comment on table product.category is
        '상품 카테고리 (계층)';

    comment on column product.category.id is
        '카테고리 아이디 (Snowflake)';

    comment on column product.category.created_at is
        '생성 시각';

    comment on column product.category.updated_at is
        '수정 시각';

    comment on column product.category.display_order is
        '같은 상위 안의 노출 순서 (오름차순)';

    comment on column product.category.name is
        '카테고리명';

    comment on column product.category.parent_id is
        '상위 카테고리 아이디 (FK: category.id). 최상위면 null';

    comment on column product.category.visible is
        '스토어프론트 노출 여부';

    create table product.product (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        category_id bigint not null,
        custom_order boolean not null,
        custom_order_lead_time_days integer,
        custom_order_notice varchar(500),
        description_html text,
        list_price_amount bigint,
        list_price_currency varchar(3) check ((list_price_currency in ('KRW','USD'))),
        max_quantity_per_order integer,
        name varchar(100) not null,
        sale_price_amount bigint not null,
        sale_price_currency varchar(3) not null check ((sale_price_currency in ('KRW','USD'))),
        sale_status varchar(20) not null check ((sale_status in ('ON_SALE','STOP_SELLING'))),
        summary varchar(200),
        visible boolean not null,
        primary key (id),
        constraint ck_product_sale_price_non_negative check (sale_price_amount >= 0),
        constraint ck_product_list_price_pair check ((list_price_amount is null and list_price_currency is null) or (list_price_amount is not null and list_price_currency is not null)),
        constraint ck_product_list_price_currency check (list_price_currency is null or list_price_currency = sale_price_currency),
        constraint ck_product_list_price_non_negative check (list_price_amount is null or list_price_amount >= 0)
    );

    comment on table product.product is
        '상품';

    comment on column product.product.id is
        '상품 아이디 (Snowflake)';

    comment on column product.product.created_at is
        '생성 시각';

    comment on column product.product.updated_at is
        '수정 시각';

    comment on column product.product.category_id is
        '대표 카테고리 아이디 (FK: category.id). 상세 경로 표시용, 진열 카테고리(product_category)에도 반드시 포함';

    comment on column product.product.custom_order is
        '맞춤 제작 상품 여부';

    comment on column product.product.custom_order_lead_time_days is
        '맞춤 제작 소요일';

    comment on column product.product.custom_order_notice is
        '맞춤 제작 안내 문구';

    comment on column product.product.description_html is
        '상세 설명 HTML. 표시 전 sanitize 대상';

    comment on column product.product.list_price_amount is
        '정가 (할인 전, 최소 화폐 단위). null 이면 할인 표시 없음';

    comment on column product.product.list_price_currency is
        '정가 통화';

    comment on column product.product.max_quantity_per_order is
        '1회 주문 최대 수량. null 이면 제한 없음';

    comment on column product.product.name is
        '상품명';

    comment on column product.product.sale_price_amount is
        '기본 판매가 (최소 화폐 단위). SKU 추가금의 기준이자 목록 최저가';

    comment on column product.product.sale_price_currency is
        '기본 판매가 통화';

    comment on column product.product.sale_status is
        '판매 상태 (ON_SALE / STOP_SELLING). 품절은 저장하지 않는다 — 재고에서 유도';

    comment on column product.product.summary is
        '한 줄 요약';

    comment on column product.product.visible is
        '스토어프론트 노출 여부. false 면 상세 404 · 목록 제외';

    create table product.product_category (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        category_id bigint not null,
        display_order integer not null,
        product_id bigint not null,
        primary key (id),
        constraint uk_product_category_product_id_category_id unique (product_id, category_id)
    );

    comment on table product.product_category is
        '상품 진열 카테고리 (다중)';

    comment on column product.product_category.id is
        '상품 진열 카테고리 아이디 (Snowflake)';

    comment on column product.product_category.created_at is
        '생성 시각';

    comment on column product.product_category.updated_at is
        '수정 시각';

    comment on column product.product_category.category_id is
        '진열 카테고리 아이디 (FK: category.id)';

    comment on column product.product_category.display_order is
        '카테고리 안의 진열 순서 (오름차순). 어드민 수동 정렬용';

    comment on column product.product_category.product_id is
        '상품 아이디 (FK: product.id)';

    create table product.product_custom_order_field (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        display_order integer not null,
        field_key varchar(30) not null,
        help_text varchar(200),
        input_type varchar(20) not null check ((input_type in ('NUMBER','TEXT'))),
        label varchar(50) not null,
        max_value numeric(10,2),
        min_value numeric(10,2),
        product_id bigint not null,
        required boolean not null,
        step_value numeric(10,2),
        unit varchar(10),
        primary key (id),
        constraint uk_product_custom_order_field_product_id_field_key unique (product_id, field_key)
    );

    comment on table product.product_custom_order_field is
        '맞춤 제작 입력 항목 (치수 등)';

    comment on column product.product_custom_order_field.id is
        '입력 항목 아이디 (Snowflake)';

    comment on column product.product_custom_order_field.created_at is
        '생성 시각';

    comment on column product.product_custom_order_field.updated_at is
        '수정 시각';

    comment on column product.product_custom_order_field.display_order is
        '노출 순서';

    comment on column product.product_custom_order_field.field_key is
        '입력 항목 키 (chest, memo). 주문 항목 스냅숏과 검증 오류 field 이름에 쓴다';

    comment on column product.product_custom_order_field.help_text is
        '도움말';

    comment on column product.product_custom_order_field.input_type is
        '입력 유형 (NUMBER / TEXT)';

    comment on column product.product_custom_order_field.label is
        '표시 이름';

    comment on column product.product_custom_order_field.max_value is
        'NUMBER 최대값 / TEXT 최대 글자 수';

    comment on column product.product_custom_order_field.min_value is
        'NUMBER 최소값';

    comment on column product.product_custom_order_field.product_id is
        '상품 아이디 (FK: product.id)';

    comment on column product.product_custom_order_field.required is
        '필수 입력 여부';

    comment on column product.product_custom_order_field.step_value is
        'NUMBER 증분 (0.5 등)';

    comment on column product.product_custom_order_field.unit is
        '단위 (cm)';

    create table product.product_image (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        alt_text varchar(200),
        display_order integer not null,
        height integer,
        image_type varchar(20) not null check ((image_type in ('THUMBNAIL','GALLERY'))),
        product_id bigint not null,
        url varchar(500) not null,
        width integer,
        primary key (id),
        constraint uk_product_image_product_id_image_type_display_order unique (product_id, image_type, display_order),
        constraint ck_product_image_thumbnail_display_order check (image_type <> 'THUMBNAIL' or display_order = 0)
    );

    comment on table product.product_image is
        '상품 이미지';

    comment on column product.product_image.id is
        '상품 이미지 아이디 (Snowflake)';

    comment on column product.product_image.created_at is
        '생성 시각';

    comment on column product.product_image.updated_at is
        '수정 시각';

    comment on column product.product_image.alt_text is
        '대체 텍스트';

    comment on column product.product_image.display_order is
        '같은 유형 안의 순서. THUMBNAIL 은 항상 0';

    comment on column product.product_image.height is
        '원본 세로 px. 모르면 null';

    comment on column product.product_image.image_type is
        '이미지 유형 — THUMBNAIL(목록 대표, 상품당 1개) / GALLERY(상세 갤러리)';

    comment on column product.product_image.product_id is
        '상품 아이디 (FK: product.id)';

    comment on column product.product_image.url is
        '이미지 URL';

    comment on column product.product_image.width is
        '원본 가로 px. 모르면 null';

    create table product.product_option_group (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        display_order integer not null,
        name varchar(50) not null,
        product_id bigint not null,
        primary key (id),
        constraint uk_product_option_group_product_id_name unique (product_id, name)
    );

    comment on table product.product_option_group is
        '상품 옵션 그룹 (색상·사이즈)';

    comment on column product.product_option_group.id is
        '옵션 그룹 아이디 (Snowflake)';

    comment on column product.product_option_group.created_at is
        '생성 시각';

    comment on column product.product_option_group.updated_at is
        '수정 시각';

    comment on column product.product_option_group.display_order is
        '노출 순서 (오름차순)';

    comment on column product.product_option_group.name is
        '옵션 그룹명';

    comment on column product.product_option_group.product_id is
        '상품 아이디 (FK: product.id)';

    create table product.product_option_value (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        display_order integer not null,
        name varchar(50) not null,
        option_group_id bigint not null,
        product_id bigint not null,
        primary key (id),
        constraint uk_product_option_value_option_group_id_name unique (option_group_id, name)
    );

    comment on table product.product_option_value is
        '상품 옵션 값';

    comment on column product.product_option_value.id is
        '옵션 값 아이디 (Snowflake)';

    comment on column product.product_option_value.created_at is
        '생성 시각';

    comment on column product.product_option_value.updated_at is
        '수정 시각';

    comment on column product.product_option_value.display_order is
        '그룹 안의 노출 순서';

    comment on column product.product_option_value.name is
        '옵션 값명';

    comment on column product.product_option_value.option_group_id is
        '옵션 그룹 아이디 (FK: product_option_group.id)';

    comment on column product.product_option_value.product_id is
        '상품 아이디 (FK: product.id) — 상세를 상품 단위로 한 번에 읽기 위한 비정규화';

    create table product.sku (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        additional_price_amount bigint not null,
        additional_price_currency varchar(3) not null check ((additional_price_currency in ('KRW','USD'))),
        option_value_key varchar(255) not null,
        product_id bigint not null,
        sale_status varchar(20) not null check ((sale_status in ('ON_SALE','STOP_SELLING'))),
        primary key (id),
        constraint uk_sku_product_id_option_value_key unique (product_id, option_value_key),
        constraint ck_sku_additional_price_non_negative check (additional_price_amount >= 0)
    );

    comment on table product.sku is
        'SKU (상품 × 옵션 값 조합)';

    comment on column product.sku.id is
        'SKU 아이디 (Snowflake)';

    comment on column product.sku.created_at is
        '생성 시각';

    comment on column product.sku.updated_at is
        '수정 시각';

    comment on column product.sku.additional_price_amount is
        '옵션 추가금 (0 이상, 최소 화폐 단위)';

    comment on column product.sku.additional_price_currency is
        '추가금 통화 — 상품 기본 판매가 통화와 같아야 한다';

    comment on column product.sku.option_value_key is
        '옵션 값 아이디 조합 키 — 값 아이디 오름차순을 쉼표(,)로 이은 문자열. 옵션 없는 상품은 빈 문자열';

    comment on column product.sku.product_id is
        '상품 아이디 (FK: product.id)';

    comment on column product.sku.sale_status is
        '판매 상태 (ON_SALE / STOP_SELLING). 품절은 저장하지 않는다 — 재고에서 유도';

    create table product.stock (
        sku_id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        available_quantity bigint not null,
        stock_managed boolean not null,
        primary key (sku_id),
        constraint ck_stock_available_quantity_non_negative check (available_quantity >= 0)
    );

    comment on table product.stock is
        'SKU 재고 (SKU 와 1:1). 행이 없는 SKU 는 품절';

    comment on column product.stock.sku_id is
        'SKU 아이디 (PK · FK: sku.id)';

    comment on column product.stock.created_at is
        '생성 시각';

    comment on column product.stock.updated_at is
        '수정 시각';

    comment on column product.stock.available_quantity is
        '판매 가능 수량 (선점분 제외, 0 이상)';

    comment on column product.stock.stock_managed is
        '재고 관리 여부. false 면 수량과 무관하게 품절이 아니다 (무제한)';

    create table product.stock_adjustment_history (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        admin_member_id bigint not null,
        after_quantity bigint not null,
        after_stock_managed boolean not null,
        before_quantity bigint not null,
        before_stock_managed boolean not null,
        delta bigint not null,
        memo varchar(200),
        reason varchar(30) not null check ((reason in ('RESTOCK','RECOUNT','DAMAGE','MANAGEMENT_CHANGE','OTHER'))),
        sku_id bigint not null,
        primary key (id)
    );

    comment on table product.stock_adjustment_history is
        '어드민 재고 조정 이력 (append-only)';

    comment on column product.stock_adjustment_history.id is
        '재고 조정 이력 아이디 (Snowflake)';

    comment on column product.stock_adjustment_history.created_at is
        '생성 시각';

    comment on column product.stock_adjustment_history.updated_at is
        '수정 시각';

    comment on column product.stock_adjustment_history.admin_member_id is
        '조정한 어드민 회원 아이디 (외부 FK: auth-service 회원 id)';

    comment on column product.stock_adjustment_history.after_quantity is
        '조정 후 판매 가능 수량';

    comment on column product.stock_adjustment_history.after_stock_managed is
        '조정 후 재고 관리 여부';

    comment on column product.stock_adjustment_history.before_quantity is
        '조정 전 판매 가능 수량. 재고 행이 없던 SKU 는 0 (품절)';

    comment on column product.stock_adjustment_history.before_stock_managed is
        '조정 전 재고 관리 여부. 재고 행이 없던 SKU 는 true (품절)';

    comment on column product.stock_adjustment_history.delta is
        '수량 변화 (after_quantity - before_quantity)';

    comment on column product.stock_adjustment_history.memo is
        '조정 메모';

    comment on column product.stock_adjustment_history.reason is
        '조정 사유 (RESTOCK / RECOUNT / DAMAGE / MANAGEMENT_CHANGE / OTHER)';

    comment on column product.stock_adjustment_history.sku_id is
        'SKU 아이디 (FK: sku.id)';

    create table product.stock_reservation (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        active_order_id bigint,
        confirmed_at timestamp(6) with time zone,
        expires_at timestamp(6) with time zone not null,
        order_id bigint not null,
        release_reason varchar(20) check ((release_reason in ('EXPIRED','RELEASED_BY_CALLER'))),
        released_at timestamp(6) with time zone,
        status varchar(20) not null check ((status in ('RESERVED','CONFIRMED','RELEASED'))),
        version bigint not null,
        primary key (id),
        constraint uk_stock_reservation_active_order_id unique (active_order_id)
    );

    comment on table product.stock_reservation is
        '재고 선점 (주문 하나에 한 행)';

    comment on column product.stock_reservation.id is
        '재고 선점 아이디 (Snowflake)';

    comment on column product.stock_reservation.created_at is
        '생성 시각';

    comment on column product.stock_reservation.updated_at is
        '수정 시각';

    comment on column product.stock_reservation.active_order_id is
        '활성 선점의 주문 아이디 — RESERVED · CONFIRMED 동안만 order_id 와 같고 RELEASED 면 null (주문당 활성 선점 1건)';

    comment on column product.stock_reservation.confirmed_at is
        '확정 시각 (CONFIRMED 일 때만)';

    comment on column product.stock_reservation.expires_at is
        '만료 시각 — RESERVED 로 이 시각이 지나면 스케줄러가 해제한다';

    comment on column product.stock_reservation.order_id is
        '주문 아이디 (외부 FK: order-service 주문 id)';

    comment on column product.stock_reservation.release_reason is
        '해제 사유 (EXPIRED / RELEASED_BY_CALLER). RELEASED 일 때만';

    comment on column product.stock_reservation.released_at is
        '해제 시각 (RELEASED 일 때만)';

    comment on column product.stock_reservation.status is
        '선점 상태 (RESERVED / CONFIRMED / RELEASED)';

    comment on column product.stock_reservation.version is
        '낙관적 락 버전';

    create table product.stock_reservation_item (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        deducted boolean not null,
        quantity integer not null,
        reservation_id bigint not null,
        sku_id bigint not null,
        primary key (id),
        constraint uk_stock_reservation_item_reservation_id_sku_id unique (reservation_id, sku_id),
        constraint ck_stock_reservation_item_quantity_positive check (quantity > 0)
    );

    comment on table product.stock_reservation_item is
        '재고 선점 항목 (선점 × SKU)';

    comment on column product.stock_reservation_item.id is
        '재고 선점 항목 아이디 (Snowflake)';

    comment on column product.stock_reservation_item.created_at is
        '생성 시각';

    comment on column product.stock_reservation_item.updated_at is
        '수정 시각';

    comment on column product.stock_reservation_item.deducted is
        '선점 때 재고를 차감했는가. 무제한 SKU 는 false 이고 해제 · 만료 때 되돌리지 않는다';

    comment on column product.stock_reservation_item.quantity is
        '선점 수량 (1 이상)';

    comment on column product.stock_reservation_item.reservation_id is
        '재고 선점 아이디 (FK: stock_reservation.id)';

    comment on column product.stock_reservation_item.sku_id is
        'SKU 아이디 (FK: sku.id)';

    create index idx_product_visible_sale_price_amount_id
       on product.product (visible, sale_price_amount, id);

    create index idx_product_category_category_id_product_id
       on product.product_category (category_id, product_id);

    create index idx_product_option_value_product_id
       on product.product_option_value (product_id);

    create index idx_stock_adjustment_history_sku_id_id
       on product.stock_adjustment_history (sku_id, id);

    create index idx_stock_reservation_status_expires_at_id
       on product.stock_reservation (status, expires_at, id);

    create index idx_stock_reservation_order_id
       on product.stock_reservation (order_id);
