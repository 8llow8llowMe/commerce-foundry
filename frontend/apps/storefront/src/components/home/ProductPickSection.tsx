import type { SliceResponse } from '@cf/api-client';
import type { ProductPickSection as ProductPickSectionData } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import { ProductCard } from '@/components/product/ProductCard';
import { productsApiPath } from '@/lib/catalog/shop-query';
import type { ProductListItem } from '@/lib/catalog/types';
import { serverFetch } from '@/lib/server-api';
import { SectionHeading } from './SectionHeading';

/**
 * 02 대표 상품. 상품 목록 API 에서 고른다 — 이름 · 가격을 설정에 적지 않는다.
 * 못 받았거나 비었으면 메인을 깨뜨리지 않고 섹션을 그리지 않는다.
 * PC 4열은 짝수 번째를 80px 내려 같은 카드가 줄지어 있는 느낌을 피하고, 모바일은 72vw 가로 스와이프(다음 카드가 보이게)다(#59).
 */
export async function ProductPickSection({ section }: { section: ProductPickSectionData }) {
  let products: ProductListItem[];
  try {
    const slice = await serverFetch<SliceResponse<ProductListItem>>(
      productsApiPath(
        { categoryId: section.source.categoryId, sort: 'NEWEST' },
        0,
        section.source.limit,
      ),
    );
    products = slice.contents.slice(0, section.source.limit);
  } catch {
    return null;
  }
  if (products.length === 0) return null;

  const headingId = `${section.id}-title`;
  return (
    <section aria-labelledby={headingId} className="mx-auto w-full max-w-screen-xl px-4 lg:px-8">
      <div className="mb-8 flex items-end justify-between gap-4 lg:mb-12">
        <SectionHeading id={headingId} title={section.title} />
        {section.viewAll && (
          <Link
            href={section.viewAll.href}
            className="shrink-0 border-b border-current pb-1 text-xs tracking-[0.2em] uppercase hover:opacity-70"
          >
            {section.viewAll.label} <span aria-hidden>→</span>
          </Link>
        )}
      </div>
      {/* relative: 카드 안 sr-only(absolute)가 스크롤 상자 밖으로 새어 페이지 가로 스크롤을 만들지 않게 */}
      <ul className="relative -mx-4 flex snap-x snap-mandatory scroll-px-4 gap-4 overflow-x-auto px-4 lg:mx-0 lg:grid lg:grid-cols-4 lg:gap-x-6 lg:overflow-visible lg:px-0 lg:[&>li:nth-child(even)]:mt-20">
        {products.map((product) => (
          <li key={product.productId} className="w-[72vw] shrink-0 snap-start lg:w-auto">
            <ProductCard product={product} />
          </li>
        ))}
      </ul>
    </section>
  );
}
