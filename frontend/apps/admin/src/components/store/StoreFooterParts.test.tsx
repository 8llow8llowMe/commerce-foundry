import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { blankRow, type FooterForm } from '@/lib/store-footer/form';
import { FooterPreview, PublishConfirm } from './StoreFooterParts';

const form: FooterForm = {
  copyrightKo: '© 국문 문구',
  copyrightEn: '',
  links: [
    {
      ...blankRow('INSTAGRAM'),
      labelKo: '인스타',
      labelEn: 'Insta',
      url: 'https://www.instagram.com/example_shop',
    },
    {
      ...blankRow('CUSTOM'),
      labelKo: '숨김',
      labelEn: 'Hidden',
      url: 'https://a.example.com',
      visible: false,
    },
    { ...blankRow('CUSTOM'), labelKo: '블로그', labelEn: 'Blog', url: 'http://bad' },
  ],
};

describe('FooterPreview', () => {
  it('노출 링크만 지금 입력 순서로, 형식이 틀린 링크는 공개 안 됨으로 표시한다', () => {
    const html = renderToStaticMarkup(<FooterPreview form={form} device="pc" locale="ko" />);
    expect(html).toContain('<span class="sr-only">인스타</span>');
    expect(html).not.toContain('숨김');
    expect(html).toContain('블로그');
    expect(html).toContain('확인 필요 · 공개 안 됨');
    expect(html).toContain('© 국문 문구');
    expect(html).toContain('data-device="pc"');
    // 미리보기 링크는 이동하지 않는다
    expect(html).not.toContain('<a ');
  });

  it('영문 · 모바일은 영문 표시명과 기본 저작권 표기 안내', () => {
    const html = renderToStaticMarkup(<FooterPreview form={form} device="mobile" locale="en" />);
    expect(html).toContain('Insta');
    expect(html).toContain('data-device="mobile"');
    expect(html).toContain('w-[375px]');
    expect(html).toContain('저작권 문구가 비면 기본 표기');
  });
});

describe('PublishConfirm', () => {
  it('게시할 리비전 번호와 바뀌는 범위를 알린다', () => {
    const html = renderToStaticMarkup(
      <PublishConfirm
        revisionNumber={4}
        pending={false}
        onCancel={() => {}}
        onPublish={() => {}}
      />,
    );
    expect(html).toContain('role="alertdialog"');
    expect(html).toContain('4번 초안을 게시할까요?');
  });
});
