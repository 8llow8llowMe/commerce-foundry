-- ProductSchemaSnapshotTest가 Hibernate PostgreSQL 17 방언으로 생성한 가격 테이블 증분이다.
-- 기존 product/sku 테이블은 변경하지 않으며 구 스키마 테스트용으로 트랜잭션 래퍼를 포함하지 않는다.
create table product.product_price (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        list_price_amount bigint,
        list_price_currency varchar(3) check ((list_price_currency in ('KRW','USD'))),
        product_id bigint not null,
        sale_price_amount bigint not null,
        sale_price_currency varchar(3) not null check ((sale_price_currency in ('KRW','USD'))),
        primary key (id),
        constraint uk_product_price_product_id_sale_price_currency unique (product_id, sale_price_currency),
        constraint ck_product_price_ids_positive check (id > 0 and product_id > 0),
        constraint ck_product_price_sale_non_negative check (sale_price_amount >= 0),
        constraint ck_product_price_list_pair check ((list_price_amount is null and list_price_currency is null) or (list_price_amount is not null and list_price_currency is not null)),
        constraint ck_product_price_list_currency check (list_price_currency is null or list_price_currency = sale_price_currency),
        constraint ck_product_price_list_above_sale check (list_price_amount is null or list_price_amount > sale_price_amount)
    );

comment on table product.product_price is
        '상품 통화별 가격 (기존 읽기·쓰기 미연결)';

comment on column product.product_price.id is
        '상품 가격 아이디 (Snowflake)';

comment on column product.product_price.created_at is
        '생성 시각';

comment on column product.product_price.updated_at is
        '수정 시각';

comment on column product.product_price.list_price_amount is
        '정가 (최소 화폐 단위). null이면 정가 없음';

comment on column product.product_price.list_price_currency is
        '정가 통화. 금액과 함께 null 또는 판매가 통화와 동일';

comment on column product.product_price.product_id is
        '상품 아이디 (FK: product.id)';

comment on column product.product_price.sale_price_amount is
        '기본 판매가 (최소 화폐 단위, 0 이상)';

comment on column product.product_price.sale_price_currency is
        '기본 판매가 통화';

create table product.sku_price (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        additional_price_amount bigint not null,
        additional_price_currency varchar(3) not null check ((additional_price_currency in ('KRW','USD'))),
        sku_id bigint not null,
        primary key (id),
        constraint uk_sku_price_sku_id_additional_price_currency unique (sku_id, additional_price_currency),
        constraint ck_sku_price_ids_positive check (id > 0 and sku_id > 0),
        constraint ck_sku_price_additional_non_negative check (additional_price_amount >= 0)
    );

comment on table product.sku_price is
        'SKU 통화별 추가금 (기존 읽기·쓰기 미연결)';

comment on column product.sku_price.id is
        'SKU 가격 아이디 (Snowflake)';

comment on column product.sku_price.created_at is
        '생성 시각';

comment on column product.sku_price.updated_at is
        '수정 시각';

comment on column product.sku_price.additional_price_amount is
        '옵션 추가금 (최소 화폐 단위, 0 이상)';

comment on column product.sku_price.additional_price_currency is
        '옵션 추가금 통화';

comment on column product.sku_price.sku_id is
        'SKU 아이디 (FK: sku.id)';
