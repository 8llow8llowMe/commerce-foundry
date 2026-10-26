import type { SliceResponse } from '@cf/api-client';
import type { ProductPickSection as ProductPickSectionData } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import { ProductCard } from '@/components/product/ProductCard';
import { productsApiPath } from '@/lib/catalog/shop-query';
import type { ProductListItem } from '@/lib/catalog/types';
import { linkRel, type DisplayProductPickSection } from '@/lib/display/home-sections';
import { serverFetch } from '@/lib/server-api';
import { SectionHeading, sectionLabel } from './SectionHeading';

/** 브랜드 설정의 상품 픽 — 상품 목록 API 에서 고른다. 못 받으면 ApiError 를 던진다 */
async function fetchPickProducts(
  section: ProductPickSectionData,
  fetchLocale: string | undefined,
): Promise<ProductListItem[]> {
  const path = productsApiPath(
    { categoryId: section.source.categoryId, sort: 'NEWEST' },
    0,
    section.source.limit,
  );
  const slice = await (fetchLocale
    ? serverFetch<SliceResponse<ProductListItem>>(
        path,
        { headers: { 'accept-language': fetchLocale } },
        { anonymous: true },
      )
    : serverFetch<SliceResponse<ProductListItem>>(path));
  return slice.contents.slice(0, section.source.limit);
}

/**
 * 02 대표 상품. 메인 전시 API 의 상품 픽은 응답의 상품 카드(목록 항목과 같은 모양)를 그대로 그리고,
 * 브랜드 설정(기본)이면 상품 목록 API 에서 고른다 — 어느 쪽이든 이름 · 가격을 설정에 적지 않는다.
 * 못 받았거나 비었으면 메인을 깨뜨리지 않고 섹션을 그리지 않는다.
 * PC 4열은 짝수 번째를 80px 내려 같은 카드가 줄지어 있는 느낌을 피하고, 모바일은 72vw 가로 스와이프(다음 카드가 보이게)다(#59).
 */
export async function ProductPickSection({
  section,
  fetchLocale,
}: {
  section: ProductPickSectionData | DisplayProductPickSection;
  /** 요청 언어(root params)를 읽을 수 없는 서버 액션(메인 미리보기)에서 설정 상품 픽을 부를 언어 */
  fetchLocale?: string;
}) {
  let products: ProductListItem[];
  if ('products' in section) {
    products = section.products;
  } else {
    try {
      products = await fetchPickProducts(section, fetchLocale);
    } catch {
      return null;
    }
  }
  if (products.length === 0) return null;

  const headingId = `${section.id}-title`;
  return (
    <section
      aria-labelledby={sectionLabel(headingId, section.title)}
      className="mx-auto w-full max-w-screen-xl px-4 lg:px-8"
    >
      <div className="mb-8 flex items-end justify-between gap-4 lg:mb-12">
        <SectionHeading id={headingId} title={section.title} />
        {section.viewAll && (
          <Link
            href={section.viewAll.href}
            rel={linkRel(section.viewAll.href)}
            className="ml-auto shrink-0 border-b border-current pb-1 text-xs tracking-[0.2em] uppercase hover:opacity-70"
          >
            {section.viewAll.label} <span aria-hidden>→</span>
          </Link>
        )}
      </div>
      {/* relative: 카드 안 sr-only(absolute)가 스크롤 상자 밖으로 새어 페이지 가로 스크롤을 만들지 않게 */}
      <ul
        data-reveal="media"
        className="relative -mx-4 flex snap-x snap-mandatory scroll-px-4 gap-4 overflow-x-auto px-4 lg:mx-0 lg:grid lg:grid-cols-4 lg:gap-x-6 lg:overflow-visible lg:px-0 lg:[&>li:nth-child(even)]:mt-20"
      >
        {products.map((product) => (
          <li key={product.productId} className="w-[72vw] shrink-0 snap-start lg:w-auto">
            <ProductCard product={product} />
          </li>
        ))}
      </ul>
    </section>
  );
}
