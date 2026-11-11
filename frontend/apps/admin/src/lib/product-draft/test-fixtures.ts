import type { ProductDraft } from './detail';

/** 테스트용 초안 응답 — 아이디는 화면에 보이지 않아야 해서 눈에 띄는 값을 쓴다 */
export const draftFixture = (patch: Partial<ProductDraft> = {}): ProductDraft => ({
  draftId: '4611686018427387904',
  status: {
    code: 'REQUESTED',
    name: '생성 대기',
    description: 'AI 초안 생성을 기다리고 있습니다.',
  },
  facts: {
    nameHint: '린넨 저고리',
    categoryId: null,
    materials: ['린넨 100%'],
    careNotes: [],
    measurementsNote: '총장 62cm\n가슴 54cm',
    origin: null,
    certifications: [],
    tone: null,
    mustInclude: [],
    mustAvoid: ['최고'],
  },
  images: [
    {
      url: 'https://cdn.example.test/products/media/7/u1/h1.jpg',
      imageType: 'THUMBNAIL',
      width: null,
      height: null,
    },
  ],
  missingRequiredInputs: [
    { code: 'CATEGORY', name: '카테고리', description: '상품 카테고리를 골라 주세요.' },
  ],
  requestedByMemberId: '9876543210123',
  requestedAt: '2026-11-01T03:00:00Z',
  expiresAt: '2026-11-08T03:00:00Z',
  rejectedAt: null,
  ...patch,
});
