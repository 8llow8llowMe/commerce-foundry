/** 어드민 카테고리 API(#9, `backend/docs/api-design-guide.md` §2-5). 숨김 포함 트리 · 생성 · 전체 교체 수정 · 삭제 */
export const CATEGORIES_PATH = '/api/v1/admin/categories';

/** 대 · 중 · 소 3단계까지(서버 `CategoryTree.MAX_DEPTH`) */
export const MAX_DEPTH = 3;

/** 단건(생성 · 수정 응답). id 는 문자열이다 */
export type Category = {
  categoryId: string;
  parentId: string | null;
  name: string;
  displayOrder: number;
  visible: boolean;
};

/** 트리 노드. 형제는 서버가 노출 순서 → id 순으로 준다 */
export type CategoryNode = Category & { children: CategoryNode[] };

export type CategoryTree = { categories: CategoryNode[] };

/** 생성 · 수정 본문(같은 모양, 수정은 전체 교체). `parentId` null 이면 최상위 */
export type CategoryRequest = {
  parentId: string | null;
  name: string;
  displayOrder: number | null;
  visible: boolean;
};
