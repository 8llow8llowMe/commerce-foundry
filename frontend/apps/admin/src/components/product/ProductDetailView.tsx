import { formatMoney } from '@cf/api-client';
import { sanitizeProductHtml } from '@cf/sanitize';
import Link from 'next/link';
import type { ReactNode } from 'react';
import type { CategoryNode } from '@/lib/category/types';
import {
  categoryLabels,
  customFieldRange,
  formatLocalDate,
  formatRetiredAt,
  skuOptionLabels,
  STOP_SELLING,
  VIDEO_MEDIA,
  type AdminProductDetail,
} from '@/lib/product/detail';

/**
 * 어드민 상품 상세(읽기 전용, #418). 서버 컴포넌트다 — 상세 설명 HTML 을 여기서 정제한다.
 * 저장된 값을 표시만 한다: 금액은 `formatMoney`, 상태는 서버 `name`(설명은 `title`). 아이디 · 코드 원문은 보이지 않는다(#305).
 * 은퇴 옵션 · 판매 종료 SKU 도 그대로 보이되 표시를 단다.
 */
export function ProductDetailView({
  product,
  categories,
}: {
  product: AdminProductDetail;
  categories: CategoryNode[] | null;
}) {
  const optionLabels = skuOptionLabels(product);
  const categoryRows = categoryLabels(product, categories);

  return (
    <div className="flex max-w-5xl flex-col gap-8">
      <div className="flex flex-col gap-2">
        <Link
          href="/products"
          className="w-fit text-sm text-muted-fg underline-offset-4 hover:underline"
        >
          ← 상품 목록
        </Link>
        <h1 className="text-xl font-semibold">{product.name}</h1>
        <p className="text-sm text-muted-fg">읽기 전용입니다. 상품 수정은 준비 중입니다.</p>
        <nav aria-label="상품 관련 화면" className="flex gap-3 text-sm">
          {/* 재고 · 원가 화면은 아직 고객 상세에서 SKU 를 읽어 노출 상품만 열린다(목록과 같은 규칙) */}
          {product.visible && (
            <Link href={`/stocks/${product.productId}`} className="underline underline-offset-4">
              재고
            </Link>
          )}
          {product.visible && (
            <Link href={`/sku-costs/${product.productId}`} className="underline underline-offset-4">
              원가
            </Link>
          )}
          <Link href={`/size-charts/${product.productId}`} className="underline underline-offset-4">
            사이즈표
          </Link>
        </nav>
      </div>

      <Section title="기본 정보">
        <dl className="grid grid-cols-[8rem_1fr] gap-x-4 gap-y-3 text-sm">
          <Row label="판매 상태">
            <span title={product.saleStatus.description}>{product.saleStatus.name}</span>
          </Row>
          <Row label="노출">
            {product.visible ? '노출' : <span className="text-muted-fg">숨김</span>}
          </Row>
          <Row label="판매가">{formatMoney(product.salePrice)}</Row>
          <Row label="정가">
            {product.listPrice ? formatMoney(product.listPrice) : <Empty>없음</Empty>}
          </Row>
          <Row label="1회 최대 수량">
            {product.maxQuantityPerOrder !== null ? (
              `${product.maxQuantityPerOrder}개`
            ) : (
              <Empty>제한 없음</Empty>
            )}
          </Row>
          <Row label="카테고리">
            {categoryRows === null ? (
              <Empty>카테고리 이름을 불러오지 못했습니다.</Empty>
            ) : (
              <ul className="flex flex-col gap-1">
                {categoryRows.map((row, index) => (
                  <li key={index}>
                    {row.text}
                    {row.primary && <span className="ml-2 text-muted-fg">(대표)</span>}
                  </li>
                ))}
              </ul>
            )}
          </Row>
          <Row label="요약">{product.summary ?? <Empty>없음</Empty>}</Row>
        </dl>
      </Section>

      <Section title="옵션">
        {product.optionGroups.length === 0 ? (
          <Empty>옵션이 없는 상품입니다.</Empty>
        ) : (
          <ul className="flex flex-col gap-3 text-sm">
            {product.optionGroups.map((group) => (
              <li key={group.optionGroupId}>
                <p className="font-medium">
                  {group.name}
                  {group.retiredAt && <Retired at={group.retiredAt} />}
                </p>
                <ul className="mt-1 flex flex-wrap gap-2">
                  {group.values.map((value) => (
                    <li
                      key={value.optionValueId}
                      className={
                        value.retiredAt
                          ? 'rounded-md bg-muted px-2 py-1 text-muted-fg'
                          : 'rounded-md bg-muted px-2 py-1'
                      }
                    >
                      {value.name}
                      {value.retiredAt && <Retired at={value.retiredAt} />}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        )}
      </Section>

      <Section title="SKU">
        {product.skus.length === 0 ? (
          <Empty>SKU 가 없습니다.</Empty>
        ) : (
          <div className="relative overflow-x-auto">
            <table className="w-full min-w-[40rem] text-left text-sm">
              <caption className="sr-only">SKU 목록</caption>
              <thead className="text-muted-fg">
                <tr>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    옵션
                  </th>
                  <th scope="col" className="py-2 pr-4 text-right font-medium">
                    추가금
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    판매 상태
                  </th>
                  <th scope="col" className="py-2 pr-4 text-right font-medium">
                    무게
                  </th>
                  <th scope="col" className="py-2 font-medium">
                    HS 코드
                  </th>
                </tr>
              </thead>
              <tbody>
                {product.skus.map((sku) => {
                  const label = optionLabels.get(sku.skuId);
                  const stopped = sku.saleStatus.code === STOP_SELLING;
                  return (
                    <tr
                      key={sku.skuId}
                      className={
                        stopped ? 'border-t border-border text-muted-fg' : 'border-t border-border'
                      }
                    >
                      <th scope="row" className="py-3 pr-4 font-medium">
                        {label?.text}
                        {label?.retired && (
                          <span className="ml-2 text-xs text-muted-fg">(사용 안 하는 옵션)</span>
                        )}
                      </th>
                      <td className="py-3 pr-4 text-right whitespace-nowrap">
                        {formatMoney(sku.additionalPrice)}
                      </td>
                      <td
                        className="py-3 pr-4 whitespace-nowrap"
                        title={sku.saleStatus.description}
                      >
                        {sku.saleStatus.name}
                      </td>
                      <td className="py-3 pr-4 text-right whitespace-nowrap">
                        {sku.weightGrams !== null ? `${sku.weightGrams}g` : '—'}
                      </td>
                      <td className="py-3 whitespace-nowrap">{sku.hsCode ?? '—'}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      <Section title="이미지 · 영상">
        {product.images.length === 0 ? (
          <Empty>등록된 이미지가 없습니다.</Empty>
        ) : (
          <ul className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            {product.images.map((media) => {
              const video = media.imageType.code === VIDEO_MEDIA;
              return (
                <li key={media.imageId} className="flex flex-col gap-1 text-sm">
                  {video ? (
                    <video
                      src={media.url}
                      poster={media.posterUrl ?? undefined}
                      controls
                      preload="none"
                      className="aspect-square w-full rounded-md bg-muted object-cover"
                      aria-label={media.altText ?? '상품 영상'}
                    />
                  ) : (
                    // 이미지 CDN 이 정해지지 않아 next/image 를 쓰지 않는다(메인 전시와 같다)
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                      src={media.url}
                      alt={media.altText ?? ''}
                      loading="lazy"
                      decoding="async"
                      className="aspect-square w-full rounded-md bg-muted object-cover"
                    />
                  )}
                  <span title={media.imageType.description}>{media.imageType.name}</span>
                  {media.altText && <span className="text-muted-fg">{media.altText}</span>}
                </li>
              );
            })}
          </ul>
        )}
      </Section>

      <Section title="프리오더 · 맞춤 제작">
        <dl className="grid grid-cols-[8rem_1fr] gap-x-4 gap-y-3 text-sm">
          <Row label="프리오더">{product.preorder ? '예약 판매' : <Empty>아니오</Empty>}</Row>
          {product.preorder && (
            <Row label="발송 예정일">
              {product.expectedShipDate ? (
                formatLocalDate(product.expectedShipDate)
              ) : (
                <Empty>추후 안내</Empty>
              )}
            </Row>
          )}
          <Row label="맞춤 제작">{product.customOrder ? '맞춤 제작' : <Empty>아니오</Empty>}</Row>
          {product.customOrder && (
            <>
              <Row label="소요 일수">
                {product.customOrderLeadTimeDays !== null ? (
                  `${product.customOrderLeadTimeDays}일`
                ) : (
                  <Empty>없음</Empty>
                )}
              </Row>
              <Row label="안내">
                {product.customOrderNotice ? (
                  <span className="whitespace-pre-line">{product.customOrderNotice}</span>
                ) : (
                  <Empty>없음</Empty>
                )}
              </Row>
            </>
          )}
        </dl>
        {/* 맞춤 제작을 끈 상품에도 저장된 입력 항목은 남아 있을 수 있다 — 있는 그대로 보인다 */}
        {product.customOrderFields.length > 0 && (
          <div className="relative mt-4 overflow-x-auto">
            <table className="w-full min-w-[40rem] text-left text-sm">
              <caption className="sr-only">맞춤 제작 입력 항목</caption>
              <thead className="text-muted-fg">
                <tr>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    항목
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    입력 유형
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    필수
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    범위
                  </th>
                  <th scope="col" className="py-2 font-medium">
                    도움말
                  </th>
                </tr>
              </thead>
              <tbody>
                {product.customOrderFields.map((field) => (
                  <tr key={field.fieldKey} className="border-t border-border">
                    <th scope="row" className="py-3 pr-4 font-medium">
                      {field.label}
                    </th>
                    <td className="py-3 pr-4 whitespace-nowrap" title={field.inputType.description}>
                      {field.inputType.name}
                    </td>
                    <td className="py-3 pr-4 whitespace-nowrap">
                      {field.required ? '필수' : '선택'}
                    </td>
                    <td className="py-3 pr-4 whitespace-nowrap">
                      {customFieldRange(field) ?? '—'}
                    </td>
                    <td className="py-3">{field.helpText ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      <Section title="상세 설명">
        {product.descriptionHtml ? (
          <div
            className="rounded-md border border-border p-4 text-sm leading-6 [&_a]:underline [&_h2]:mt-6 [&_h2]:text-lg [&_h2]:font-semibold [&_h3]:mt-4 [&_h3]:font-semibold [&_img]:my-4 [&_img]:h-auto [&_img]:max-w-full [&_li]:ml-5 [&_ol]:list-decimal [&_p]:my-2 [&_table]:my-4 [&_td]:border-b [&_td]:border-border [&_td]:py-1 [&_th]:border-b [&_th]:border-border [&_th]:py-1 [&_th]:text-left [&_ul]:list-disc"
            // 서버에서 허용 목록으로 거른 HTML 만 넣는다(@cf/sanitize, 스토어프론트 상세와 같은 규칙)
            dangerouslySetInnerHTML={{ __html: sanitizeProductHtml(product.descriptionHtml) }}
          />
        ) : (
          <Empty>상세 설명이 없습니다.</Empty>
        )}
      </Section>
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-base font-semibold">{title}</h2>
      {children}
    </section>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-muted-fg">{label}</dt>
      <dd>{children}</dd>
    </>
  );
}

function Empty({ children }: { children: ReactNode }) {
  return <span className="text-sm text-muted-fg">{children}</span>;
}

/** 은퇴한 옵션 그룹 · 값 표시. 기존 주문 · SKU 를 위해 남아 있고 새로 고를 수 없다 */
function Retired({ at }: { at: string }) {
  return (
    <span className="ml-1 text-xs text-muted-fg" title={`${formatRetiredAt(at)}부터 사용 안 함`}>
      (사용 안 함)
    </span>
  );
}
