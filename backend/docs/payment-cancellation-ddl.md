# 결제 취소 원장 운영 DDL (#15)

`payment.payment_cancel`은 클레임 ID 하나에 취소 요청 하나를 보관한다. PG 호출 전에 `PENDING` 행을 커밋하고, PG 성공 응답을 검증한 뒤 결제 상태와 원장 상태를 한 트랜잭션에서 확정한다. 아래 DDL은 `PaymentSchemaSnapshotTest`가 생성한 PostgreSQL 17 스키마와 맞춘 운영 적용안이다. 운영 적용은 배포 담당자가 수행한다.

## 적용 전 확인

- `payment` 스키마와 기존 `payment.payment` 테이블이 있어야 한다.
- 배포 전 백업 및 결제 취소 API 비활성 상태를 확인한다.
- `backend/service/payment-service/src/test/resources/ddl/payment-schema.sql`과 제약 정의를 비교한다.
- 운영 DDL은 테스트/개발 환경에서 먼저 적용해 `ddl-auto=validate`를 확인한다.

## DDL

아래 전체 DDL은 취소 원장이 없는 DB의 최초 설치용이다. 이미 원장 · 취소 제약이 배포된 DB에서는 다시 실행하지 않고,
[#551 제약 교체 스크립트](../service/payment-service/src/test/resources/ddl/payment-canceled-failed-fields.sql)만
`psql -v ON_ERROR_STOP=1 -f <파일>`로 적용한 뒤 애플리케이션을 배포한다. 최초 설치는 아래 보정된 제약을 포함하므로 별도 교체가 필요 없다.
기존 식은 CANCELED를 일반 상태에서 제외하고 허용 분기를 빠뜨려 모든 전액 취소 저장을 거절했다. CANCELED 허용만 보완하며 실패 메시지/코드 쌍은 유지한다.
교체 스크립트는 잠금 대기 2초 · 문장 30초 상한에서 기존 행을 즉시 검사하고, 실패 시 기존 제약을 롤백 보존한다.
`ACCESS EXCLUSIVE` 잠금과 전체 행 검사 시간을 고려해 적용하며, 같은 최종 제약으로 재실행할 수 있다. 적용 뒤 `convalidated=true`를 확인한다.

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
    and ((status = 'FAILED' and failure_code is not null) or status in ('LATE_APPROVED','CANCELED')
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

기존 제약 오류로 PG 취소는 성공했지만 DB 확정이 실패했다면 결제 상태는 이전 상태이고 원장은 `PENDING`일 수 있다.
임의 SQL로 성공 처리하거나 새 키로 PG 요청을 보내지 않는다. 기존 클레임 멱등 키로 PG 취소 내역을 먼저 대조하고 아래 재시도/수동 확인 절차를 따른다.

`PENDING`이 남으면 동일한 클레임 ID와 `Idempotency-Key`로 재시도한다. 토스 멱등키는 첫 요청부터 15일 동안만 유효하므로 애플리케이션은 생성 후 14일이 지난 `PENDING`의 PG 재호출을 막는다. 이때 PG 거래 내역을 대조해 수동으로 확정해야 한다. 다른 클레임의 취소는 미확정 요청이 해소될 때까지 막힌다.

현재 결제 오픈 게이트와 주문 클레임 측 환불 실패 상태가 연동되기 전에는 운영 API를 활성화하지 않는다. 토스 취소의 확정 거절 코드만 `REJECTED`로 기록하고, 타임아웃·5xx·처리 중·의미 불명 4xx는 `PENDING`으로 보존한다.

## #579 자동 취소 메타데이터 이행

`service/payment-service/src/test/resources/ddl/payment-auto-cancel.sql`을 운영자가 `psql -v ON_ERROR_STOP=1 -f <파일>`로 적용한다.
`payment_auto_cancel`은 금전 원장이 아닌 실행 의도·고정 PG 키·재시도·lease·거래 증거다. payment_id는 결제 ID 참조이며 **DB FK 제약은 없다**. 운영 DDL에는 due 시각과 미만료 lease가 각각 편향된 대기열을 위한 두 후보 인덱스가 포함된다.
양수 claim_id에는 nullable 유니크 제약을 둔다. enqueue는 claimId·금액을 비워 두고, 최초 acquire가 새 양수 원장 INSERT와 메타데이터 할당을 함께 커밋한다.
충돌하면 전체 롤백 후 새 트랜잭션에서만 새 ID를 시도한다. 커밋된 claimId·PG 키·금액은 변경하지 않는다. 원장 누락·불일치는 재발급 없이 수동 확인이다.
구버전 양수 전용 원장 독자와 호환되며 음수 원장 행을 생성하지 않는다. 기존 #591 커서·lane·두 seed·가드는 변경하지 않는다.

게이트 OFF와 실행자 drain을 확인한 뒤 DDL 전체를 한 트랜잭션으로 적용한다. 잠금 대기 2초·문장 30초 상한이며 실패하면 롤백 후 catalog를 확인한다.
성공한 CREATE를 무조건 재실행하지 않는다. 새 매핑 validate 및 Hibernate 생성 DDL과 전체 스냅숏·운영 스크립트의 일치 검증 뒤 배포한다.
금전 원장·의도·PG 키는 롤백 시에도 보존한다. 이전 버전으로 내릴 때는 자동 취소 게이트와 새 실행자를 먼저 끄고 drain한다.
보정 전 음수 설계는 배포된 적이 없다는 전제다. 이미 음수 행이 존재한다면 임의 ID 교체 없이 별도 거래 대조·이행 승인이 필요하다.

PENDING의 next_attempt_at·lease_until과 MANUAL_REVIEW를 관찰한다. 수동 사유는 `AUTO_CANCEL_MANUAL_REVIEW`로 고정한다.
PG 거래·정확한 요청 표식·저장된 거래 증거를 대조하기 전에는 잔액만으로 성공 처리하거나 새 키로 재청구하지 않는다.
14일 후에는 POST하지 않되 GET 확정을 허용한다. 만료 후 GET RETRY도 수동 확인으로 남기고 원장 PENDING은 보존한다.
#213 어드민 알림이나 operations-service 연동이 제공된다는 뜻은 아니다.

### 별도 커서·스케줄러 DDL

CORE 메타데이터 DDL 다음 `service/payment-service/src/test/resources/ddl/payment-auto-cancel-scheduler.sql`을 한 번 적용한다.
새 auto_cancel_cursor·세 seed(REJECTED/LATE_APPROVED/PENDING)·idx_payment_status_id만 추가한다.
기존 #591 테이블·RECONCILE/REJECTED 두 seed·가드는 그대로다. 구 스키마 이행 시험은 두 seed의 진행값과 원장/의도를 보존하고 양쪽 가드를 확인한다.

단일 `payment.auto-cancel.enabled=false`·진행 중 실행자 drain → 백업 → metadata DDL → scheduler DDL → 매핑 validate·생성 스냅숏 일치 검증 → 전체 배포 순서다.
새 인덱스 생성은 payment 쓰기를 막을 수 있어 승인·취소·웹훅·복구 쓰기를 drain한 작업 창에서 수행한다.
실패 시 전체 롤백 후 catalog를 확인한다. 성공한 CREATE 재실행은 실패하도록 하며 진행값을 초기화하지 않는다.
새 DB 전체 스냅숏에는 seed가 없으므로 운영 스크립트의 세 seed INSERT만 별도 적용한다.

운영 선행 조건과 사용자 승인 뒤 단일 게이트를 켠다. 기본 OFF이며 별도 scheduler.enabled는 없다.
기본 1분 주기·lane당 5건·락 15분이다. 세 lane 전체의 명목 예산을 검증하지만 벽시계 보장은 아니며 중복 안전성은 의도 token과 PG 멱등 키에 의존한다.
게이트 ON에서는 Hibernate none 환경도 새 커서/세 seed·양수 claim 칼럼·원장·shedlock 누락으로 기동 실패한다.
롤백은 게이트 OFF 후 drain하고 양수 원장·의도·커서 진행·고정 키를 보존한다. 구버전 #591 exact-two-seed 검사는 그대로 통과한다.
#213 어드민 알림·operations-service 호출은 제공하지 않는다. 저장된 고정 수동 사유와 원문 없는 스케줄러 실패 로그로 확인한다.

수동 확인은 MANUAL_REVIEW 영속 상태와 고정 사유 `AUTO_CANCEL_MANUAL_REVIEW`로 남긴다.
최초 전이 커밋 후 같은 고정 코드 로그와 태그 없는 `commerce.payment.auto-cancel.manual-review` counter를 기록한다.
롤백·같은 완료의 재호출은 counter를 늘리지 않는다. 커밋 직후 프로세스 중단으로 로그/counter가 유실될 수 있으므로 정확한 누적 사건 원장으로 쓰지 않는다.
gauge 질의는 read-only 트랜잭션 timeout 2초와 JPA query timeout 2000ms를 함께 적용한다. 풀 대기나 전체 scrape의 2초 벽시계 상한을 보장하지 않는다.
`commerce.payment.auto-cancel.manual-review.pending` gauge는 DB의 MANUAL_REVIEW 건수를 다시 조회해 재기동 후에도 대상을 보여 준다.
조회 실패는 0 대신 NaN이며 ID·PG 키·원문·cause를 metric 태그나 로그에 넣지 않는다. 이는 **#213 어드민 알림 전달이 아니다**.
