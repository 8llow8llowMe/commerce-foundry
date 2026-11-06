
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

    create table orders.claim (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        attempt_count integer not null,
        expected_refund_amount bigint,
        expected_refund_currency varchar(3) check ((expected_refund_currency in ('KRW','USD'))),
        failure_code varchar(40),
        next_attempt_at timestamp(6) with time zone,
        order_id bigint not null,
        payment_id bigint,
        request_key uuid not null,
        status varchar(20) not null check ((status in ('REQUESTED','REFUNDED','COMPLETED','REFUND_FAILED','RESTORATION_FAILED'))),
        type varchar(20) not null check ((type in ('RETURN','CANCELLATION'))),
        version bigint not null,
        primary key (id),
        constraint uk_claim_order_id_request_key unique (order_id, request_key),
        constraint ck_claim_identifiers_positive check (id > 0 and order_id > 0 and version >= 0),
        constraint ck_claim_expected_refund_pair check ((expected_refund_amount is null and expected_refund_currency is null) or (expected_refund_amount is not null and expected_refund_currency is not null and expected_refund_amount >= 0)),
        constraint ck_claim_attempt_count_non_negative check (attempt_count >= 0),
        constraint ck_claim_cancellation_payment check (type <> 'CANCELLATION' or (payment_id is not null and payment_id > 0 and expected_refund_amount is not null and expected_refund_amount > 0)),
        constraint ck_claim_payment_cancellation_only check (type = 'CANCELLATION' or payment_id is null),
        constraint ck_claim_cancellation_next_attempt check (type <> 'CANCELLATION' or ((status in ('REQUESTED', 'REFUNDED')) = (next_attempt_at is not null))),
        constraint ck_claim_cancellation_failure_code check (type <> 'CANCELLATION' or ((status in ('REFUND_FAILED', 'RESTORATION_FAILED')) = (failure_code is not null)))
    );

    comment on table orders.claim is
        '주문 클레임 접수';

    comment on column orders.claim.id is
        '클레임 아이디 (Snowflake)';

    comment on column orders.claim.created_at is
        '생성 시각';

    comment on column orders.claim.updated_at is
        '수정 시각';

    comment on column orders.claim.attempt_count is
        '지금 단계(환불 · 재고 복원)의 미확정 재시도 횟수';

    comment on column orders.claim.expected_refund_amount is
        '예상 환불 최소 단위 금액 (미산정이면 null)';

    comment on column orders.claim.expected_refund_currency is
        '예상 환불 통화 (미산정이면 null)';

    comment on column orders.claim.failure_code is
        '종결 실패 사유 코드 (REFUND_FAILED · RESTORATION_FAILED). 아니면 null';

    comment on column orders.claim.next_attempt_at is
        '재시도 스케줄러가 다시 집을 시각. 종결이면 null';

    comment on column orders.claim.order_id is
        '주문 아이디 (FK: orders.id)';

    comment on column orders.claim.payment_id is
        '환불할 결제 아이디 (외부 FK: payment-service payment.id). 취소 클레임에만';

    comment on column orders.claim.request_key is
        '주문별 멱등 접수 키 (UUID). 전체 취소는 주문 아이디로 만든 결정적 키';

    comment on column orders.claim.status is
        '클레임 상태 (REQUESTED / REFUNDED / COMPLETED / REFUND_FAILED / RESTORATION_FAILED)';

    comment on column orders.claim.type is
        '클레임 유형 (RETURN / CANCELLATION)';

    comment on column orders.claim.version is
        '낙관적 락 버전';

    create table orders.claim_item (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        claim_id bigint not null,
        order_item_id bigint not null,
        quantity integer not null,
        reason varchar(20) not null check ((reason in ('CHANGE_OF_MIND','DEFECTIVE','WRONG_ITEM','ORDER_MISTAKE','PAYMENT_ISSUE','OTHER'))),
        primary key (id),
        constraint uk_claim_item_claim_id_order_item_id unique (claim_id, order_item_id),
        constraint ck_claim_item_identifiers_positive check (id > 0 and claim_id > 0 and order_item_id > 0),
        constraint ck_claim_item_quantity_positive check (quantity > 0)
    );

    comment on table orders.claim_item is
        '클레임 항목 스냅숏';

    comment on column orders.claim_item.id is
        '클레임 항목 아이디 (Snowflake)';

    comment on column orders.claim_item.created_at is
        '생성 시각';

    comment on column orders.claim_item.updated_at is
        '수정 시각';

    comment on column orders.claim_item.claim_id is
        '클레임 아이디 (FK: claim.id)';

    comment on column orders.claim_item.order_item_id is
        '주문 항목 아이디 (FK: order_item.id)';

    comment on column orders.claim_item.quantity is
        '클레임 수량';

    comment on column orders.claim_item.reason is
        '접수 사유 (반품 CHANGE_OF_MIND / DEFECTIVE / WRONG_ITEM, 취소 CHANGE_OF_MIND / ORDER_MISTAKE / PAYMENT_ISSUE / OTHER)';

    create table orders.order_item (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        custom_option_hash varchar(64) not null,
        custom_order boolean not null,
        hs_code varchar(10),
        line_amount bigint not null,
        line_currency varchar(3) not null check ((line_currency in ('KRW','USD'))),
        line_no integer not null,
        option_name varchar(300) not null,
        order_id bigint not null,
        product_id bigint not null,
        product_name varchar(200) not null,
        quantity integer not null,
        sku_id bigint not null,
        unit_price_amount bigint not null,
        unit_price_currency varchar(3) not null check ((unit_price_currency in ('KRW','USD'))),
        unit_weight_grams integer,
        primary key (id),
        constraint uk_order_item_order_id_line_no unique (order_id, line_no),
        constraint ck_order_item_quantity_range check (quantity between 1 and 99),
        constraint ck_order_item_line_no_positive check (line_no >= 1),
        constraint ck_order_item_line_amount_consistent check (unit_price_amount >= 0 and line_amount = unit_price_amount * quantity and line_currency = unit_price_currency),
        constraint ck_order_item_unit_weight_grams_positive check (unit_weight_grams is null or unit_weight_grams >= 1),
        constraint ck_order_item_hs_code_format check (hs_code is null or (char_length(hs_code) between 6 and 10 and ltrim(hs_code, '0123456789') = ''))
    );

    comment on table orders.order_item is
        '주문 항목 (주문 시점 SKU 스냅숏)';

    comment on column orders.order_item.id is
        '주문 항목 아이디 (Snowflake)';

    comment on column orders.order_item.created_at is
        '생성 시각';

    comment on column orders.order_item.updated_at is
        '수정 시각';

    comment on column orders.order_item.custom_option_hash is
        '맞춤 제작 입력의 정규화 SHA-256 hex (장바구니 줄 해시와 같은 알고리즘). 입력이 없으면 빈 문자열';

    comment on column orders.order_item.custom_order is
        '주문 시점에 맞춤 제작 상품이었는가';

    comment on column orders.order_item.hs_code is
        '주문 시점 HS 코드 (숫자 6~10자리). 미입력이면 null';

    comment on column orders.order_item.line_amount is
        '판매가 × 수량';

    comment on column orders.order_item.line_currency is
        '줄 금액 통화';

    comment on column orders.order_item.line_no is
        '요청 items 순서의 1부터 번호';

    comment on column orders.order_item.option_name is
        '주문 시점 옵션 표시명 — 옵션 값 이름(50자)을 최대 5그룹 " / " 로 이은 값이라 최대 262자. 옵션이 없으면 빈 문자열';

    comment on column orders.order_item.order_id is
        '주문 아이디 (FK: orders.id)';

    comment on column orders.order_item.product_id is
        '상품 아이디 (외부 FK: product-service product.id)';

    comment on column orders.order_item.product_name is
        '주문 시점 상품명 (원천 product.name 100자)';

    comment on column orders.order_item.quantity is
        '수량 (1~99, 줄 단위)';

    comment on column orders.order_item.sku_id is
        'SKU 아이디 (외부 FK: product-service sku.id)';

    comment on column orders.order_item.unit_price_amount is
        '주문 시점 판매가 (옵션 추가금 포함)';

    comment on column orders.order_item.unit_price_currency is
        '판매가 통화';

    comment on column orders.order_item.unit_weight_grams is
        '주문 시점 SKU 무게(그램). 미입력이면 null';

    create table orders.order_item_custom_option (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        display_order integer not null,
        field_key varchar(50) not null,
        field_value varchar(500) not null,
        order_id bigint not null,
        order_item_id bigint not null,
        primary key (id),
        constraint uk_order_item_custom_option_order_item_id_field_key unique (order_item_id, field_key)
    );

    comment on table orders.order_item_custom_option is
        '주문 항목의 맞춤 제작 입력 (주문 시점 스냅숏)';

    comment on column orders.order_item_custom_option.id is
        '맞춤 제작 입력 아이디 (Snowflake)';

    comment on column orders.order_item_custom_option.created_at is
        '생성 시각';

    comment on column orders.order_item_custom_option.updated_at is
        '수정 시각';

    comment on column orders.order_item_custom_option.display_order is
        '표시 순서 (받은 순서)';

    comment on column orders.order_item_custom_option.field_key is
        '상품 입력 항목 키 (chest, memo)';

    comment on column orders.order_item_custom_option.field_value is
        '입력값 — 앞뒤 공백을 자르고 NFC 정규화한 원문. 서버가 HTML 을 거르지 않는다(화면 · 메일 · CSV 는 텍스트로 이스케이프)';

    comment on column orders.order_item_custom_option.order_id is
        '주문 아이디 (FK: orders.id) — 주문 단위로 읽고 지우는 일이 항목을 거치지 않게';

    comment on column orders.order_item_custom_option.order_item_id is
        '주문 항목 아이디 (FK: order_item.id)';

    create table orders.order_payment (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        approved_at timestamp(6) with time zone,
        event_id uuid not null,
        member_id bigint not null,
        order_id bigint not null,
        paid_order_id bigint,
        payment_id bigint not null,
        restoration_claim_id bigint,
        restored_at timestamp(6) with time zone,
        result varchar(40) not null check ((result in ('PAID','REJECTED_ORDER_CANCELED','REJECTED_ORDER_NOT_FOUND','REJECTED_PAYMENT_MISMATCH','REJECTED_STOCK_UNAVAILABLE','REJECTED_ALREADY_PAID','REJECTED_PAYMENT_NOT_DONE'))),
        stock_reservation_id bigint,
        total_amount bigint not null,
        total_currency varchar(3) not null check ((total_currency in ('KRW','USD'))),
        primary key (id),
        constraint uk_order_payment_payment_id unique (payment_id),
        constraint uk_order_payment_paid_order_id unique (paid_order_id),
        constraint ck_order_payment_total_amount_positive check (total_amount > 0),
        constraint ck_order_payment_paid_order_id_consistent check ((result = 'PAID' and paid_order_id is not null and paid_order_id = order_id) or (result <> 'PAID' and paid_order_id is null)),
        constraint ck_order_payment_paid_requires_confirmation check (result <> 'PAID' or (stock_reservation_id is not null and approved_at is not null)),
        constraint ck_order_payment_reservation_scope check (stock_reservation_id is null or result in ('PAID', 'REJECTED_ORDER_CANCELED') or (result = 'REJECTED_PAYMENT_NOT_DONE' and restoration_claim_id is not null and approved_at is not null)),
        constraint ck_order_payment_restoration_consistent check ((restoration_claim_id is null and restored_at is null) or (restoration_claim_id is not null and result in ('REJECTED_ORDER_CANCELED', 'REJECTED_PAYMENT_NOT_DONE') and stock_reservation_id is not null))
    );

    comment on table orders.order_payment is
        '결제 반영 기록 (payment-approved 이벤트 + 재조회 결과, 결제당 1행)';

    comment on column orders.order_payment.id is
        '결제 반영 기록 아이디 (Snowflake)';

    comment on column orders.order_payment.created_at is
        '생성 시각';

    comment on column orders.order_payment.updated_at is
        '수정 시각';

    comment on column orders.order_payment.approved_at is
        '결제 승인 시각 (payment 재조회 값). 결제 완료면 필수, 거절이면 null 일 수 있다';

    comment on column orders.order_payment.event_id is
        '반영한 payment-approved 이벤트 아이디 (봉투 eventId)';

    comment on column orders.order_payment.member_id is
        '결제한 회원 아이디 (외부 FK: auth-service member.id, payment 재조회 값)';

    comment on column orders.order_payment.order_id is
        '주문 아이디 (논리 FK: orders.id, 제약 없음) — 이벤트가 가리킨 주문. 주문 없음 거절이면 없는 아이디다';

    comment on column orders.order_payment.paid_order_id is
        '결제 완료면 order_id, 아니면 null — 주문당 결제 완료 1건 유니크';

    comment on column orders.order_payment.payment_id is
        '결제 아이디 (외부 FK: payment-service payment.id)';

    comment on column orders.order_payment.restoration_claim_id is
        '확정 뒤 ORDER_CANCELED 또는 NOT_DONE 거절의 재고 복원 클레임 아이디 (product stock-restorations 멱등 키). 없으면 null';

    comment on column orders.order_payment.restored_at is
        '재고 복원 200 을 받은 시각 (#308 PR-C). 복원 전이면 null';

    comment on column orders.order_payment.result is
        '반영 결과 (PAID / REJECTED_* — 거절 사유는 order-payment-rejected 회신 reason 과 같다)';

    comment on column orders.order_payment.stock_reservation_id is
        '확정 선점 아이디 (외부 FK: product-service stock_reservation.id). PAID · ORDER_CANCELED 또는 원승인 시각과 복원 클레임이 있는 NOT_DONE';

    comment on column orders.order_payment.total_amount is
        '결제 금액 — 통화의 최소 단위 정수 (payment 재조회 값)';

    comment on column orders.order_payment.total_currency is
        '결제 금액 통화';

    create table orders.order_request (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        idempotency_key uuid not null,
        lease_expires_at timestamp(6) with time zone not null,
        member_id bigint not null,
        order_id bigint not null,
        request_hash varchar(64) not null,
        status varchar(20) not null check ((status in ('PROCESSING','COMPLETED'))),
        version bigint not null,
        primary key (id),
        constraint uk_order_request_member_id_idempotency_key unique (member_id, idempotency_key),
        constraint ck_order_request_identifiers_positive check (id > 0 and member_id > 0 and order_id > 0 and version >= 0),
        constraint ck_order_request_hash_format check (char_length(request_hash) = 64 and ltrim(request_hash, '0123456789abcdef') = '')
    );

    comment on table orders.order_request is
        '주문 요청 기록 (회원 × Idempotency-Key)';

    comment on column orders.order_request.id is
        '기록 아이디 (Snowflake)';

    comment on column orders.order_request.created_at is
        '생성 시각';

    comment on column orders.order_request.updated_at is
        '수정 시각';

    comment on column orders.order_request.idempotency_key is
        'Idempotency-Key 헤더 (UUID)';

    comment on column orders.order_request.lease_expires_at is
        '처리 중 lease 가 끝나는 시각 — 지나면 같은 키의 재요청이 이어받는다';

    comment on column orders.order_request.member_id is
        '회원 아이디 (외부 FK: auth-service member.id)';

    comment on column orders.order_request.order_id is
        '미리 발급한 주문 아이디 (FK: orders.id — 주문이 저장되기 전에도 있다)';

    comment on column orders.order_request.request_hash is
        '요청 본문 지문 (정규화한 items · 배송지 · expectedTotalAmount 의 SHA-256 hex). 원문은 남지 않는다';

    comment on column orders.order_request.status is
        '처리 상태 (PROCESSING / COMPLETED)';

    comment on column orders.order_request.version is
        '낙관적 락 버전';

    create table orders.order_request_item (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        order_request_id bigint not null,
        product_id bigint not null,
        quantity integer not null,
        primary key (id),
        constraint uk_order_request_item_order_request_id_product_id unique (order_request_id, product_id),
        constraint ck_order_request_item_identifiers_positive check (id > 0 and order_request_id > 0 and product_id > 0),
        constraint ck_order_request_item_quantity_positive check (quantity >= 1)
    );

    comment on table orders.order_request_item is
        '주문 요청 기록의 상품별 수량 (진행 중 시도의 수량 상한 계수용)';

    comment on column orders.order_request_item.id is
        '항목 아이디 (Snowflake)';

    comment on column orders.order_request_item.created_at is
        '생성 시각';

    comment on column orders.order_request_item.updated_at is
        '수정 시각';

    comment on column orders.order_request_item.order_request_id is
        '주문 요청 기록 아이디 (FK: order_request.id)';

    comment on column orders.order_request_item.product_id is
        '상품 아이디 (외부 FK: product-service product.id)';

    comment on column orders.order_request_item.quantity is
        '요청에 담긴 이 상품의 수량 합 (줄 · SKU 를 합친 상품 단위)';

    create table orders.order_shipping_address (
        order_id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        address_line1 varchar(200) not null,
        address_line2 varchar(200),
        city varchar(100),
        country_code varchar(2) not null,
        delivery_memo varchar(200),
        postal_code varchar(20),
        recipient_name varchar(50) not null,
        recipient_phone varchar(20) not null,
        state_province varchar(100),
        primary key (order_id),
        constraint ck_order_shipping_address_country_code_format check (char_length(country_code) = 2 and ltrim(country_code, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ') = ''),
        constraint ck_order_shipping_address_postal_code_required check (country_code <> 'KR' or postal_code is not null),
        constraint ck_order_shipping_address_city_required check (country_code = 'KR' or (city is not null and trim(city) <> '')),
        constraint ck_order_shipping_address_recipient_phone_format check ((country_code = 'KR' and char_length(recipient_phone) between 9 and 11 and substring(recipient_phone from 1 for 1) = '0' and ltrim(recipient_phone, '0123456789') = '') or (country_code <> 'KR' and ((char_length(recipient_phone) between 7 and 15 and ltrim(recipient_phone, '0123456789') = '') or (char_length(recipient_phone) between 8 and 16 and substring(recipient_phone from 1 for 1) = '+' and ltrim(substring(recipient_phone from 2), '0123456789') = ''))))
    );

    comment on table orders.order_shipping_address is
        '주문 배송지 스냅숏 (주문과 1:1, 개인정보)';

    comment on column orders.order_shipping_address.order_id is
        '주문 아이디 (FK: orders.id)';

    comment on column orders.order_shipping_address.created_at is
        '생성 시각';

    comment on column orders.order_shipping_address.updated_at is
        '수정 시각';

    comment on column orders.order_shipping_address.address_line1 is
        '기본 주소 (개인정보)';

    comment on column orders.order_shipping_address.address_line2 is
        '상세 주소 (개인정보). 없으면 null';

    comment on column orders.order_shipping_address.city is
        '도시 — 해외 배송지는 필수, 국내는 없으면 null';

    comment on column orders.order_shipping_address.country_code is
        '배송 국가 (ISO 3166-1 alpha-2 대문자)';

    comment on column orders.order_shipping_address.delivery_memo is
        '배송 요청 사항. 없으면 null';

    comment on column orders.order_shipping_address.postal_code is
        '우편번호. 국내는 필수, 해외는 없으면 null';

    comment on column orders.order_shipping_address.recipient_name is
        '받는 사람 (개인정보)';

    comment on column orders.order_shipping_address.recipient_phone is
        '받는 사람 연락처 — 공백 · 하이픈을 뺀 값 (국내 숫자 9~11자리 · 해외 E.164, 개인정보)';

    comment on column orders.order_shipping_address.state_province is
        '주 · 도 (state / province). 없으면 null';

    create table orders.order_status_history (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        changed_by_id bigint,
        changed_by_type varchar(20) not null check ((changed_by_type in ('CUSTOMER','ADMIN','SYSTEM','SERVICE'))),
        from_status varchar(30) check ((from_status in ('PENDING_PAYMENT','EXPIRED','PAID','MAKING','PREPARING','SHIPPING','DELIVERED','PURCHASE_CONFIRMED','CANCEL_REQUESTED','CANCELED','RETURN_REQUESTED','RETURNED'))),
        order_id bigint not null,
        reason varchar(200),
        to_status varchar(30) not null check ((to_status in ('PENDING_PAYMENT','EXPIRED','PAID','MAKING','PREPARING','SHIPPING','DELIVERED','PURCHASE_CONFIRMED','CANCEL_REQUESTED','CANCELED','RETURN_REQUESTED','RETURNED'))),
        primary key (id),
        constraint ck_order_status_history_changed_by_id_consistent check ((changed_by_type in ('CUSTOMER', 'ADMIN') and changed_by_id is not null) or (changed_by_type in ('SYSTEM', 'SERVICE') and changed_by_id is null))
    );

    comment on table orders.order_status_history is
        '주문 상태 변경 이력 (추가만 한다)';

    comment on column orders.order_status_history.id is
        '이력 아이디 (Snowflake)';

    comment on column orders.order_status_history.created_at is
        '생성 시각';

    comment on column orders.order_status_history.updated_at is
        '수정 시각';

    comment on column orders.order_status_history.changed_by_id is
        '바꾼 회원 · 관리자 아이디 (외부 FK: auth-service member.id). SYSTEM · SERVICE 면 null';

    comment on column orders.order_status_history.changed_by_type is
        '바꾼 주체 유형 (CUSTOMER / ADMIN / SYSTEM / SERVICE)';

    comment on column orders.order_status_history.from_status is
        '이전 상태. 주문 생성이면 null';

    comment on column orders.order_status_history.order_id is
        '주문 아이디 (FK: orders.id)';

    comment on column orders.order_status_history.reason is
        '사유. 없으면 null';

    comment on column orders.order_status_history.to_status is
        '바뀐 상태';

    create table orders.order_stock_confirmation (
        payment_id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        event_id uuid not null,
        order_id bigint not null,
        stock_reservation_id bigint not null,
        primary key (payment_id)
    );

    comment on table orders.order_stock_confirmation is
        '결제 반영의 재선점 증거 (재선점 새 선점 아이디를 확정 전에 기록, 결제당 1행)';

    comment on column orders.order_stock_confirmation.payment_id is
        '결제 아이디 (외부 FK: payment-service payment.id)';

    comment on column orders.order_stock_confirmation.created_at is
        '생성 시각';

    comment on column orders.order_stock_confirmation.updated_at is
        '수정 시각';

    comment on column orders.order_stock_confirmation.event_id is
        '기록한 payment-approved 이벤트 아이디 (봉투 eventId)';

    comment on column orders.order_stock_confirmation.order_id is
        '주문 아이디 (FK: orders.id)';

    comment on column orders.order_stock_confirmation.stock_reservation_id is
        '확정하려는 재선점 아이디 (외부 FK: product-service stock_reservation.id)';

    create table orders.orders (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        delivered_at timestamp(6) with time zone,
        items_amount bigint not null,
        items_currency varchar(3) not null check ((items_currency in ('KRW','USD'))),
        member_id bigint not null,
        payment_due_at timestamp(6) with time zone not null,
        shipped_at timestamp(6) with time zone,
        shipping_base_fee_amount bigint not null,
        shipping_base_fee_currency varchar(3) not null check ((shipping_base_fee_currency in ('KRW','USD'))),
        shipping_fee_amount bigint not null,
        shipping_fee_currency varchar(3) not null check ((shipping_fee_currency in ('KRW','USD'))),
        shipping_free_shipping_applied boolean not null,
        shipping_free_shipping_threshold_amount bigint,
        shipping_free_shipping_threshold_currency varchar(3) check ((shipping_free_shipping_threshold_currency in ('KRW','USD'))),
        shipping_policy_version bigint not null,
        shipping_remote_area_name varchar(50),
        shipping_remote_area_surcharge_amount bigint not null,
        shipping_remote_area_surcharge_currency varchar(3) not null check ((shipping_remote_area_surcharge_currency in ('KRW','USD'))),
        shipping_scope varchar(20) not null check ((shipping_scope in ('DOMESTIC','INTERNATIONAL'))),
        shipping_weight_fee_amount bigint,
        shipping_weight_fee_currency varchar(3) check ((shipping_weight_fee_currency in ('KRW','USD'))),
        shipping_weight_grams integer,
        shipping_zone_name varchar(50),
        status varchar(30) not null check ((status in ('PENDING_PAYMENT','EXPIRED','PAID','MAKING','PREPARING','SHIPPING','DELIVERED','PURCHASE_CONFIRMED','CANCEL_REQUESTED','CANCELED','RETURN_REQUESTED','RETURNED'))),
        stock_reservation_id bigint not null,
        total_amount bigint not null,
        total_currency varchar(3) not null check ((total_currency in ('KRW','USD'))),
        version bigint not null,
        primary key (id),
        constraint uk_orders_stock_reservation_id unique (stock_reservation_id),
        constraint ck_orders_amounts_non_negative check (items_amount >= 0 and shipping_fee_amount >= 0 and total_amount >= 0 and shipping_base_fee_amount >= 0 and shipping_remote_area_surcharge_amount >= 0 and (shipping_weight_fee_amount is null or shipping_weight_fee_amount >= 0) and (shipping_free_shipping_threshold_amount is null or shipping_free_shipping_threshold_amount >= 0)),
        constraint ck_orders_total_amount_consistent check (total_amount = items_amount + shipping_fee_amount and shipping_fee_currency = items_currency and total_currency = items_currency),
        constraint ck_orders_shipping_currency_consistent check (shipping_base_fee_currency = items_currency and shipping_remote_area_surcharge_currency = items_currency and (shipping_free_shipping_threshold_currency is null or shipping_free_shipping_threshold_currency = items_currency) and (shipping_weight_fee_currency is null or shipping_weight_fee_currency = items_currency)),
        constraint ck_orders_shipping_free_shipping_threshold_pair check ((shipping_free_shipping_threshold_amount is null and shipping_free_shipping_threshold_currency is null) or (shipping_free_shipping_threshold_amount is not null and shipping_free_shipping_threshold_currency is not null)),
        constraint ck_orders_shipping_weight_fee_pair check ((shipping_weight_fee_amount is null and shipping_weight_fee_currency is null) or (shipping_weight_fee_amount is not null and shipping_weight_fee_currency is not null)),
        constraint ck_orders_shipping_weight_grams_positive check (shipping_weight_grams is null or shipping_weight_grams >= 1),
        constraint ck_orders_shipping_international_fields check ((shipping_scope = 'DOMESTIC' and shipping_weight_grams is null and shipping_zone_name is null and shipping_weight_fee_amount is null) or (shipping_scope = 'INTERNATIONAL' and shipping_weight_grams is not null and shipping_zone_name is not null and shipping_weight_fee_amount is not null)),
        constraint ck_orders_shipping_remote_area_domestic_only check (shipping_scope = 'DOMESTIC' or (shipping_remote_area_surcharge_amount = 0 and shipping_remote_area_name is null)),
        constraint ck_orders_shipping_free_shipping_threshold_required check (shipping_free_shipping_applied = false or shipping_free_shipping_threshold_amount is not null),
        constraint ck_orders_shipping_fee_formula check ((shipping_scope = 'DOMESTIC' and shipping_fee_amount = (case when shipping_free_shipping_applied then 0 else shipping_base_fee_amount end) + shipping_remote_area_surcharge_amount) or (shipping_scope = 'INTERNATIONAL' and shipping_weight_fee_amount is not null and shipping_fee_amount = (case when shipping_free_shipping_applied then 0 else shipping_base_fee_amount + shipping_weight_fee_amount end)))
    );

    comment on table orders.orders is
        '주문 (금액 · 배송비는 주문 시점 스냅숏, 배송지는 order_shipping_address)';

    comment on column orders.orders.id is
        '주문 아이디 (Snowflake — 주문 요청 기록을 잡을 때 미리 발급). 고객에게 보이는 주문 식별자다';

    comment on column orders.orders.created_at is
        '생성 시각';

    comment on column orders.orders.updated_at is
        '수정 시각';

    comment on column orders.orders.delivered_at is
        '배송 완료 시각 (자동 구매 확정 기준, #18). 배송 완료 전이면 null';

    comment on column orders.orders.items_amount is
        '상품 합계 — Σ(단가 × 수량), 최소 화폐 단위';

    comment on column orders.orders.items_currency is
        '상품 합계 통화';

    comment on column orders.orders.member_id is
        '회원 아이디 (외부 FK: auth-service member.id)';

    comment on column orders.orders.payment_due_at is
        '결제 기한 — 재고 선점 만료 시각';

    comment on column orders.orders.shipped_at is
        '출고 시각 (송장 등록으로 배송 중이 된 시각, #18). 출고 전이면 null';

    comment on column orders.orders.shipping_base_fee_amount is
        '견적 시점 정책 기본 배송비 (해외는 기본료)';

    comment on column orders.orders.shipping_base_fee_currency is
        '기본 배송비 통화';

    comment on column orders.orders.shipping_fee_amount is
        '받은 배송비';

    comment on column orders.orders.shipping_fee_currency is
        '배송비 통화';

    comment on column orders.orders.shipping_free_shipping_applied is
        '무료배송 기준 이상이라 기본 배송비(해외는 기본료 + 무게 요금)를 받지 않았는가';

    comment on column orders.orders.shipping_free_shipping_threshold_amount is
        '견적 시점 정책의 무료배송 기준. 없으면 null';

    comment on column orders.orders.shipping_free_shipping_threshold_currency is
        '무료배송 기준 통화. 기준이 없으면 null';

    comment on column orders.orders.shipping_policy_version is
        '견적에 쓴 배송비 정책 version (외부: delivery-service)';

    comment on column orders.orders.shipping_remote_area_name is
        '도서산간 구간 이름. 아니면 null';

    comment on column orders.orders.shipping_remote_area_surcharge_amount is
        '도서산간 추가 배송비 (아니거나 해외면 0)';

    comment on column orders.orders.shipping_remote_area_surcharge_currency is
        '도서산간 추가 배송비 통화';

    comment on column orders.orders.shipping_scope is
        '견적에 쓴 배송비 정책의 배송 범위 (DOMESTIC / INTERNATIONAL)';

    comment on column orders.orders.shipping_weight_fee_amount is
        '해외 무게 요금. 국내면 null';

    comment on column orders.orders.shipping_weight_fee_currency is
        '해외 무게 요금 통화. 국내면 null';

    comment on column orders.orders.shipping_weight_grams is
        '견적에 보낸 해외 배송 총 무게(그램, Σ SKU 무게 × 수량). 국내면 null';

    comment on column orders.orders.shipping_zone_name is
        '해외 국가 그룹 이름. 국내면 null';

    comment on column orders.orders.status is
        '주문 상태 (order-flow-guide.md §1)';

    comment on column orders.orders.stock_reservation_id is
        '재고 선점 아이디 (외부 FK: product-service stock_reservation.id). 만료 뒤 재선점(#14)하면 바뀐다';

    comment on column orders.orders.total_amount is
        '결제 금액 = 상품 합계 + 배송비';

    comment on column orders.orders.total_currency is
        '결제 금액 통화';

    comment on column orders.orders.version is
        '낙관적 락 버전';

    create table orders.outbox (
        id bigint not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        aggregate_id varchar(100) not null,
        aggregate_type varchar(50) not null,
        available_at timestamp(6) with time zone not null,
        event_type varchar(100) not null,
        last_error_code varchar(100),
        payload text not null,
        publish_attempts integer not null,
        published_at timestamp(6) with time zone,
        schema_version integer not null,
        version bigint not null,
        primary key (id)
    );

    comment on table orders.outbox is
        '이벤트 발행 대기열 (transactional outbox)';

    comment on column orders.outbox.id is
        'outbox 행 아이디 (Snowflake — 시간순, 같은 키의 발행 순서)';

    comment on column orders.outbox.created_at is
        '생성 시각';

    comment on column orders.outbox.updated_at is
        '수정 시각';

    comment on column orders.outbox.aggregate_id is
        '집합 아이디 — Kafka 키(같은 키는 같은 파티션 · 순서)';

    comment on column orders.outbox.aggregate_type is
        '집합 종류 (예: order)';

    comment on column orders.outbox.available_at is
        '이 시각부터 보낸다 — 예약 · 재시도 백오프 같은 지연 발행';

    comment on column orders.outbox.event_type is
        '이벤트 종류 — 토픽 끝 토막 (예: order-expired)';

    comment on column orders.outbox.last_error_code is
        '마지막 발행 실패 코드 (예외 타입 · Kafka 오류 코드만, 메시지 원문 없음)';

    comment on column orders.outbox.payload is
        '이벤트 봉투 JSON (개인정보 원문 없음)';

    comment on column orders.outbox.publish_attempts is
        '실패한 발행 시도 수';

    comment on column orders.outbox.published_at is
        'Kafka 에 보낸 시각. 아직이면 null';

    comment on column orders.outbox.schema_version is
        '페이로드 계약 버전 (1부터)';

    comment on column orders.outbox.version is
        '낙관적 락 버전 — 발행 표시 · 실패 기록이 올린다';

    create table orders.processed_event (
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

    comment on table orders.processed_event is
        '처리한 이벤트 기록 (멱등 컨슈머 inbox)';

    comment on column orders.processed_event.id is
        '처리 기록 아이디 (Snowflake)';

    comment on column orders.processed_event.created_at is
        '생성 시각';

    comment on column orders.processed_event.updated_at is
        '수정 시각';

    comment on column orders.processed_event.consumer_group is
        '컨슈머 그룹 (예: commerce-foundry.prod.order.payment-approved)';

    comment on column orders.processed_event.event_id is
        '이벤트 아이디 (봉투 eventId)';

    comment on column orders.processed_event.event_type is
        '이벤트 종류 (예: payment-approved)';

    comment on column orders.processed_event.processed_at is
        '처리 시각 — 보존 기간 정리 기준';

    create table orders.shedlock (
        name varchar(64) not null,
        lock_until timestamp(6) with time zone not null,
        locked_at timestamp(6) with time zone not null,
        locked_by varchar(255) not null,
        primary key (name)
    );

    comment on table orders.shedlock is
        '단일 실행 락 (ShedLock — outbox 릴레이 · 정리)';

    comment on column orders.shedlock.name is
        '락 이름';

    comment on column orders.shedlock.lock_until is
        '이 시각까지 락을 쥔다';

    comment on column orders.shedlock.locked_at is
        '락을 잡은 시각';

    comment on column orders.shedlock.locked_by is
        '락을 잡은 인스턴스 (호스트 이름)';

    create index idx_claim_type_next_attempt_at_id
       on orders.claim (type, next_attempt_at, id);

    create index idx_order_item_custom_option_order_id_order_item_id
       on orders.order_item_custom_option (order_id, order_item_id);

    create index idx_order_payment_order_id_id
       on orders.order_payment (order_id, id);

    create index idx_order_request_member_id_updated_at
       on orders.order_request (member_id, updated_at);

    create index idx_order_status_history_order_id_id
       on orders.order_status_history (order_id, id);

    create index idx_orders_member_id_id
       on orders.orders (member_id, id);

    create index idx_orders_status_payment_due_at_id
       on orders.orders (status, payment_due_at, id);

    create index idx_orders_status_created_at_id
       on orders.orders (status, created_at, id);

    create index idx_orders_created_at_id
       on orders.orders (created_at, id);

    create index idx_orders_status_delivered_at_id
       on orders.orders (status, delivered_at, id);

    create index idx_outbox_published_at_available_at
       on orders.outbox (published_at, available_at);

    create index idx_processed_event_processed_at
       on orders.processed_event (processed_at);
