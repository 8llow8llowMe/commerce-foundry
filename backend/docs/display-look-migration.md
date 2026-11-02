# 메인 LOOK의 룩 참조 전환 (#372)

LOOK 섹션의 위치 · 노출 기간은 전시 관리가 소유하고, 화보 · 구성 상품은 룩 관리가 소유한다.
`display_item.look_id`는 룩 ID만 저장하며 룩 내용을 복제하지 않는다. 이 문서는 수동 운영 절차이며 자동 데이터 변환은 없다.

## 계약과 배포 순서

- 어드민 LOOK 저장/미리보기는 `items:[{itemType:"LOOK",lookId:"..."}]`(1~10개)로 바뀐다. 기존 MEDIA/PRODUCT 혼합 저장은 400 `PRODUCT_047`이다.
- 어드민 조회의 LOOK 아이템은 `lookId` 문자열을 반환한다. 룩이 삭제되어도 참조 ID는 남으며 운영자가 교체/삭제한다.
- 고객과 미리보기는 `item.look` 안의 이미지 · 상품 카드를 그린다. 기존 FE의 MEDIA/PRODUCT 필터로는 새 룩을 표시하지 못한다.
  어드민 유형/개수 검증 · 룩 선택과 storefront `LookSection`의 새 payload 소비를 BE와 함께 배포해야 한다. FE 리뷰어는 `seonghoho`다.
- 구 BE는 `DisplayItemType.LOOK`을 읽을 수 없다. 전시 쓰기를 잠시 중지하고 아래 DDL을 적용한 뒤 구 BE 인스턴스를 모두 종료하고 새 BE/FE로 전환한다.
  구/신 BE가 함께 실행되는 동안 새 LOOK 아이템을 저장하지 않는다.
- 새 BE에서 이관 전 LOOK은 어드민에만 보존되고 고객 응답에서 섹션 전체가 빠진다. 전환 완료까지의 비노출 구간을 배포 일정에 포함한다.

## 기존 LOOK 이관

1. 전시 목록/단건 API 원문과 DB의 `display_section` · `display_item`을 백업한다. 섹션 ID · 배열 순서 · 미디어/상품 순서 · 문구 · 기간을 함께 남긴다.
2. 각 구 LOOK의 실제 착장을 운영자가 확인한다. 미디어 여러 장/영상/여러 상품은 룩 대표 이미지 한 장과 1~20개 구성 상품으로 자동 대응할 수 없다.
   대표 정지 화보와 상품 묶음을 선택해 `POST /api/v1/admin/looks`로 룩을 만든다. 여러 착장이면 여러 룩으로 나눈다.
   영상/PC·모바일 화보/캡션 등 룩 모델이 보관하지 않는 원문은 백업에 유지하거나 별도 STORY/DETAIL에 옮긴다. 암묵적으로 버리지 않는다.
3. 구 섹션 ID에 `PUT /api/v1/admin/displays/sections/{sectionId}`를 보낸다. 제목 · 링크 · 노출 기간을 유지하고 아이템만 LOOK 참조로 교체한다.
   섹션 ID와 섹션 순서는 유지된다. 아이템 ID는 기존 전체 교체 규칙대로 새로 발급된다. 불필요한 구 LOOK은 명시적으로 DELETE한다.
4. 룩 기능 플래그(`store.features.looks`)와 룩 노출을 확인한다. 고객 메인과 룩 상세의 화보/구성 상품, 미리보기, 숨김·삭제 상품 제외를 확인한다.
   공유 캐시는 최대 60초 동안 이전 응답을 보일 수 있다.
5. 이관 대상이 모두 처리됐는지 아래 사전 조회를 다시 실행하고 전시 쓰기를 재개한다.

룩 생성은 성공했으나 섹션 PUT이 실패하면 기존 섹션은 트랜잭션 롤백으로 보존된다. 새 룩은 남으므로 해당 ID로 PUT을 재시도한다.
룩 생성 직후 또는 존재 검증 직후 삭제되는 경쟁은 참조 행을 지우지 않는다. 고객에서는 제외하고 어드민에서 ID를 보고 다시 연결한다.
구 BE로 롤백하려면 새 LOOK 아이템부터 백업 상태로 복원해야 한다. 새 enum이 저장된 채 구 BE만 재배포하면 읽기가 실패한다.
복원은 전시 쓰기를 중지한 상태에서 운영자가 백업과 대조해 수행하며, 신규 데이터 삭제/덮어쓰기를 자동으로 실행하지 않는다.

