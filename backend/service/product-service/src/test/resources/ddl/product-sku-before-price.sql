-- 동결 출처 커밋: 43429ff8ae1427cf4891ab8b807dea9282f0831a, backend/service/product-service/src/test/resources/ddl/product-schema.sql
-- 전체 원본 스냅숏 SHA256: 21360a4bcc2427ac3b1d7d84527a5da56b646c0c60801c1ec7f2523e05b3a485
-- 추출 SQL 원문 SHA256: 642b93701920b923d9ea343b27c34ad3b82c92aa58be397218c0fe433a27c196
-- 정규화: CREATE INDEX idx_product_visible_sale_price_amount_id 줄끝의 ASCII 공백 한 개만 제거했다.
-- 정규화 SQL 본문 SHA256: 380a1315d7d4ebd978bdf8518ce5237ab92f107aced5b286dd26f649f983bdf4
create table product.product (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        category_id bigint not null,
        created_by_member_id bigint,
        custom_order boolean not null,
        custom_order_lead_time_days integer,
        custom_order_notice varchar(500),
        deleted_at timestamp(6) with time zone,
        description_html text,
        description_html_en text,
        expected_ship_date date,
        list_price_amount bigint,
        list_price_currency varchar(3) check ((list_price_currency in ('KRW','USD'))),
        max_quantity_per_order integer,
        name varchar(100) not null,
        name_en varchar(100),
        preorder boolean not null,
        sale_price_amount bigint not null,
        sale_price_currency varchar(3) not null check ((sale_price_currency in ('KRW','USD'))),
        sale_status varchar(20) not null check ((sale_status in ('ON_SALE','STOP_SELLING'))),
        summary varchar(200),
        summary_en varchar(200),
        version bigint not null,
        visible boolean not null,
        primary key (id),
        constraint ck_product_sale_price_non_negative check (sale_price_amount >= 0),
        constraint ck_product_list_price_pair check ((list_price_amount is null and list_price_currency is null) or (list_price_amount is not null and list_price_currency is not null)),
        constraint ck_product_list_price_currency check (list_price_currency is null or list_price_currency = sale_price_currency),
        constraint ck_product_list_price_non_negative check (list_price_amount is null or list_price_amount >= 0),
        constraint ck_product_expected_ship_date_preorder check (preorder = true or expected_ship_date is null),
        constraint ck_product_deleted_hidden check (deleted_at is null or (visible = false and sale_status = 'STOP_SELLING'))
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

comment on column product.product.created_by_member_id is
        '등록한 어드민 (외부 FK: auth-service member.id). 이관 · 시드면 null';

comment on column product.product.custom_order is
        '맞춤 제작 상품 여부';

comment on column product.product.custom_order_lead_time_days is
        '맞춤 제작 소요일';

comment on column product.product.custom_order_notice is
        '맞춤 제작 안내 문구';

comment on column product.product.deleted_at is
        '삭제 시각 (soft delete). null 이면 삭제되지 않은 상품. 삭제되면 숨김 · 판매 종료로 고정된다(ck_product_deleted_hidden)';

comment on column product.product.description_html is
        '상세 설명 HTML. 표시 전 sanitize 대상';

comment on column product.product.description_html_en is
        '영문 상세 설명 HTML. 없으면 기본 설명 사용, 표시 전 sanitize 대상';

comment on column product.product.expected_ship_date is
        '발송 예정일 (순차 발송 시작일). 프리오더 상품만 갖는다. 프리오더인데 null 이면 발송일 추후 안내';

comment on column product.product.list_price_amount is
        '정가 (할인 전, 최소 화폐 단위). null 이면 할인 표시 없음';

comment on column product.product.list_price_currency is
        '정가 통화';

comment on column product.product.max_quantity_per_order is
        '1회 주문 최대 수량. null 이면 제한 없음';

comment on column product.product.name is
        '상품명';

comment on column product.product.name_en is
        '영문 상품명. 없으면 기본 상품명 사용';

comment on column product.product.preorder is
        '프리오더(예약 판매) 상품 여부. 판매 가능 판정 · 재고는 일반 상품과 같다';

comment on column product.product.sale_price_amount is
        '기본 판매가 (최소 화폐 단위). SKU 추가금의 기준이자 목록 최저가';

comment on column product.product.sale_price_currency is
        '기본 판매가 통화';

comment on column product.product.sale_status is
        '판매 상태 (ON_SALE / STOP_SELLING). 품절은 저장하지 않는다 — 재고에서 유도';

comment on column product.product.summary is
        '한 줄 요약';

comment on column product.product.summary_en is
        '영문 한 줄 요약. 없으면 기본 요약 사용';

comment on column product.product.version is
        '낙관적 락 버전';

comment on column product.product.visible is
        '스토어프론트 노출 여부. false 면 상세 404 · 목록 제외';

create table product.sku (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        additional_price_amount bigint not null,
        additional_price_currency varchar(3) not null check ((additional_price_currency in ('KRW','USD'))),
        hs_code varchar(10),
        option_value_key varchar(255) not null,
        product_id bigint not null,
        sale_status varchar(20) not null check ((sale_status in ('ON_SALE','STOP_SELLING'))),
        weight_grams integer,
        primary key (id),
        constraint uk_sku_product_id_option_value_key unique (product_id, option_value_key),
        constraint ck_sku_additional_price_non_negative check (additional_price_amount >= 0),
        constraint ck_sku_weight_grams_positive check (weight_grams is null or weight_grams > 0),
        constraint ck_sku_hs_code_format check (hs_code is null or (char_length(hs_code) between 6 and 10 and ltrim(hs_code, '0123456789') = ''))
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

comment on column product.sku.hs_code is
        '통관 신고용 HS 코드 (숫자 6~10자리) — 해외 판매 상품만 필수, null 이면 미입력';

comment on column product.sku.option_value_key is
        '옵션 값 아이디 조합 키 — 값 아이디 오름차순을 쉼표(,)로 이은 문자열. 옵션 없는 상품은 빈 문자열';

comment on column product.sku.product_id is
        '상품 아이디 (FK: product.id)';

comment on column product.sku.sale_status is
        '판매 상태 (ON_SALE / STOP_SELLING). 품절은 저장하지 않는다 — 재고에서 유도';

comment on column product.sku.weight_grams is
        '배송 무게 (그램, 1 이상) — 해외 배송비 견적에 쓴다. 해외 판매 상품만 필수, null 이면 미입력';

create index idx_product_visible_sale_price_amount_id
       on product.product (visible, sale_price_amount, id);

create index idx_product_created_by_member_id_deleted_at_created_at
       on product.product (created_by_member_id, deleted_at, created_at);
