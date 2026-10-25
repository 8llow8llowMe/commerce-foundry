'use client';

import { cx } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import { useDictionary, useFormatMoney } from '@/components/i18n/LocaleProvider';
import type { ProductListItem } from '@/lib/catalog/types';

/**
 * 목록의 상품 하나. 큰 이미지 + 작은 상품명 · 가격, 테두리 · 그림자 없이 여백으로 나눈다(#60).
 * 두 번째 이미지(`hoverImage`)가 있으면 hover · 키보드 포커스 때 그 이미지로 바꾸고, 한 장이면 살짝 확대만 한다.
 * 금액은 서버 값을 표시만 한다. 메인(서버)과 목록 무한 스크롤(클라이언트) 양쪽에서 쓰여 클라이언트 컴포넌트다.
 */
export function ProductCard({ product }: { product: ProductListItem }) {
  const { thumbnail, saleStatus } = product;
  const hoverImage = thumbnail ? (product.hoverImage ?? null) : null;
  const available = saleStatus.code === 'ON_SALE';
  const dict = useDictionary();
  const formatMoney = useFormatMoney();

  return (
    <Link href={`/products/${product.productId}`} className="group block">
      <div className="relative aspect-[3/4] overflow-hidden bg-muted">
        {thumbnail ? (
          // 이미지 CDN 이 정해지지 않아 next/image(remotePatterns · 최적화)를 아직 쓰지 않는다.
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={thumbnail.url}
            alt={thumbnail.altText ?? product.name}
            width={thumbnail.width ?? undefined}
            height={thumbnail.height ?? undefined}
            loading="lazy"
            decoding="async"
            className={cx(
              'size-full object-cover transition duration-700 motion-reduce:transition-none',
              !hoverImage && 'group-hover:scale-[1.03]',
              !available && 'opacity-60',
            )}
          />
        ) : (
          <span className="absolute inset-0 flex items-center justify-center text-xs text-muted-fg">
            {dict.product.imagePending}
          </span>
        )}
        {hoverImage && (
          // 대표 이미지 alt 가 상품을 설명하므로 장식으로 둔다
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={hoverImage.url}
            alt=""
            width={hoverImage.width ?? undefined}
            height={hoverImage.height ?? undefined}
            loading="lazy"
            decoding="async"
            className={cx(
              'absolute inset-0 size-full object-cover opacity-0 transition-opacity duration-500 group-hover:opacity-100 group-focus-visible:opacity-100 motion-reduce:transition-none',
              !available && 'group-hover:opacity-60 group-focus-visible:opacity-60',
            )}
          />
        )}
      </div>

      {/* 모바일 13px · 이름 한 줄 말줄임, PC 14px · 두 줄까지 (#60) */}
      <div className="mt-3 flex flex-col gap-1 text-xs lg:text-sm">
        <p className="truncate lg:line-clamp-2 lg:whitespace-normal">{product.name}</p>
        <p className="flex flex-wrap items-baseline gap-x-2">
          {product.discountRate !== null && (
            <span className="font-medium">
              <span className="sr-only">{dict.price.discountRate} </span>
              {product.discountRate}%
            </span>
          )}
          <span>
            {formatMoney(product.salePrice)}
            {product.hasOptionSurcharge && (
              <>
                <span aria-hidden>~</span>
                <span className="sr-only"> {dict.price.from}</span>
              </>
            )}
          </span>
          {product.listPrice && (
            <del className="text-xs text-muted-fg">
              <span className="sr-only">{dict.price.listPrice} </span>
              {formatMoney(product.listPrice)}
            </del>
          )}
          {/* 판매 상태는 이미지 위 뱃지가 아니라 가격 줄 뒤 글자로(#60) */}
          {!available && <span className="text-muted-fg">· {saleStatus.name}</span>}
        </p>
      </div>
    </Link>
  );
}
