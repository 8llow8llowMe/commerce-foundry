import { describe, expect, it } from 'vitest';
import { editableType, itemSummary, sectionName } from './section-view';
import { mediaItem, productItem, section } from './test-fixtures';
import { CREATABLE_TYPES } from './types';

describe('sectionName', () => {
  it('제목, 없으면 유형 이름 + 제목 없음', () => {
    expect(sectionName(section('STORY'))).toBe('샘플 제목');
    expect(sectionName(section('STORY', { title: null }))).toBe('스토리 (제목 없음)');
  });
});

describe('itemSummary', () => {
  it('미디어 · 상품 수를 세어 보인다', () => {
    expect(
      itemSummary(section('PRODUCT_PICK', { items: [productItem('1'), productItem('2')] })),
    ).toBe('상품 2개');
    expect(itemSummary(section('LOOK', { items: [mediaItem(), productItem('1')] }))).toBe(
      '이미지 · 영상 1개 · 상품 1개',
    );
    expect(itemSummary(section('STORY'))).toBe('항목 없음');
  });
});

describe('editableType', () => {
  it('LOOK · 모르는 유형은 이 화면에서 고치지 않는다', () => {
    expect(editableType(section('HERO'))).toBe('HERO');
    expect(editableType(section('LOOK'))).toBeNull();
    const unknown = section('STORY');
    expect(
      editableType({ ...unknown, sectionType: { ...unknown.sectionType, code: 'BANNER' } }),
    ).toBeNull();
  });
});

describe('CREATABLE_TYPES', () => {
  it('업로드 없이 만들 수 있는 유형(미디어 하한 0, LOOK 제외)만이다', () => {
    expect(CREATABLE_TYPES).toEqual(['PRODUCT_PICK', 'STORY']);
  });
});
