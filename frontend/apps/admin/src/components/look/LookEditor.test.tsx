import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { createLookForm, editLookForm } from '@/lib/look/form';
import { ImageFields, LookEditor } from './LookEditor';

const noop = () => {};

const renderImage = (imageUrl: string, altText = '') =>
  renderToStaticMarkup(
    <ImageFields
      form={{ imageUrl, altText }}
      errorsOf={() => []}
      onChange={noop}
      onMeasured={noop}
    />,
  );

describe('ImageFields', () => {
  it('업로드 대신 저장소에 올린 이미지 주소를 넣으라고 안내하고, 주소가 없으면 빈 자리를 보인다', () => {
    const html = renderImage('');
    expect(html).toContain('이미지 업로드는 준비 중입니다');
    expect(html).toContain('이미지 없음');
    expect(html).not.toContain('<img');
  });

  it('주소가 있으면 그 이미지를 작게 보인다', () => {
    const html = renderImage(' https://media.example.test/a.jpg ', '가을 코디');
    expect(html).toContain('src="https://media.example.test/a.jpg"');
    expect(html).toContain('사진을 볼 수 없는 고객에게 읽어 주는 문장입니다.');
  });
});

describe('LookEditor', () => {
  const render = (lookId: string | null, initial = createLookForm()) =>
    renderToStaticMarkup(
      <LookEditor
        heading="룩 추가"
        lookId={lookId}
        initial={initial}
        onSaved={noop}
        onReload={noop}
        onDenied={noop}
        onCancel={noop}
      />,
    );

  it('새 룩은 숨김으로 시작한다고 알리고, 상품 고르기는 룩 기준 문구다', () => {
    const html = render(null);
    expect(html).toContain('새 룩은 꺼진 채로 시작합니다');
    expect(html).toContain('상품 (0 / 20개)');
    expect(html).toContain('룩에 이 순서대로 보입니다.');
  });

  it('고칠 때는 구성 상품 이름 · 상태를 보이고 상품 아이디는 보이지 않는다', () => {
    const html = render(
      '4611686018427387905',
      editLookForm({
        lookId: '4611686018427387905',
        title: '가을 룩',
        description: null,
        image: { url: 'https://media.example.test/a.jpg', width: 1, height: 1, altText: null },
        visible: true,
        products: [
          {
            productId: '4611686018427387904',
            name: '치마',
            visible: true,
            saleStatus: { code: 'STOP_SELLING', name: '판매 종료', description: '' },
          },
        ],
      }),
    );
    expect(html).not.toContain('새 룩은 꺼진 채로 시작합니다');
    expect(html).toContain('치마');
    expect(html).toContain('판매 종료 상품입니다. 룩에 보이지만 지금은 살 수 없습니다.');
    expect(html).not.toMatch(/>[^<]*4611686018427387904[^<]*</);
  });
});
