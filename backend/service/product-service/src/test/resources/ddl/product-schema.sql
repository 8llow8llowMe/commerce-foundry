
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

    create table product.display_item (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        alt_text varchar(200),
        caption varchar(200),
        display_order integer not null,
        item_type varchar(20) not null check ((item_type in ('MEDIA','PRODUCT'))),
        media_type varchar(20) check ((media_type in ('IMAGE','VIDEO'))),
        mobile_poster_url varchar(500),
        mobile_url varchar(500),
        pc_poster_url varchar(500),
        pc_url varchar(500),
        product_id bigint,
        section_id bigint not null,
        primary key (id),
        constraint uk_display_item_section_id_display_order unique (section_id, display_order),
        constraint ck_display_item_media_fields check (item_type <> 'MEDIA' or (media_type is not null and pc_url is not null and product_id is null)),
        constraint ck_display_item_product_fields check (item_type <> 'PRODUCT' or (product_id is not null and media_type is null and pc_url is null and mobile_url is null and pc_poster_url is null and mobile_poster_url is null and alt_text is null)),
        constraint ck_display_item_video_poster check (media_type is null or (media_type = 'VIDEO' and pc_poster_url is not null) or (media_type = 'IMAGE' and pc_poster_url is null and mobile_poster_url is null)),
        constraint ck_display_item_display_order_non_negative check (display_order >= 0)
    );

    comment on table product.display_item is
        '메인 전시 아이템 (미디어 또는 상품)';

    comment on column product.display_item.id is
        '전시 아이템 아이디 (Snowflake). 섹션 수정마다 새로 발급한다';

    comment on column product.display_item.created_at is
        '생성 시각';

    comment on column product.display_item.updated_at is
        '수정 시각';

    comment on column product.display_item.alt_text is
        '미디어 대체 텍스트';

    comment on column product.display_item.caption is
        '아이템 설명 문구';

    comment on column product.display_item.display_order is
        '섹션 안의 순서 (0 부터, 오름차순)';

    comment on column product.display_item.item_type is
        '아이템 유형 (MEDIA · PRODUCT)';

    comment on column product.display_item.media_type is
        '미디어 종류 (IMAGE · VIDEO). 미디어 아이템만';

    comment on column product.display_item.mobile_poster_url is
        '영상 포스터 (모바일). 없으면 PC 포스터를 쓴다';

    comment on column product.display_item.mobile_url is
        '모바일 미디어 주소. 없으면 PC 주소를 쓴다';

    comment on column product.display_item.pc_poster_url is
        '영상 포스터 (PC). 영상이면 필수';

    comment on column product.display_item.pc_url is
        'PC 미디어 주소. 미디어 아이템만';

    comment on column product.display_item.product_id is
        '상품 아이디 (FK: product.id). 상품 아이템만';

    comment on column product.display_item.section_id is
        '전시 섹션 아이디 (FK: display_section.id)';

    create table product.display_section (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        body text,
        display_order integer not null,
        end_at timestamp(6) with time zone,
        link_label varchar(50),
        link_url varchar(500),
        section_type varchar(20) not null check ((section_type in ('HERO','PRODUCT_PICK','STORY','LOOK','DETAIL'))),
        start_at timestamp(6) with time zone,
        subtitle varchar(200),
        title varchar(200),
        visible boolean not null,
        primary key (id),
        constraint ck_display_section_period check (end_at is null or start_at is null or end_at > start_at),
        constraint ck_display_section_body_length check (body is null or char_length(body) <= 2000),
        constraint ck_display_section_display_order_non_negative check (display_order >= 0)
    );

    comment on table product.display_section is
        '메인 전시 섹션';

    comment on column product.display_section.id is
        '전시 섹션 아이디 (Snowflake)';

    comment on column product.display_section.created_at is
        '생성 시각';

    comment on column product.display_section.updated_at is
        '수정 시각';

    comment on column product.display_section.body is
        '본문 (개행 허용, 2000자 이하)';

    comment on column product.display_section.display_order is
        '메인 화면 안의 노출 순서 (오름차순, 같으면 아이디 오름차순)';

    comment on column product.display_section.end_at is
        '노출 종료 시각 (제외). null 이면 제한 없음';

    comment on column product.display_section.link_label is
        '이동 버튼 문구';

    comment on column product.display_section.link_url is
        '이동 주소 — 슬래시로 시작하는 상대 경로 또는 https';

    comment on column product.display_section.section_type is
        '섹션 유형 (HERO · PRODUCT_PICK · STORY · LOOK · DETAIL)';

    comment on column product.display_section.start_at is
        '노출 시작 시각 (포함). null 이면 제한 없음';

    comment on column product.display_section.subtitle is
        '부제목';

    comment on column product.display_section.title is
        '제목';

    comment on column product.display_section.visible is
        '고객 메인 노출 여부';

    create table product.look (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        description text,
        display_order integer not null,
        image_alt_text varchar(200),
        image_height integer,
        image_url varchar(500) not null,
        image_width integer,
        title varchar(100) not null,
        visible boolean not null,
        primary key (id),
        constraint ck_look_description_length check (description is null or char_length(description) <= 1000),
        constraint ck_look_display_order_non_negative check (display_order >= 0)
    );

    comment on table product.look is
        '룩 (Shop the Look)';

    comment on column product.look.id is
        '룩 아이디 (Snowflake)';

    comment on column product.look.created_at is
        '생성 시각';

    comment on column product.look.updated_at is
        '수정 시각';

    comment on column product.look.description is
        '설명 (평문, 개행 허용, 1000자 이하)';

    comment on column product.look.display_order is
        '룩 목록 안의 노출 순서 (오름차순, 같으면 아이디 오름차순)';

    comment on column product.look.image_alt_text is
        '대표 이미지 대체 텍스트';

    comment on column product.look.image_height is
        '대표 이미지 원본 세로 px. 모르면 null';

    comment on column product.look.image_url is
        '대표 이미지 주소 — 스토어 미디어 저장소의 공개 주소 아래';

    comment on column product.look.image_width is
        '대표 이미지 원본 가로 px. 모르면 null';

    comment on column product.look.title is
        '제목 (평문)';

    comment on column product.look.visible is
        '고객 노출 여부';

    create table product.look_item (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        display_order integer not null,
        look_id bigint not null,
        product_id bigint not null,
        primary key (id),
        constraint uk_look_item_look_id_display_order unique (look_id, display_order),
        constraint uk_look_item_look_id_product_id unique (look_id, product_id),
        constraint ck_look_item_display_order_non_negative check (display_order >= 0)
    );

    comment on table product.look_item is
        '룩 구성 상품';

    comment on column product.look_item.id is
        '룩 아이템 아이디 (Snowflake). 룩 수정마다 새로 발급한다';

    comment on column product.look_item.created_at is
        '생성 시각';

    comment on column product.look_item.updated_at is
        '수정 시각';

    comment on column product.look_item.display_order is
        '룩 안의 순서 (0 부터, 오름차순)';

    comment on column product.look_item.look_id is
        '룩 아이디 (FK: look.id)';

    comment on column product.look_item.product_id is
        '상품 아이디 (FK: product.id)';

    create table product.processed_event (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        consumer_group varchar(200) not null,
        event_id uuid not null,
        event_type varchar(100) not null,
        processed_at timestamp(6) with time zone not null,
        primary key (id),
        constraint uk_processed_event_consumer_group_event_id unique (consumer_group, event_id)
    );

    comment on table product.processed_event is
        '처리한 이벤트 기록 (멱등 컨슈머 inbox)';

    comment on column product.processed_event.id is
        '처리 기록 아이디 (Snowflake)';

    comment on column product.processed_event.created_at is
        '생성 시각';

    comment on column product.processed_event.updated_at is
        '수정 시각';

    comment on column product.processed_event.consumer_group is
        '컨슈머 그룹 (예: commerce-foundry.prod.product.order-stock-release)';

    comment on column product.processed_event.event_id is
        '이벤트 아이디 (봉투 eventId)';

    comment on column product.processed_event.event_type is
        '이벤트 종류 (예: order-expired)';

    comment on column product.processed_event.processed_at is
        '처리 시각 — 보존 기간 정리 기준';

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
        expected_ship_date date,
        list_price_amount bigint,
        list_price_currency varchar(3) check ((list_price_currency in ('KRW','USD'))),
        max_quantity_per_order integer,
        name varchar(100) not null,
        preorder boolean not null,
        sale_price_amount bigint not null,
        sale_price_currency varchar(3) not null check ((sale_price_currency in ('KRW','USD'))),
        sale_status varchar(20) not null check ((sale_status in ('ON_SALE','STOP_SELLING'))),
        summary varchar(200),
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

    comment on column product.product.version is
        '낙관적 락 버전';

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
        image_type varchar(20) not null check ((image_type in ('THUMBNAIL','GALLERY','VIDEO'))),
        poster_url varchar(500),
        product_id bigint not null,
        url varchar(500) not null,
        width integer,
        primary key (id),
        constraint uk_product_image_product_id_image_type_display_order unique (product_id, image_type, display_order),
        constraint ck_product_image_thumbnail_display_order check (image_type <> 'THUMBNAIL' or display_order = 0),
        constraint ck_product_image_poster_url check ((image_type = 'VIDEO' and poster_url is not null) or (image_type <> 'VIDEO' and poster_url is null))
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
        '이미지 유형 — THUMBNAIL(목록 대표, 상품당 1개) / GALLERY(상세 갤러리) / VIDEO(상세 갤러리 영상)';

    comment on column product.product_image.poster_url is
        '영상 포스터 이미지 URL. VIDEO 면 필수, 그 밖에는 null';

    comment on column product.product_image.product_id is
        '상품 아이디 (FK: product.id)';

    comment on column product.product_image.url is
        '이미지 URL. VIDEO 면 영상 파일 URL';

    comment on column product.product_image.width is
        '원본 가로 px. 모르면 null';

    create table product.product_option_group (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        display_order integer not null,
        name varchar(50) not null,
        product_id bigint not null,
        retired_at timestamp(6) with time zone,
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

    comment on column product.product_option_group.retired_at is
        '은퇴 시각. null 이면 사용 중. 은퇴한 그룹은 고객 상세에서 빠지고, 행은 주문 · 장바구니의 옵션명 표시용으로 남는다';

    create table product.product_option_value (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        display_order integer not null,
        name varchar(50) not null,
        option_group_id bigint not null,
        product_id bigint not null,
        retired_at timestamp(6) with time zone,
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

    comment on column product.product_option_value.retired_at is
        '은퇴 시각. null 이면 사용 중. 은퇴한 값과 그 값을 쓰는 SKU 는 고객 상세에서 빠지고, 행은 주문 · 장바구니의 옵션명 표시용으로 남는다';

    create table product.product_size_cell (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        cell_value numeric(10,2) not null,
        column_id bigint not null,
        row_id bigint not null,
        primary key (id),
        constraint uk_product_size_cell_row_id_column_id unique (row_id, column_id),
        constraint ck_product_size_cell_value_range check (cell_value > 0 and cell_value <= 9999.99)
    );

    comment on table product.product_size_cell is
        '상품 사이즈표 칸';

    comment on column product.product_size_cell.id is
        '사이즈 칸 아이디 (Snowflake)';

    comment on column product.product_size_cell.created_at is
        '생성 시각';

    comment on column product.product_size_cell.updated_at is
        '수정 시각';

    comment on column product.product_size_cell.cell_value is
        '치수 값. 0 초과 9999.99 이하, 소수 둘째 자리. 템플릿 저장 단위 그대로';

    comment on column product.product_size_cell.column_id is
        '템플릿 항목 아이디 (FK: size_chart_template_column.id)';

    comment on column product.product_size_cell.row_id is
        '사이즈 행 아이디 (FK: product_size_row.id)';

    create table product.product_size_chart (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        note varchar(500),
        product_id bigint not null,
        template_id bigint not null,
        primary key (id),
        constraint uk_product_size_chart_product_id unique (product_id)
    );

    comment on table product.product_size_chart is
        '상품 사이즈표';

    comment on column product.product_size_chart.id is
        '상품 사이즈표 아이디 (Snowflake)';

    comment on column product.product_size_chart.created_at is
        '생성 시각';

    comment on column product.product_size_chart.updated_at is
        '수정 시각';

    comment on column product.product_size_chart.note is
        '사이즈표 비고. 없으면 null';

    comment on column product.product_size_chart.product_id is
        '상품 아이디 (FK: product.id)';

    comment on column product.product_size_chart.template_id is
        '사이즈표 템플릿 아이디 (FK: size_chart_template.id)';

    create table product.product_size_row (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        display_order integer not null,
        size_chart_id bigint not null,
        size_label varchar(20) not null,
        primary key (id),
        constraint uk_product_size_row_size_chart_id_size_label unique (size_chart_id, size_label),
        constraint uk_product_size_row_size_chart_id_display_order unique (size_chart_id, display_order),
        constraint ck_product_size_row_display_order_non_negative check (display_order >= 0)
    );

    comment on table product.product_size_row is
        '상품 사이즈표 행';

    comment on column product.product_size_row.id is
        '사이즈 행 아이디 (Snowflake)';

    comment on column product.product_size_row.created_at is
        '생성 시각';

    comment on column product.product_size_row.updated_at is
        '수정 시각';

    comment on column product.product_size_row.display_order is
        '사이즈 노출 순서 (같은 표 안에서 유일, 오름차순)';

    comment on column product.product_size_row.size_chart_id is
        '상품 사이즈표 아이디 (FK: product_size_chart.id)';

    comment on column product.product_size_row.size_label is
        '사이즈 이름';

    create table product.shedlock (
        name varchar(64) not null,
        lock_until timestamp(6) with time zone not null,
        locked_at timestamp(6) with time zone not null,
        locked_by varchar(255) not null,
        primary key (name)
    );

    comment on table product.shedlock is
        '단일 실행 락 (ShedLock — 메시징 정리)';

    comment on column product.shedlock.name is
        '락 이름';

    comment on column product.shedlock.lock_until is
        '이 시각까지 락을 쥔다';

    comment on column product.shedlock.locked_at is
        '락을 잡은 시각';

    comment on column product.shedlock.locked_by is
        '락을 잡은 인스턴스 (호스트 이름)';

    create table product.size_chart_template (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        base_unit varchar(10) not null check ((base_unit in ('CM','INCH'))),
        name varchar(50) not null,
        primary key (id),
        constraint ck_size_chart_template_base_unit check (base_unit in ('CM', 'INCH'))
    );

    comment on table product.size_chart_template is
        '사이즈표 템플릿';

    comment on column product.size_chart_template.id is
        '사이즈표 템플릿 아이디 (Snowflake)';

    comment on column product.size_chart_template.created_at is
        '생성 시각';

    comment on column product.size_chart_template.updated_at is
        '수정 시각';

    comment on column product.size_chart_template.base_unit is
        '치수 저장 단위 (CM 또는 INCH). 화면 변환은 응답에서만 한다';

    comment on column product.size_chart_template.name is
        '템플릿 이름';

    create table product.size_chart_template_column (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        description varchar(200),
        display_order integer not null,
        name varchar(30) not null,
        template_id bigint not null,
        primary key (id),
        constraint uk_size_chart_template_column_template_id_name unique (template_id, name),
        constraint ck_size_chart_template_column_display_order_non_negative check (display_order >= 0)
    );

    comment on table product.size_chart_template_column is
        '사이즈표 템플릿 항목';

    comment on column product.size_chart_template_column.id is
        '템플릿 항목 아이디 (Snowflake)';

    comment on column product.size_chart_template_column.created_at is
        '생성 시각';

    comment on column product.size_chart_template_column.updated_at is
        '수정 시각';

    comment on column product.size_chart_template_column.description is
        '항목 설명. 없으면 null';

    comment on column product.size_chart_template_column.display_order is
        '항목 노출 순서 (오름차순, 같으면 아이디 오름차순)';

    comment on column product.size_chart_template_column.name is
        '항목 이름';

    comment on column product.size_chart_template_column.template_id is
        '사이즈표 템플릿 아이디 (FK: size_chart_template.id)';

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

    create table product.sku_cost (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        admin_member_id bigint not null,
        amount bigint not null,
        cost_type varchar(20) not null check ((cost_type in ('GOODS','PACKAGING'))),
        currency varchar(3) not null check ((currency in ('KRW','USD'))),
        effective_from timestamp(6) with time zone not null,
        reason varchar(200) not null,
        sku_id bigint not null,
        primary key (id),
        constraint uk_sku_cost_sku_id_cost_type_effective_from unique (sku_id, cost_type, effective_from),
        constraint ck_sku_cost_amount_non_negative check (amount >= 0)
    );

    comment on table product.sku_cost is
        'SKU 원가 적용 이력 (append-only)';

    comment on column product.sku_cost.id is
        'SKU 원가 이력 아이디 (Snowflake)';

    comment on column product.sku_cost.created_at is
        '생성 시각';

    comment on column product.sku_cost.updated_at is
        '수정 시각';

    comment on column product.sku_cost.admin_member_id is
        '기록한 어드민 회원 아이디 (외부 FK: auth-service member.id)';

    comment on column product.sku_cost.amount is
        '원가 (0 이상, 최소 화폐 단위). 0 은 0원이라고 명시한 값';

    comment on column product.sku_cost.cost_type is
        '원가 유형 (GOODS 매입 · 제작 원가 / PACKAGING 포장 변동비)';

    comment on column product.sku_cost.currency is
        '원가 통화 — 같은 SKU · 유형의 첫 행 통화로 고정';

    comment on column product.sku_cost.effective_from is
        '적용 시작 시각 (포함). 같은 SKU · 유형의 다음 행 시작 전까지 적용';

    comment on column product.sku_cost.reason is
        '변경 사유';

    comment on column product.sku_cost.sku_id is
        'SKU 아이디 (FK: sku.id)';

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
        release_reason varchar(20) check ((release_reason in ('EXPIRED','RELEASED_BY_CALLER','ORDER_EXPIRED','ORDER_CANCELED'))),
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
        '해제 사유 (EXPIRED / RELEASED_BY_CALLER / ORDER_EXPIRED / ORDER_CANCELED). RELEASED 일 때만';

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

    create table product.stock_restoration (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        claim_id bigint not null,
        order_id bigint not null,
        reason varchar(20) not null check ((reason in ('CANCELLATION','RETURN'))),
        reservation_id bigint not null,
        primary key (id),
        constraint uk_stock_restoration_claim_id unique (claim_id)
    );

    comment on table product.stock_restoration is
        '결제 후 재고 복원 (클레임 하나에 한 행, append-only)';

    comment on column product.stock_restoration.id is
        '재고 복원 아이디 (Snowflake)';

    comment on column product.stock_restoration.created_at is
        '생성 시각';

    comment on column product.stock_restoration.updated_at is
        '수정 시각';

    comment on column product.stock_restoration.claim_id is
        '클레임 아이디 (외부 FK: order-service 클레임 id). 클레임당 복원 1건 — 멱등 키';

    comment on column product.stock_restoration.order_id is
        '주문 아이디 (외부 FK: order-service 주문 id). 선점에서 복사한 운영 조회용 값';

    comment on column product.stock_restoration.reason is
        '복원 사유 (CANCELLATION / RETURN)';

    comment on column product.stock_restoration.reservation_id is
        '복원 대상 확정 선점 아이디 (FK: stock_reservation.id)';

    create table product.stock_restoration_item (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        quantity integer not null,
        restoration_id bigint not null,
        restored boolean not null,
        sku_id bigint not null,
        primary key (id),
        constraint uk_stock_restoration_item_restoration_id_sku_id unique (restoration_id, sku_id),
        constraint ck_stock_restoration_item_quantity_positive check (quantity > 0)
    );

    comment on table product.stock_restoration_item is
        '결제 후 재고 복원 항목 (복원 × SKU)';

    comment on column product.stock_restoration_item.id is
        '재고 복원 항목 아이디 (Snowflake)';

    comment on column product.stock_restoration_item.created_at is
        '생성 시각';

    comment on column product.stock_restoration_item.updated_at is
        '수정 시각';

    comment on column product.stock_restoration_item.quantity is
        '복원 수량 (1 이상). 재고를 되돌리지 않은 무제한 SKU 도 복원 상한 계산에 들어간다';

    comment on column product.stock_restoration_item.restoration_id is
        '재고 복원 아이디 (FK: stock_restoration.id)';

    comment on column product.stock_restoration_item.restored is
        '되돌릴 대상(선점 때 차감)이었는가 — 재고 행이 없으면 warn 만 남기고 수량은 늘지 않는다. 무제한 SKU 는 false(기록만)';

    comment on column product.stock_restoration_item.sku_id is
        'SKU 아이디 (FK: sku.id)';

    create index idx_look_item_product_id_look_id 
       on product.look_item (product_id, look_id);

    create index idx_processed_event_processed_at 
       on product.processed_event (processed_at);

    create index idx_product_visible_sale_price_amount_id 
       on product.product (visible, sale_price_amount, id);

    create index idx_product_created_by_member_id_deleted_at_created_at
       on product.product (created_by_member_id, deleted_at, created_at);

    create index idx_product_category_category_id_product_id 
       on product.product_category (category_id, product_id);

    create index idx_product_option_value_product_id 
       on product.product_option_value (product_id);

    create index idx_product_size_chart_template_id 
       on product.product_size_chart (template_id);

    create index idx_size_chart_template_column_template_id_display_order 
       on product.size_chart_template_column (template_id, display_order);

    create index idx_stock_adjustment_history_sku_id_id 
       on product.stock_adjustment_history (sku_id, id);

    create index idx_stock_reservation_status_expires_at_id 
       on product.stock_reservation (status, expires_at, id);

    create index idx_stock_reservation_order_id 
       on product.stock_reservation (order_id);

    create index idx_stock_restoration_reservation_id 
       on product.stock_restoration (reservation_id);
