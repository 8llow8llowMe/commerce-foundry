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
    status varchar(20) not null check (status in ('PENDING','SUCCEEDED','REJECTED','VOIDED','EXTERNAL_PG_REFUNDED','OFF_PG_REFUNDED')),
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

위 최초 설치 DDL 은 #691 의 `VOIDED` 와 #693 의 `EXTERNAL_PG_REFUNDED` · `OFF_PG_REFUNDED` 를 이미 포함한다.
이미 원장이 있는 DB 는 아래 "#693 외부 확인 환불 상태" 스크립트 하나만 적용하면 된다 — 최종 check 가 `VOIDED` 를 포함하므로 #691 스크립트를 따로 돌릴 필요가 없다.
#691 스크립트를 이미 적용했어도 그대로 적용한다.

## #691 VOIDED 상태 (무환불 봉인)

무환불 판정(`payment-guide.md` §8-1)은 환불을 요청한 적 없는 클레임을 `VOIDED` 행으로 봉인한다. 기존 DB 의 `payment_cancel.status` check 에는 이 값이 없다.
Hibernate 가 이름 없이 만든 칼럼 check 라 PostgreSQL 기본 이름 `payment_cancel_status_check` 이다. **적용 전에 실제 이름을 catalog 로 확인한다.**

```sql
select conname, pg_get_constraintdef(oid)
  from pg_constraint
 where conrelid = 'payment.payment_cancel'::regclass and contype = 'c' and pg_get_constraintdef(oid) ilike '%status%';
```

결과는 아래 한 줄이어야 한다(PostgreSQL 이 varchar 칼럼의 `in (...)` 을 바꿔 쓴 실제 형식).

```text
payment_cancel_status_check | CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCEEDED'::character varying, 'REJECTED'::character varying])::text[])))
```

이름이 다르면 스크립트의 이름을 그 이름으로 고친 뒤 적용한다. `IF EXISTS` 로 건너뛰지 않는다.
스크립트도 BEGIN 직후 `DO` 블록으로 같은 조회를 해, status 를 다루는 check 가 정확히 1건이고 이름이 `payment_cancel_status_check` 가 아니면
`RAISE EXCEPTION` 으로 아무것도 바꾸지 않고 롤백한다. 적용 뒤에도 같은 이름 1건이라 재실행해도 통과한다.

[`payment-cancel-voided-status.sql`](../service/payment-service/src/test/resources/ddl/payment-cancel-voided-status.sql)을 `psql -v ON_ERROR_STOP=1 -f <파일>`로 적용한다.

```sql
begin;
set local lock_timeout = '2s';
set local statement_timeout = '30s';
do $$ ... $$;  -- 자기 점검: status check 가 정확히 1건 · 이름 payment_cancel_status_check 가 아니면 raise exception (전문은 파일)
alter table payment.payment_cancel drop constraint payment_cancel_status_check;
alter table payment.payment_cancel add constraint payment_cancel_status_check
    check (status in ('PENDING','SUCCEEDED','REJECTED','VOIDED'));
commit;
```

- **순서** — 새 payment-service 기동 **전**에 적용한다. dev 포함이다: `ddl-auto=update` 는 기존 enum check 를 고치지 않는다.
- **미적용 상태로 호출되면** — 봉인 INSERT 가 check 위반으로 500 이다. 원장은 바뀌지 않고, order 는 200 이 아닌 응답을 무환불 확정으로 읽지 않으므로 거절이 막힌다(안전 쪽 실패).
  판정이 PG 를 부른 뒤라 토스 GET 한 번은 이미 나갔다.
- **잠금** — `ACCESS EXCLUSIVE` 잠금과 기존 행 전체 검사가 필요하다. 잠금 대기 2초 · 문장 30초 상한이며 실패하면 롤백해 원래 제약이 남는다. 같은 최종 제약으로 다시 실행할 수 있다.
- **확인** — 적용 뒤 위 조회가 `VOIDED` 를 포함한 한 줄이고 `pg_constraint.convalidated = true` 인지 본다. Hibernate 스냅숏은 `ddl/payment-schema.sql` 이다.
- **롤백** — 이전 앱은 `VOIDED` 행을 읽으면 enum 변환에 실패한다. 봉인 행이 생긴 뒤에는 이전 앱으로 내리지 않는다. 제약을 되돌리는 스크립트는 두지 않는다 —
  `VOIDED` 행이 있으면 이전 제약을 다시 걸 수 없고, 봉인 행을 지우면 그 클레임의 환불 봉인이 풀린다.

`PaymentCancelVoidPostgresTest` 가 이전 제약 → 봉인 INSERT 실패 → 스크립트 두 번 적용 → 봉인 성공 · 모르는 상태 거절을 보고,
제약 이름이 다를 때 자기 점검이 `P0001` 로 실패하고 제약을 그대로 두는지 본다.

> #693 스크립트를 적용한 뒤에는 이 스크립트를 다시 돌리지 않는다. 돌려도 **자기 점검이 막는다** — 현재 status check 정의에 `EXTERNAL_PG_REFUNDED` 가 있으면
> `DO` 블록이 DROP 전에 `RAISE EXCEPTION`(`P0001`)으로 롤백해, 외부 확인 환불 행이 있든 없든 6값 check 가 그대로 남는다.

