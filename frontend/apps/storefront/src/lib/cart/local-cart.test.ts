import { describe, expect, it } from 'vitest';
import { addLines, lineKey, parseLines, withoutMerged, type LocalCartLine } from './local-cart';

const line = (overrides: Partial<LocalCartLine> = {}): LocalCartLine => ({
  skuId: '1001',
  productId: '10',
  quantity: 1,
  customOptions: [],
  addedAt: 1,
  ...overrides,
});

describe('lineKey', () => {
  it('맞춤 제작 입력은 공백 · 순서 · 유니코드 정규화와 무관하게 같은 줄이다 (BE 와 같은 판정)', () => {
    const nfd = '허리'.normalize('NFD');
    expect(
      lineKey('1', [
        { fieldKey: 'b', value: ' 2 ' },
        { fieldKey: 'a', value: nfd },
      ]),
    ).toBe(
      lineKey('1', [
        { fieldKey: 'a', value: '허리' },
        { fieldKey: 'b', value: '2' },
      ]),
    );
    expect(lineKey('1', [])).not.toBe(lineKey('2', []));
  });
});

describe('addLines', () => {
  it('같은 줄이면 수량을 더하고 상한에서 자른다', () => {
    const next = addLines([line({ quantity: 2 })], [{ ...line(), quantity: 5, maxQuantity: 3 }], 9);
    expect(next).toEqual([line({ quantity: 3 })]);
  });

  it('새 줄은 담은 시각을 붙이고, 상한이 없으면 99 에서 자른다', () => {
    const next = addLines(
      [],
      [{ skuId: '7', productId: '1', quantity: 120, customOptions: [] }],
      9,
    );
    expect(next).toEqual([
      { skuId: '7', productId: '1', quantity: 99, customOptions: [], addedAt: 9 },
    ]);
  });

  it('새 줄이 50줄을 넘으면 담지 않는다(null)', () => {
    const full = Array.from({ length: 50 }, (_, i) => line({ skuId: String(i + 1) }));
    expect(addLines(full, [{ ...line(), skuId: '999' }], 9)).toBeNull();
    // 이미 있는 줄은 50줄이어도 수량만 더한다
    expect(addLines(full, [{ ...line(), skuId: '1' }], 9)?.[0]?.quantity).toBe(2);
  });
});

describe('parseLines', () => {
  it('모양이 틀린 줄 · 깨진 값은 버린다', () => {
    expect(parseLines('not json')).toEqual([]);
    expect(parseLines('{"a":1}')).toEqual([]);
    const ok = line();
    const raw = JSON.stringify([
      ok,
      { ...ok, skuId: 'abc' },
      { ...ok, quantity: 0 },
      { ...ok, quantity: 100 },
    ]);
    expect(parseLines(raw)).toEqual([ok]);
  });

  it('맞춤 제작 입력이 BE 제약(키 형식 · 값 1~500자 · 20개)을 어기는 줄은 버린다 — 병합 전체가 400 이 되지 않게', () => {
    const ok = line({ customOptions: [{ fieldKey: 'waist', value: '70' }] });
    const bad = [
      { fieldKey: 'Waist', value: '70' },
      { fieldKey: 'waist', value: '  ' },
      { fieldKey: 'memo', value: 'x'.repeat(501) },
    ].map((option) => line({ customOptions: [option] }));
    const tooMany = line({
      customOptions: Array.from({ length: 21 }, (_, i) => ({ fieldKey: `k${i}`, value: '1' })),
    });
    expect(parseLines(JSON.stringify([ok, ...bad, tooMany]))).toEqual([ok]);
  });
});

describe('withoutMerged', () => {
  it('보낸 줄은 지우고, 보내는 동안 새로 담은 줄과 늘어난 수량은 남긴다', () => {
    const sent = [line({ skuId: '1', quantity: 2, addedAt: 1 }), line({ skuId: '2', addedAt: 2 })];
    const current = [
      line({ skuId: '1', quantity: 5, addedAt: 1 }),
      line({ skuId: '2', addedAt: 2 }),
      line({ skuId: '3', addedAt: 3 }),
    ];
    expect(withoutMerged(current, sent)).toEqual([
      line({ skuId: '1', quantity: 3, addedAt: 1 }),
      line({ skuId: '3', addedAt: 3 }),
    ]);
  });
});
