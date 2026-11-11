import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { PHOTO_PRIVACY_NOTICE } from './DraftImagesField';
import { DraftListField } from './DraftListField';
import {
  afterDraftCreated,
  FACTS_ONLY_NOTICE,
  ProductDraftRequestForm,
} from './ProductDraftRequestForm';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn() }) }));

describe('ProductDraftRequestForm', () => {
  const html = renderToStaticMarkup(<ProductDraftRequestForm />);

  it('사진 안내(고객 정보 금지)와 "여기 적은 값만 사실" 안내를 보인다', () => {
    expect(html).toContain(PHOTO_PRIVACY_NOTICE.split('(')[0]);
    expect(html).toContain('주문 화면 · 송장');
    expect(html).toContain(FACTS_ONLY_NOTICE);
  });

  it('대표 · 추가 사진 업로드 칸과 사실 칸들을 그린다', () => {
    expect(html).toContain('대표 사진 파일 올리기');
    expect(html).toContain('추가 사진 파일 올리기');
    expect(html).toContain('추가 사진 (선택, 7장까지 — 0장)');
    expect(html).toContain('한 장에 5MB 이하');
    for (const label of [
      '상품명 후보',
      '카테고리',
      '원산지',
      '치수 메모',
      '소재',
      '세탁 · 관리 방법',
      '인증',
      '문구 분위기',
      '꼭 넣을 표현',
      '쓰지 않을 표현',
    ]) {
      expect(html).toContain(label);
    }
    expect(html).toMatch(/<button type="submit"[^>]*>초안 요청<\/button>/);
  });

  it('아이디 · 코드를 보이지 않는다', () => {
    expect(html).not.toMatch(/PRODUCT_DRAFT_|draftId|categoryId=/);
  });
});

describe('DraftListField', () => {
  it('칩마다 빼기 버튼을 달고, 10개면 입력 · 더하기를 막는다', () => {
    const items = Array.from({ length: 10 }, (_, i) => `항목${i}`);
    const html = renderToStaticMarkup(
      <DraftListField label="소재" items={items} onChange={() => {}} />,
    );
    expect(html).toContain('aria-label="소재 &quot;항목0&quot; 빼기"');
    expect(html).toMatch(/<input[^>]*disabled=""/);
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>더하기<\/button>/);
  });

  it('비었으면 목록 없이 입력 칸만 둔다', () => {
    const html = renderToStaticMarkup(
      <DraftListField label="인증" items={[]} onChange={() => {}} />,
    );
    expect(html).not.toContain('<ul');
    expect(html).toContain('인증 (선택, 10개까지)');
  });
});

describe('afterDraftCreated', () => {
  it('응답의 초안 아이디로 상세로 옮긴다', () => {
    const replace = vi.fn();
    expect(afterDraftCreated({ draftId: '42' }, replace)).toBe(true);
    expect(replace).toHaveBeenCalledWith('/products/drafts/42');
  });

  it('아이디 모양이 다르면 옮기지 않는다(결과 모름으로 다룬다)', () => {
    const replace = vi.fn();
    expect(afterDraftCreated({ draftId: 42 }, replace)).toBe(false);
    expect(afterDraftCreated(null, replace)).toBe(false);
    expect(afterDraftCreated({ draftId: '../x' }, replace)).toBe(false);
    expect(replace).not.toHaveBeenCalled();
  });
});
