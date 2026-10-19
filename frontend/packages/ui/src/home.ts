// 메인 화면 섹션 계약. 유형은 BE 메인 전시 API(#63)의 섹션 유형과 같은 이름으로 둔다 —
// API 가 생기면 브랜드 설정 대신 응답을 그대로 넘긴다. 유형 이름은 브랜드 무관 블록이고,
// 'The Romantic Edit' 같은 브랜드 문구는 title 값이다.

export type HomeLink = {
  label: string;
  href: string;
};

export type HomeImage = {
  src: string;
  alt: string;
  width?: number;
  height?: number;
};

/** 이미지 한 칸. 화보가 아직 없으면 image 는 null 이고 placeholder 에 필요한 사진을 적는다 */
export type HomeMediaSlot = {
  image: HomeImage | null;
  /** 빈 자리에 보이는 안내 (예: '소재 클로즈업 · 3:4') */
  placeholder: string;
};

export type HeroSection = {
  type: 'HERO';
  id: string;
  eyebrow: string | null;
  /** 줄마다 하나 */
  title: string[];
  subtitle: string | null;
  cta: HomeLink;
  /** PC 가로(16:9) · 모바일 세로(4:5) 화보를 따로 둔다(art direction) */
  desktop: HomeMediaSlot;
  mobile: HomeMediaSlot;
  /** 짧은 영상. 있으면 muted autoplay, 움직임 줄이기 설정이면 포스터만 */
  video: { src: string; poster: string } | null;
};

export type ProductPickSection = {
  type: 'PRODUCT_PICK';
  id: string;
  title: string;
  viewAll: HomeLink | null;
  /** 상품 목록 API 에서 고른다(#63 이전 1차). categoryId 가 null 이면 전체 신상품 */
  source: { categoryId: string | null; limit: number };
};

export type StorySection = {
  type: 'STORY';
  id: string;
  eyebrow: string | null;
  title: string;
  body: string;
  links: HomeLink[];
  /** 1~2장. 2장이면 비대칭으로 놓는다 */
  media: HomeMediaSlot[];
  /** asymmetric: 큰 이미지 + 글 비대칭, centered: 글 가운데 */
  layout: 'asymmetric' | 'centered';
};

export type LookSection = {
  type: 'LOOK';
  id: string;
  title: string;
  body: string | null;
  media: HomeMediaSlot;
  /** 착장 구성 상품. 상품 상세 API 로 이름 · 가격 · 판매 상태를 받는다(#62 이전 1차) */
  productIds: string[];
  link: HomeLink | null;
};

export type DetailSection = {
  type: 'DETAIL';
  id: string;
  eyebrow: string | null;
  title: string;
  body: string;
  points: { title: string; text: string }[];
  media: HomeMediaSlot[];
};

export type HomeSection =
  HeroSection | ProductPickSection | StorySection | LookSection | DetailSection;