## #693 외부 확인 환불 상태

order 의 수동 환불 확정(#17 A3)을 payment 원장에 남기는 두 종결 상태를 `payment_cancel.status` check 에 더한다(`payment-guide.md` §8-1).

| 상태 | 뜻 | PG 잔액 | 결제 상태(`payment.status`) |
|------|----|---------|------------------------------|
| `EXTERNAL_PG_REFUNDED` | PG 상점관리자(콘솔) 등 우리 취소 호출 밖에서 PG 가 환불했다 | 줄었다 | PG 취소 누계에 넣어 전이한다 |
| `OFF_PG_REFUNDED` | PG 밖(계좌 송금 등)으로 환불했다 | 그대로다 | 바꾸지 않는다 — PG 상태의 거울 |

두 이름 모두 칼럼 폭 20자 안이라 칼럼은 바꾸지 않는다. DDL 은 check 교체 하나다.
이번 배포(PR A)에는 새 상태를 쓰는 경로가 없다 — 기록 명령은 다음 배포(internal `.../external-refund`, PR B)에서 생긴다. 그래도 DDL 은 이번 배포 전에 적용한다.

적용 전에 위 "#691 VOIDED 상태" 의 catalog 조회로 이름을 확인한다. 출발 상태는 둘 중 하나다 — 둘 다 그대로 적용한다.
아래 출력은 읽기 쉽게 줄을 나눴다. 실제 결과는 한 줄이다.

```text
-- #691 미적용 (3값)
payment_cancel_status_check | CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCEEDED'::character varying, 'REJECTED'::character varying])::text[])))
-- #691 적용 (4값)
payment_cancel_status_check | CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCEEDED'::character varying, 'REJECTED'::character varying,
  'VOIDED'::character varying])::text[])))
```

[`payment-cancel-external-refund-status.sql`](../service/payment-service/src/test/resources/ddl/payment-cancel-external-refund-status.sql)을
`psql -v ON_ERROR_STOP=1 -f <파일>`로 적용한다. 뼈대(자기 점검 `DO` 블록 · 잠금 상한 · 한 트랜잭션)는 #691 스크립트와 같다.

```sql
begin;
set local lock_timeout = '2s';
set local statement_timeout = '30s';
do $$ ... $$;  -- 자기 점검: status check 가 정확히 1건 · 이름 payment_cancel_status_check 가 아니면 raise exception (전문은 파일)
alter table payment.payment_cancel drop constraint payment_cancel_status_check;
alter table payment.payment_cancel add constraint payment_cancel_status_check
    check (status in ('PENDING','SUCCEEDED','REJECTED','VOIDED','EXTERNAL_PG_REFUNDED','OFF_PG_REFUNDED'));
commit;
```

적용 뒤 catalog 기대 출력(한 줄, `convalidated = true`):

```text
payment_cancel_status_check | CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCEEDED'::character varying, 'REJECTED'::character varying,
  'VOIDED'::character varying, 'EXTERNAL_PG_REFUNDED'::character varying, 'OFF_PG_REFUNDED'::character varying])::text[])))
```

- **순서** — 새 payment-service 기동 **전**에 적용한다(dev 포함, `ddl-auto=update` 는 enum check 를 고치지 않는다). 배포 순서는 [`deploy-guide.md` "외부 확인 환불 상태 배포 (#693)"](deploy-guide.md#외부-확인-환불-상태-배포-693).
- **재실행** — 최종 check 가 기존 값을 모두 포함하므로 새 상태 행이 생긴 뒤에도 다시 실행할 수 있다.
- **잠금** — #691 과 같다: `ACCESS EXCLUSIVE` · 기존 행 전체 검사, 잠금 대기 2초 · 문장 30초 상한, 실패하면 롤백해 원래 제약이 남는다.
- **롤백** — 외부 확인 환불 행이 **한 건이라도 생기면 이전 앱으로 내릴 수 없다.** 이전 앱은 새 값을 enum 으로 읽지 못한다 — 그 결제의 취소 원장을 읽는 경로
  (결제 후 취소 준비 · 무환불 판정 · 자동 취소)가 enum 변환 실패로 5xx 가 되고, order 는 결제 후 취소 5xx 를 미확정으로 읽어 같은 클레임을 재시도 상한까지 되풀이한다
  (상한을 넘으면 `REFUND_FAILED` · `REFUND_UNCONFIRMED`). 돈은 나가지 않지만 그 결제의 환불 · 판정이 멈춘다. 제약을 되돌리는 스크립트는 두지 않는다.
  행이 생기기 전(PR A 만 배포된 동안)에는 이전 앱으로 내려도 된다 — 넓힌 check 는 그대로 둔다.

`PaymentCancelVoidPostgresTest` 가 3값 · 4값 출발 → 새 상태 INSERT 실패 → 적용 → 새 상태 · VOIDED INSERT → 새 행 위 재실행 · 모르는 상태 거절,
#691 스크립트를 거꾸로 다시 돌렸을 때 외부 환불 행이 있든 없든 `P0001` 로 막히고 6값이 남는지, 제약 이름이 다를 때 `P0001` 로 아무것도 바꾸지 않는지 본다.

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
