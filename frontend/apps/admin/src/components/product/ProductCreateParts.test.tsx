import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { comboKey, emptyProductForm, emptySku, skuRows } from '@/lib/product/create';
import { OptionEditor } from './OptionEditor';
import { SanitizedNotice, SANITIZED_MESSAGE } from './SanitizedNotice';
import { SkuTable } from './SkuTable';

const groups = [{ id: 'a', name: '사이즈', values: ['S', 'M'] }];
const none = () => [];

describe('SkuTable', () => {
  it('조합마다 한 줄 · 처음 재고를 비우면 품절 안내 · 무제한은 수량 칸이 없다', () => {
    const form = {
      ...emptyProductForm(),
      optionGroups: groups,
      skus: { [comboKey(['M'])]: { ...emptySku(), stockMode: 'unlimited' as const } },
    };
    const html = renderToStaticMarkup(
      <SkuTable
        rows={skuRows(form)}
        groups={groups}
        currencyUnit="원"
        errorsOf={none}
        onChange={() => {}}
      />,
    );
    expect(html).toContain('사이즈: S');
    expect(html).toContain('사이즈: M');
    expect(html).toContain('추가금 (원)');
    expect(html).toContain('품절로 등록됩니다.');
    expect(html).toContain('재고를 세지 않고 판매합니다.');
    expect(html.match(/처음 재고 수량/g)).toHaveLength(1);
  });

  it('줄 번호 키의 칸 오류와 표 전체 오류를 그린다', () => {
    const errors: Record<string, string[]> = {
      'skus.0.hsCode': ['HS 오류'],
      skus: ['표 오류'],
    };
    const html = renderToStaticMarkup(
      <SkuTable
        rows={skuRows(emptyProductForm())}
        groups={[]}
        currencyUnit="원"
        errorsOf={(key) => errors[key] ?? []}
        onChange={() => {}}
      />,
    );
    expect(html).toContain('>기본<');
    expect(html).toContain('HS 오류');
    expect(html).toContain('aria-invalid="true"');
    expect(html).toContain('표 오류');
  });
});

describe('OptionEditor', () => {
  it('그룹 · 값 칩 · 조합 수를 보이고 그룹 칸 오류를 그 그룹에 그린다', () => {
    const errors: Record<string, string[]> = { 'optionGroups.0.name': ['이름 오류'] };
    const html = renderToStaticMarkup(
      <OptionEditor
        groups={groups}
        errorsOf={(key) => errors[key] ?? []}
        disabled={false}
        onChange={() => {}}
      />,
    );
    expect(html).toContain('지금 조합 2개');
    expect(html).toContain('S 값 삭제');
    expect(html).toContain('M 값 삭제');
    expect(html).toContain('이름 오류');
  });

  it('그룹이 다섯 개면 그룹 추가를 막는다', () => {
    const five = Array.from({ length: 5 }, (_, i) => ({ id: `${i}`, name: `g${i}`, values: [] }));
    const html = renderToStaticMarkup(
      <OptionEditor groups={five} errorsOf={none} disabled={false} onChange={() => {}} />,
    );
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>옵션 그룹 추가<\/button>/);
  });
});

describe('SanitizedNotice', () => {
  it('정제 안내를 보인다', () => {
    expect(renderToStaticMarkup(<SanitizedNotice />)).toContain(SANITIZED_MESSAGE);
  });
});
