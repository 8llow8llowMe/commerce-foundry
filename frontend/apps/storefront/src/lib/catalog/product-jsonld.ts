import { moneyToDecimalString } from '@cf/api-client';
import type { ProductDetail, SaleStatusCode } from './types';

const AVAILABILITY: Record<SaleStatusCode, string> = {
  ON_SALE: 'https://schema.org/InStock',
  SOLD_OUT: 'https://schema.org/OutOfStock',
  STOP_SELLING: 'https://schema.org/Discontinued',
};

/**
 * 상품 구조화 데이터(schema.org Product). `<script type="application/ld+json">` 에 그대로 넣는 문자열이다.
 * 가격은 상품 표시가(판매중 옵션 최저가)다. 상품명 · 요약은 운영 데이터라 `<` 를 이스케이프해
 * `</script>` 로 태그를 닫을 수 없게 한다.
 */
export function productJsonLd(product: ProductDetail): string {
  const images = [product.thumbnail, ...product.images]
    .map((image) => image?.url)
    .filter((url): url is string => Boolean(url));

  const data = {
    '@context': 'https://schema.org',
    '@type': 'Product',
    productID: product.productId,
    name: product.name,
    ...(product.summary ? { description: product.summary } : {}),
    ...(images.length > 0 ? { image: [...new Set(images)] } : {}),
    offers: {
      '@type': 'Offer',
      price: moneyToDecimalString(product.salePrice),
      priceCurrency: product.salePrice.currency,
      availability: AVAILABILITY[product.saleStatus.code],
    },
  };
  return JSON.stringify(data).replace(/</g, '\\u003c');
}
