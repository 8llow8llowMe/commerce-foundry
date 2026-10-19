import { formatMoney } from '@cf/api-client';
import type { LookSection as LookSectionData } from '@cf/ui';
import Link from 'next/link';
import type { ProductDetail } from '@/lib/catalog/types';
import { serverFetch } from '@/lib/server-api';
import { MediaFrame } from './MediaFrame';
import { SectionHeading } from './SectionHeading';

/**
 * 04 Shop the Look. 착장 화보 + 구성 상품. 구성품은 상품 상세 API 로 받아 이름 · 가격 · 판매 상태를 보인다
 * (룩 API #62 전 1차). 못 받은 상품은 빼고, 하나도 없으면 화보와 전체 보기 링크만 둔다.
 * '함께 담기'는 장바구니(#26) 이후다 — 지금은 각 상품으로 이동한다.
 */
export async function LookSection({ section }: { section: LookSectionData }) {
  const results = await Promise.allSettled(
    section.productIds.map((id) => serverFetch<ProductDetail>(`/api/v1/products/${id}`)),
  );
  const products = results.flatMap((result) =>
    result.status === 'fulfilled' ? [result.value] : [],
  );
  const headingId = `${section.id}-title`;

  return (
    <section
      aria-labelledby={headingId}
      className="mx-auto grid w-full max-w-screen-xl gap-10 px-4 lg:grid-cols-2 lg:items-center lg:gap-16 lg:px-8"
    >
      <MediaFrame slot={section.media} className="aspect-[4/5]" />

      <div className="flex flex-col gap-6">
        <SectionHeading id={headingId} title={section.title} />
        {section.body && <p className="font-serif leading-8">{section.body}</p>}

        {products.length > 0 && (
          <ul className="flex flex-col border-t border-border">
            {products.map((product) => (
              <li key={product.productId} className="border-b border-border">
                <Link
                  href={`/products/${product.productId}`}
                  className="group flex items-center gap-4 py-4"
                >
                  <div className="aspect-[3/4] w-16 shrink-0 bg-muted">
                    {product.thumbnail && (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img
                        src={product.thumbnail.url}
                        alt=""
                        loading="lazy"
                        decoding="async"
                        className="size-full object-cover"
                      />
                    )}
                  </div>
                  <div className="flex min-w-0 flex-1 flex-col gap-1 text-sm">
                    <span className="truncate group-hover:underline">{product.name}</span>
                    <span className="text-muted-fg">
                      {formatMoney(product.salePrice)}
                      {product.saleStatus.code !== 'ON_SALE' && ` · ${product.saleStatus.name}`}
                    </span>
                  </div>
                  <span aria-hidden className="text-xs">
                    →
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}

        {section.link && (
          <Link
            href={section.link.href}
            className="self-start border-b border-current pb-1 text-xs tracking-[0.2em] uppercase hover:opacity-70"
          >
            {section.link.label} <span aria-hidden>→</span>
          </Link>
        )}
      </div>
    </section>
  );
}
