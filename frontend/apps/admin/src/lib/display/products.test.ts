import { describe, expect, it } from 'vitest';
import type { ProductFormItem } from './form';
import { addProduct, productNotice } from './products';

const ON_SALE = { code: 'ON_SALE', name: '판매중', description: '' };
const SOLD_OUT = { code: 'SOLD_OUT', name: '품절', description: '' };

const candidate = (productId: string, visible = true, availability = ON_SALE) => ({
  productId,
  name: `상품 ${productId}`,
  visible,
  availability,
});

const item = (productId: string): ProductFormItem => ({
  productId,
  name: `상품 ${productId}`,
  visible: true,
  availability: null,
  caption: null,
});

describe('addProduct', () => {
  it('맨 뒤에 더하고 검색 결과의 노출 · 판매 상태를 함께 담는다', () => {
    const result = addProduct([item('1')], candidate('2', false, SOLD_OUT), 'PRODUCT_PICK');
    expect(result).toEqual({
      ok: true,
      products: [
        item('1'),
        { productId: '2', name: '상품 2', visible: false, availability: SOLD_OUT, caption: null },
      ],
    });
  });

  it('이미 넣은 상품은 더하지 않는다', () => {
    expect(addProduct([item('1')], candidate('1'), 'PRODUCT_PICK')).toEqual({
      ok: false,
      reason: 'duplicate',
    });
  });

  it('상한(상품 픽 20개)에 닿으면 더하지 않는다', () => {
    const full = Array.from({ length: 20 }, (_, index) => item(String(index + 1)));
    expect(addProduct(full, candidate('99'), 'PRODUCT_PICK')).toEqual({
      ok: false,
      reason: 'full',
    });
  });
});

describe('productNotice', () => {
  it('찾을 수 없음 · 숨김 · 판매중 아님 순으로 알리고, 판매 상태를 모르면 알리지 않는다', () => {
    expect(productNotice({ ...item('1'), name: null })).toContain('찾을 수 없는 상품');
    expect(productNotice({ ...item('1'), visible: false })).toContain('메인에 보이지 않습니다');
    expect(productNotice({ ...item('1'), availability: SOLD_OUT })).toBe(
      '품절 상품입니다. 메인에 보이지만 지금은 살 수 없습니다.',
    );
    expect(productNotice({ ...item('1'), availability: ON_SALE })).toBeNull();
    expect(productNotice(item('1'))).toBeNull();
  });
});
