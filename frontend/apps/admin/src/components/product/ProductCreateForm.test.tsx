import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { PRODUCT_NEW_TOUR } from '@/lib/tour/tours';
import { ProductCreateForm } from './ProductCreateForm';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn() }) }));

/** 서버 렌더는 effect 를 돌리지 않아 카테고리가 늘 불러오는 중이다 — 불러온 뒤 화면은 카테고리 초기 상태만 바꿔 그린다 */
const categoriesLoaded = vi.hoisted(() => ({ value: false }));
vi.mock('react', async (importOriginal) => {
  const react = await importOriginal<typeof import('react')>();
  const useState = ((initial: unknown) =>
    react.useState(
      categoriesLoaded.value &&
        typeof initial === 'object' &&
        (initial as { kind?: unknown } | null)?.kind === 'loading'
        ? { kind: 'ok', rows: [] }
        : initial,
    )) as typeof react.useState;
  return { ...react, default: { ...react, useState }, useState };
});

const render = (loaded: boolean) => {
  categoriesLoaded.value = loaded;
  return renderToStaticMarkup(<ProductCreateForm />);
};

describe('ProductCreateForm', () => {
  it('카테고리를 불러오면 상품 등록 투어(#228 product-new-v1)가 가리키는 앵커를 모두 한 번씩 정의 순서로 단다', () => {
    const anchors = [...render(true).matchAll(/data-tour="([^"]+)"/g)].map((match) => match[1]);
    // 정의와 폼이 어긋나면 필수 요소가 없어 투어가 시작하지 않는다 — 폼에 없는 앵커 · 정의에 없는 앵커 둘 다 잡는다
    expect(anchors).toEqual(PRODUCT_NEW_TOUR.steps.map((step) => step.target));
  });

  it('카테고리를 불러오는 중(권한 확인 전)에는 앵커를 달지 않는다 — 투어가 403 권한 안내 위에 남지 않게', () => {
    expect(render(false)).not.toContain('data-tour');
  });

  it('이미지 업로드 칸(BE #9 F2)은 투어가 가리키지 않는다', () => {
    const html = render(true);
    const imageSection = html.match(/<section[^>]*>(?:(?!<\/section>).)*type="file"/s)?.[0] ?? '';
    expect(imageSection).not.toBe('');
    expect(imageSection).not.toContain('data-tour');
  });

  it('대표 이미지 업로드 칸을 켜 두고 추가 이미지 칸을 더할 수 있다', () => {
    const html = render(false);
    expect(html).toMatch(/<input(?=[^>]*type="file")(?![^>]*disabled="")[^>]*>/);
    expect(html).toContain('대표 이미지 (선택)');
    expect(html).toContain('추가 이미지 칸 더하기');
    expect(html).not.toContain('서버 기능이 준비되면 열립니다');
  });
});
