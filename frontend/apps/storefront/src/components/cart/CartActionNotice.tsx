'use client';

import { useDictionary } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import type { CartActionResult } from '@/lib/cart/use-cart-action';

/** 담기 버튼 아래 안내 줄. 담기 결과가 있으면 그것을(장바구니 링크와 함께), 없으면 지금 선택 상태 안내를 보인다 */
export function CartActionNotice({
  id,
  result,
  notice,
}: {
  id: string;
  result: CartActionResult | null;
  notice: string;
}) {
  const t = useDictionary().cart;
  return (
    <p id={id} role="status" className="text-center text-xs text-muted-fg">
      {result ? (
        <>
          {result.text}
          {result.showCartLink && (
            <>
              {' '}
              <Link href="/cart" className="text-fg underline underline-offset-4">
                {t.view}
              </Link>
            </>
          )}
        </>
      ) : (
        notice
      )}
    </p>
  );
}
