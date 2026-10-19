import type { LookSection as LookSectionData } from '@cf/ui';
import Link from 'next/link';
import { LookPicker } from '@/components/product/look/LookPicker';
import { fetchLookProducts } from '@/lib/catalog/look-products';
import { MediaFrame } from './MediaFrame';
import { SectionHeading } from './SectionHeading';

/**
 * 04 Shop the Look. 착장 화보 + 구성 상품. 구성품은 상품 상세 API 로 받아 옵션까지 고르게 한다
 * (룩 API #62 전 1차). 못 받은 상품은 빼고, 하나도 없으면 화보와 전체 보기 링크만 둔다.
 * 구성품 목록 · 함께 담기는 상품 상세 SHOP THE LOOK 과 같은 LookPicker 다.
 */
export async function LookSection({ section }: { section: LookSectionData }) {
  const found = await fetchLookProducts(section.productIds);
  const products = section.productIds.flatMap((id) => found.get(id) ?? []);
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

        {products.length > 0 && <LookPicker products={products} />}

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
