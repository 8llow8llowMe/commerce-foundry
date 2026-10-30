import type { LookSection as LookSectionData } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import { LookPicker } from '@/components/product/look/LookPicker';
import { fetchLookProducts } from '@/lib/catalog/look-products';
import { linkRel } from '@/lib/display/home-sections';
import { MediaFrame } from './MediaFrame';
import { SectionHeading, sectionLabel } from './SectionHeading';

/**
 * 04 Shop the Look. 착장 화보 + 구성 상품 — 메인 전시 API 의 LOOK 섹션(#419) 또는 브랜드 설정의 LOOK 이다.
 * 구성품은 옵션 · SKU 가 필요해 상품 상세 API 로 받는다. 못 받은 상품은 빼고, 하나도 없으면 화보와 링크만 둔다.
 * 제목이 없으면(전시 API 의 제목은 선택) 제목과 aria-labelledby 를 함께 뺀다.
 * 구성품 목록 · 함께 담기는 상품 상세 SHOP THE LOOK 과 같은 LookPicker 다.
 */
export async function LookSection({
  section,
  fetchLocale,
}: {
  section: LookSectionData;
  /** 요청 언어(root params)를 읽을 수 없는 서버 액션(메인 미리보기)에서 구성품을 부를 언어 */
  fetchLocale?: string;
}) {
  const found = await fetchLookProducts(section.productIds, fetchLocale);
  const products = section.productIds.flatMap((id) => found.get(id) ?? []);
  const headingId = `${section.id}-title`;

  return (
    <section
      aria-labelledby={sectionLabel(headingId, section.title)}
      className="mx-auto grid w-full max-w-screen-xl gap-10 px-4 lg:grid-cols-12 lg:gap-x-8 lg:px-8"
    >
      {/* 모바일은 전폭 사진, PC 는 7열 큰 사진이 목록을 내리는 동안 고정된다 */}
      <MediaFrame
        slot={section.media}
        className="-mx-4 aspect-[4/5] lg:sticky lg:top-[calc(var(--site-header-h)+1.5rem)] lg:col-span-7 lg:mx-0 lg:self-start"
      />

      <div className="flex flex-col gap-6 lg:col-span-5 lg:self-center">
        <SectionHeading id={headingId} title={section.title} />
        {section.body && (
          <p data-reveal="text" className="font-serif leading-8">
            {section.body}
          </p>
        )}

        {products.length > 0 && <LookPicker products={products} floatingAction />}

        {section.link && (
          <Link
            href={section.link.href}
            rel={linkRel(section.link.href)}
            className="self-start border-b border-current pb-1 text-xs tracking-[0.2em] uppercase hover:opacity-70"
          >
            {section.link.label} <span aria-hidden>→</span>
          </Link>
        )}
      </div>
    </section>
  );
}