## 운영 DDL

엔티티의 Hibernate PostgreSQL 스키마 스냅샷(`src/test/resources/ddl/product-schema.sql`) 변경부를 ALTER로 옮긴 것이다.
DB에 직접 적용한 결과나 `pg_dump` 출력은 아니다. 운영에서는 사용자가 백업 후 적용한다.
이미 존재하는 테이블의 check 제약은 `ddl-auto=update`가 갱신하지 않으므로 아래 교체가 필요하다.

사전 조회: 이관 대상과 실제 enum check 이름/정의를 확인한다. 기본 스키마 이름은 `product`다.

```sql
select s.id, s.display_order, s.visible, i.item_type, i.id as item_id
from product.display_section s
join product.display_item i on i.section_id = s.id
where s.section_type = 'LOOK' and i.item_type <> 'LOOK'
order by s.display_order, s.id, i.display_order;

select c.conname, pg_get_constraintdef(c.oid)
from pg_constraint c
where c.conrelid = 'product.display_item'::regclass and c.contype = 'c';
```

아래 트랜잭션은 `look_id`가 없는 구 스키마를 대상으로 한 번 실행한다. 이미 적용한 경우 반복 실행하지 않는다.
enum check는 Hibernate가 생성한 이름이 환경마다 다를 수 있어 `item_type` 단일 컬럼 check를 정확히 하나 찾고 같은 이름으로 교체한다.
사전 조회에서 해당 제약이 MEDIA/PRODUCT enum check인지 먼저 대조한다. 0개/여러 개면 자동 추측하지 않고 실패한다.

```sql
set client_encoding = 'UTF8';
begin;
set local lock_timeout = '5s';

alter table product.display_item add column look_id bigint;
comment on column product.display_item.look_id is '룩 아이디 (FK: look.id). 룩 아이템만';
comment on column product.display_item.item_type is '아이템 유형 (MEDIA · PRODUCT · LOOK)';
comment on table product.display_item is '메인 전시 아이템 (미디어 · 상품 또는 룩 참조)';

do $$
declare
    enum_checks text[];
begin
    select array_agg(c.conname::text) into enum_checks
    from pg_constraint c
    where c.conrelid = 'product.display_item'::regclass and c.contype = 'c'
      and c.conkey = array[(select attnum from pg_attribute
          where attrelid = 'product.display_item'::regclass and attname = 'item_type')]::smallint[];
    if coalesce(cardinality(enum_checks), 0) <> 1 then
        raise exception 'Expected exactly one item_type enum check; inspect pg_constraint before migration';
    end if;
    execute format('alter table product.display_item drop constraint %I', enum_checks[1]);
    execute format('alter table product.display_item add constraint %I check (item_type in (''MEDIA'',''PRODUCT'',''LOOK''))', enum_checks[1]);
end $$;

alter table product.display_item drop constraint ck_display_item_media_fields;
alter table product.display_item add constraint ck_display_item_media_fields
    check (item_type <> 'MEDIA' or (media_type is not null and pc_url is not null and product_id is null and look_id is null));

alter table product.display_item drop constraint ck_display_item_product_fields;
alter table product.display_item add constraint ck_display_item_product_fields
    check (item_type <> 'PRODUCT' or (product_id is not null and media_type is null and pc_url is null and mobile_url is null
        and pc_poster_url is null and mobile_poster_url is null and alt_text is null and look_id is null));

alter table product.display_item add constraint ck_display_item_look_fields
    check (item_type <> 'LOOK' or (look_id is not null and look_id > 0 and product_id is null and media_type is null
        and pc_url is null and mobile_url is null and pc_poster_url is null and mobile_poster_url is null and alt_text is null));

commit;
```

`look_id`에 DB FK를 추가하지 않는다. 삭제된 룩의 참조를 어드민에 보존하는 정책이다. 역방향 조회가 없어 단독 인덱스도 만들지 않는다.
DDL은 데이터 행을 변환하거나 삭제하지 않는다. 실패하면 전체 DDL 트랜잭션을 롤백하고 원인을 해결한 뒤 다시 적용한다.
