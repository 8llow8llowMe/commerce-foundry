import { formatMoney } from '@cf/api-client';
import { cx } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import type { ProductListItem } from '@/lib/catalog/types';

/**
 * 목록의 상품 하나. 큰 이미지 + 작은 상품명 · 가격, 테두리 · 그림자 없이 여백으로 나눈다(#60).
 * 금액은 서버 값을 표시만 한다.
 */
export function ProductCard({ product }: { product: ProductListItem }) {
  const { thumbnail, saleStatus } = product;
  const available = saleStatus.code === 'ON_SALE';

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
              'size-full object-cover transition duration-700 group-hover:scale-[1.03]',
              !available && 'opacity-60',
            )}
          />
        ) : (
          <span className="absolute inset-0 flex items-center justify-center text-xs text-muted-fg">
            이미지 준비 중
          </span>
        )}
        {!available && (
          <span className="absolute top-3 left-3 bg-bg px-2 py-1 text-xs tracking-[0.15em]">
            {saleStatus.name}
          </span>
        )}
      </div>

      <div className="mt-3 flex flex-col gap-1 text-sm">
        <p className="line-clamp-2">{product.name}</p>
        <p className="flex flex-wrap items-baseline gap-x-2">
          {product.discountRate !== null && (
            <span className="font-medium">
              <span className="sr-only">할인율 </span>
              {product.discountRate}%
            </span>
          )}
          <span>
            {formatMoney(product.salePrice)}
            {product.hasOptionSurcharge && (
              <>
                <span aria-hidden>~</span>
                <span className="sr-only"> 부터</span>
              </>
            )}
          </span>
          {product.listPrice && (
            <del className="text-xs text-muted-fg">
              <span className="sr-only">정가 </span>
              {formatMoney(product.listPrice)}
            </del>
          )}
        </p>
      </div>
    </Link>
  );
}
