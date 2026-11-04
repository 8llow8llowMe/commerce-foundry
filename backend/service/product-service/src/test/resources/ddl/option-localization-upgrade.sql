alter table product.product_option_group add column name_en varchar(30);

comment on column product.product_option_group.name_en is
        '옵션 그룹 영문명. 없으면 한국어 이름으로 표시';

alter table product.product_option_value add column name_en varchar(50);

comment on column product.product_option_value.name_en is
        '옵션 값 영문명. 없으면 한국어 이름으로 표시';
