// 메인 화면 섹션 계약. 유형은 BE 메인 전시 API(#63)의 섹션 유형과 같은 이름으로 둔다 —
// API 가 생기면 브랜드 설정 대신 응답을 그대로 넘긴다. 유형 이름은 브랜드 무관 블록이고,
// 'The Romantic Edit' 같은 브랜드 문구는 title 값이다. 제목 위 작은 라벨(eyebrow)은 두지 않는다(#57 금지 목록).

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
  /** 사진 아래쪽에 얹는 한 줄 캡션(curtain 스토리). 없으면 생략 */
  caption?: string | null;
};

export type HeroSection = {
  type: 'HERO';
  id: string;
  /** 줄마다 하나 */
  title: string[];
  subtitle: string | null;
  /** 주 버튼(단색 채움) */
  cta: HomeLink;
  /** 보조 동작 — 모바일은 cta 와 반반 버튼, PC 는 글자 링크. 없으면 null */
  secondaryCta: HomeLink | null;
  /** PC · 모바일 화보를 따로 둔다(art direction). 둘 다 세로 4:5 전신 — PC 는 아래쪽이 화면 밖으로 잘린다 */
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
  title: string;
  body: string;
  links: HomeLink[];
  /** asymmetric · centered 는 1~2장, curtain 은 2~3장(같은 빛 · 같은 톤으로 고른다) */
  media: HomeMediaSlot[];
  /**
   * asymmetric: 큰 이미지 + 글 비대칭, centered: 글 가운데,
   * curtain: 전폭 사진을 스크롤 고정으로 쌓아 다음 장이 커튼처럼 덮는다(#59 03),
   * panel: 사이트에서 유일한 어두운 면 — 제목 · 글(빈 줄로 단락) · 버튼 + 브랜드 모티프 모양으로 자른 사진 1장(#59 06)
   */
  layout: 'asymmetric' | 'centered' | 'curtain' | 'panel';
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
  title: string;
  body: string;
  /** 확대해 보는 전신 사진(세로 4:5). null 이면 확대 무대 없이 클로즈업만 보인다 */
  frame: HomeMediaSlot | null;
  points: DetailPoint[];
  /** 클로즈업 — 스크립트가 없거나 frame 이 없을 때 확대 대신 보인다. points 와 같은 순서 */
  media: HomeMediaSlot[];
};

/** 제작 포인트. focus 는 frame 사진에서 이 부분의 위치(0~1)와 확대 배율 */
export type DetailPoint = {
  title: string;
  text: string;
  focus: { x: number; y: number; scale: number } | null;
};

export type HomeSection =
  HeroSection | ProductPickSection | StorySection | LookSection | DetailSection;
