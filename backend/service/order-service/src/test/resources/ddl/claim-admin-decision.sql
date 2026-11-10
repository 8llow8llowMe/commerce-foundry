-- #17 A1: Hibernate PostgreSQL 17 생성 스냅숏의 신규 열 · check · comment를 기존 테이블에 적용한다.
-- 운영자가 기존 #16 취소 DDL 적용을 확인한 뒤 실행한다. 운영 자동 적용 · 관리자 처리 연결은 없다.
-- 재실행하지 않는다. 실패하면 전체 롤백 후 원인을 확인한다.
set client_encoding = 'UTF8';
begin;

alter table orders.claim add column decided_at timestamp(6) with time zone;
alter table orders.claim add column decided_by_member_id bigint;
alter table orders.claim add column decision_failure_code varchar(40);
alter table orders.claim add column decision_reason varchar(200);
alter table orders.claim add constraint ck_claim_admin_decision check (
    (decision_reason is null and decided_by_member_id is null and decided_at is null and decision_failure_code is null)
    or (type = 'CANCELLATION' and decision_reason is not null and length(trim(decision_reason)) > 0
        and decided_by_member_id is not null and decided_by_member_id > 0 and decided_at is not null
        and decision_failure_code is not null and length(trim(decision_failure_code)) > 0)
);
comment on column orders.claim.decided_at is '마지막 운영자 수습 결정 시각';
comment on column orders.claim.decided_by_member_id is '수습 결정 관리자 아이디 (외부 FK: auth-service member.id)';
comment on column orders.claim.decision_failure_code is '마지막 수습 결정 직전 실패 코드';
comment on column orders.claim.decision_reason is '마지막 운영자 수습 사유. 개인정보 입력 금지';

commit;
