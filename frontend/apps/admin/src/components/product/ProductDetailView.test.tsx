import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { adminProduct } from '@/lib/product/test-fixtures';
import { ProductDetailView } from './ProductDetailView';

const render = (product = adminProduct()) =>
  renderToStaticMarkup(<ProductDetailView product={product} categories={null} />);

describe('ProductDetailView', () => {
  it('숨긴 상품의 저장된 상태 · 가격을 서버 이름과 formatMoney 로 보인다', () => {
    const html = render();
    expect(html).toContain('테스트 저고리');
    expect(html).toContain('title="판매 중인 상품"');
    expect(html).toContain('>판매중<');
    expect(html).toContain('숨김');
    expect(html).toContain('129,000원');
    expect(html).toContain('159,000원');
    expect(html).toContain('제한 없음');
    expect(html).toContain('카테고리 이름을 불러오지 못했습니다.');
  });

  it('아이디 · 코드 원문을 보이지 않는다(#305)', () => {
    const html = render();
    for (const raw of ['4611686018427387904', '9001', 'ON_SALE', 'STOP_SELLING', 'THUMBNAIL']) {
      // 상품 아이디는 관련 화면 링크(href)에만 있다
      expect(html.replaceAll(/href="[^"]*"/g, '')).not.toContain(raw);
    }
  });

  it('은퇴 옵션 · 판매 종료 SKU 를 표시한다', () => {
    const html = render();
    expect(html).toContain('(사용 안 함)');
    expect(html).toContain('색상: 쪽빛 · 사이즈: L');
    expect(html).toContain('(사용 안 하는 옵션)');
    expect(html).toContain('>판매 종료<');
    expect(html).toContain('5,000원');
    expect(html).toContain('450g');
  });

  it('영상은 포스터와 함께 video 로, 이미지는 img 로 그린다', () => {
    const html = render();
    expect(html).toContain('<video src="https://img.test/clip.mp4"');
    expect(html).toContain('poster="https://img.test/poster.jpg"');
    expect(html).toContain('src="https://img.test/thumb.jpg" alt="정면"');
  });

  it('상세 설명 HTML 은 정제해서 넣는다', () => {
    const html = render(
      adminProduct({
        descriptionHtml: '<p onclick="x()">소재</p><script>alert(1)</script>',
      }),
    );
    expect(html).toContain('<p>소재</p>');
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('onclick');
    expect(render(adminProduct({ descriptionHtml: null }))).toContain('상세 설명이 없습니다.');
  });

  it('프리오더 · 맞춤 제작 설정과 입력 항목을 보인다', () => {
    const html = render(
      adminProduct({
        preorder: true,
        expectedShipDate: '2026-11-20',
        customOrder: true,
        customOrderLeadTimeDays: 14,
        customOrderNotice: '치수를 확인해 주세요',
        customOrderFields: [
          {
            fieldKey: 'chest_raw_key',
            label: '가슴둘레',
            inputType: { code: 'NUMBER', name: '숫자', description: '숫자 입력' },
            unit: 'cm',
            required: true,
            minValue: 70,
            maxValue: 130,
            stepValue: null,
            helpText: '겨드랑이 아래를 잽니다',
            displayOrder: 0,
          },
        ],
      }),
    );
    expect(html).toContain('2026. 11. 20.');
    expect(html).toContain('14일');
    expect(html).toContain('치수를 확인해 주세요');
    expect(html).toContain('가슴둘레');
    expect(html).toContain('70 ~ 130 cm');
    expect(html).toContain('겨드랑이 아래를 잽니다');
    expect(html).not.toContain('chest_raw_key');
  });

  it('재고 · 원가 링크는 노출 상품만, 사이즈표는 늘 있다', () => {
    const hidden = render();
    expect(hidden).not.toContain('href="/stocks/');
    expect(hidden).toContain('href="/size-charts/4611686018427387904"');
    const shown = render(adminProduct({ visible: true }));
    expect(shown).toContain('href="/stocks/4611686018427387904"');
    expect(shown).toContain('href="/sku-costs/4611686018427387904"');
  });
});
