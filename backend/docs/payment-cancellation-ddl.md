# 결제 취소 원장 운영 DDL (#15)

`payment.payment_cancel`은 클레임 ID 하나에 취소 요청 하나를 보관한다. PG 호출 전에 `PENDING` 행을 커밋하고, PG 성공 응답을 검증한 뒤 결제 상태와 원장 상태를 한 트랜잭션에서 확정한다. 아래 DDL은 `PaymentSchemaSnapshotTest`가 생성한 PostgreSQL 17 스키마와 맞춘 운영 적용안이다. 운영 적용은 배포 담당자가 수행한다.

## 적용 전 확인

- `payment` 스키마와 기존 `payment.payment` 테이블이 있어야 한다.
- 배포 전 백업 및 결제 취소 API 비활성 상태를 확인한다.
- `backend/service/payment-service/src/test/resources/ddl/payment-schema.sql`과 제약 정의를 비교한다.
- 운영 DDL은 테스트/개발 환경에서 먼저 적용해 `ddl-auto=validate`를 확인한다.

## DDL

```sql
begin;

create table payment.payment_cancel (
    claim_id bigint not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    amount bigint not null,
    currency varchar(3) not null check (currency in ('KRW','USD')),
    payment_id bigint not null,
    status varchar(20) not null check (status in ('PENDING','SUCCEEDED','REJECTED')),
    version bigint,
    primary key (claim_id),
    constraint ck_payment_cancel_amount_positive check (amount > 0)
);

comment on column payment.payment_cancel.created_at is '생성 시각';
comment on column payment.payment_cancel.updated_at is '수정 시각';

create index idx_payment_cancel_payment_id on payment.payment_cancel (payment_id);

alter table payment.payment drop constraint ck_payment_active_order_id_status;
alter table payment.payment add constraint ck_payment_active_order_id_status check (
    (status in ('FAILED','ABORTED','LATE_APPROVED') and active_order_id is null)
    or (status = 'CANCELED' and (failure_code is not null or abort_reason is not null) and active_order_id is null)
    or (status not in ('FAILED','ABORTED','LATE_APPROVED')
        and not (status = 'CANCELED' and (failure_code is not null or abort_reason is not null))
        and active_order_id is not null and active_order_id = order_id)
);

alter table payment.payment drop constraint ck_payment_failed_fields;
alter table payment.payment add constraint ck_payment_failed_fields check (
    (failure_message is null or failure_code is not null)
    and ((status = 'FAILED' and failure_code is not null) or status = 'LATE_APPROVED'
        or (status not in ('FAILED','LATE_APPROVED','CANCELED') and failure_code is null))
);

alter table payment.payment drop constraint ck_payment_abort_reason_status;
alter table payment.payment add constraint ck_payment_abort_reason_status check (
    (status = 'ABORTED' and abort_reason is not null) or status in ('LATE_APPROVED','CANCELED')
    or (status not in ('ABORTED','LATE_APPROVED','CANCELED') and abort_reason is null)
);

commit;
```

## 운영 관측

`PENDING`이 남으면 동일한 클레임 ID와 `Idempotency-Key`로 재시도한다. 토스 멱등키는 첫 요청부터 15일 동안만 유효하므로 애플리케이션은 생성 후 14일이 지난 `PENDING`의 PG 재호출을 막는다. 이때 PG 거래 내역을 대조해 수동으로 확정해야 한다. 다른 클레임의 취소는 미확정 요청이 해소될 때까지 막힌다.

현재 결제 오픈 게이트와 주문 클레임 측 환불 실패 상태가 연동되기 전에는 운영 API를 활성화하지 않는다. 토스 취소의 확정 거절 코드만 `REJECTED`로 기록하고, 타임아웃·5xx·처리 중·의미 불명 4xx는 `PENDING`으로 보존한다.
