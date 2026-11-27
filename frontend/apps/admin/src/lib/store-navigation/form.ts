import type { FormErrors } from '@/lib/api-errors';
import { externalHost } from '@/lib/store-footer/form';
import { blankNode, countAll, flatten, validKey, type MenuNode } from './tree';
import {
  LIMITS,
  TYPE_LABELS,
  type NavDetail,
  type NavItemPayload,
  type NavText,
  type NavType,
} from './types';

/** 상세 응답(트리 전위 순서의 평평한 목록) → 편집기 트리. 형제는 displayOrder 오름차순, 상위를 못 찾으면 최상위로 둔다 */
export function formOf(detail: NavDetail | null): MenuNode[] {
  if (!detail) return [];
  const items = detail.revision.items;
  const keys = new Set(items.map((item) => item.key));
  const nodes = new Map<string, MenuNode>();
  for (const item of items) {
    nodes.set(item.key, {
      ...blankNode(item.key),
      type: item.type,
      labelKo: item.labels.ko,
      labelEn: item.labels.en,
      descriptionKo: item.descriptions?.ko ?? '',
      descriptionEn: item.descriptions?.en ?? '',
      path: item.type === 'INTERNAL' ? (item.url ?? '') : '',
      externalUrl: item.type === 'EXTERNAL' ? (item.url ?? '') : '',
      categoryId: item.categoryId ?? '',
      visible: item.visible,
      primary: item.primary,
      shortcut: item.shortcut,
    });
  }
  const roots: { order: number; node: MenuNode }[] = [];
  const childOrders = new Map<string, { order: number; node: MenuNode }[]>();
  for (const item of items) {
    const node = nodes.get(item.key);
    if (!node) continue;
    const entry = { order: item.displayOrder, node };
    if (item.parentKey !== null && keys.has(item.parentKey) && item.parentKey !== item.key) {
      childOrders.set(item.parentKey, [...(childOrders.get(item.parentKey) ?? []), entry]);
    } else roots.push(entry);
  }
  const sorted = (entries: { order: number; node: MenuNode }[]) =>
    [...entries].sort((a, b) => a.order - b.order).map((entry) => entry.node);
  // 하위 목록을 채운 새 객체로 다시 만든다(가변 객체를 화면 상태에 두지 않는다)
  const build = (node: MenuNode, seen: Set<string>): MenuNode => {
    if (seen.has(node.key)) return { ...node, children: [] };
    const next = new Set(seen).add(node.key);
    return {
      ...node,
      children: sorted(childOrders.get(node.key) ?? []).map((child) => build(child, next)),
    };
  };
  return sorted(roots).map((node) => build(node, new Set()));
}

const trimmed = (value: string) => value.trim();

/** 유형별 링크 대상 — 지금 유형의 칸만 보낸다 */
function target(node: MenuNode): Pick<NavItemPayload, 'url' | 'categoryId'> {
  switch (node.type) {
    case 'INTERNAL':
      return { url: node.path.trim(), categoryId: null };
    case 'EXTERNAL':
      return { url: node.externalUrl.trim(), categoryId: null };
    case 'CATEGORY':
      return { url: null, categoryId: node.categoryId.trim() };
    case 'NONE':
      return { url: null, categoryId: null };
  }
}

/** 설명 — 두 언어 모두 비면 null(없음). 하나만 있으면 그대로 보내 서버 · 화면 검사가 알린다 */
function descriptionsOf(node: MenuNode): NavText | null {
  const ko = trimmed(node.descriptionKo);
  const en = trimmed(node.descriptionEn);
  return ko === '' && en === '' ? null : { ko, en };
}

/**
 * 저장 본문의 items — 트리 전위 순서, 형제의 displayOrder 는 화면 순서대로 0 부터 다시 매긴다.
 * primary · shortcut 은 최상위만 보낸다(하위로 옮길 때 이미 꺼지지만 한 번 더 지킨다).
 */
export function contentOf(tree: readonly MenuNode[]): NavItemPayload[] {
  return flatten(tree).map((row) => ({
    key: row.node.key,
    parentKey: row.parentKey,
    type: row.node.type,
    labels: { ko: trimmed(row.node.labelKo), en: trimmed(row.node.labelEn) },
    descriptions: descriptionsOf(row.node),
    ...target(row.node),
    displayOrder: row.index,
    visible: row.node.visible,
    primary: row.depth === 0 && row.node.primary,
    shortcut: row.depth === 0 && row.node.shortcut,
  }));
}

/** 최신 저장본과 입력이 다른가 — 다르면 저장 전 입력이다(게시는 저장한 최신 리비전만 할 수 있다) */
export function isDirty(tree: readonly MenuNode[], base: NavDetail | null): boolean {
  return JSON.stringify(contentOf(tree)) !== JSON.stringify(contentOf(formOf(base)));
}

/** 저장하지 않은 변경 건수 — 더하거나 지웠거나 내용 · 자리가 바뀐 항목 수 */
export function changeCount(tree: readonly MenuNode[], base: NavDetail | null): number {
  const before = new Map(contentOf(formOf(base)).map((item) => [item.key, JSON.stringify(item)]));
  const after = new Map(contentOf(tree).map((item) => [item.key, JSON.stringify(item)]));
  let count = 0;
  for (const [key, value] of after) if (before.get(key) !== value) count++;
  for (const key of before.keys()) if (!after.has(key)) count++;
  return count;
}

/** 줄 이름 — 고른 언어 표시명, 비었으면 다른 언어, 그것도 비면 '(이름 없음)' */
export function nodeName(node: MenuNode, locale: 'ko' | 'en' = 'ko'): string {
  const first = locale === 'ko' ? node.labelKo : node.labelEn;
  const second = locale === 'ko' ? node.labelEn : node.labelKo;
  return first.trim() || second.trim() || '(이름 없음)';
}

export const LABEL_INVALID = `표시명을 공백이 아닌 ${LIMITS.label}자 이내로 입력해 주세요(줄바꿈 · 제어 문자 제외).`;
export const DESCRIPTION_INVALID = `설명은 비워 두거나, 한국어 · 영어 모두 ${LIMITS.description}자 이내로 입력해 주세요(줄바꿈 · 제어 문자 제외).`;
export const PATH_INVALID = `/ 로 시작하는 쇼핑몰 안 경로를 입력해 주세요(예: /shop). 영문 · 숫자와 - . _ ~ / 만 쓸 수 있고, // 와 . · .. 구간은 쓸 수 없습니다. ${LIMITS.path}자 이내.`;
export const URL_INVALID =
  'https:// 로 시작하는 전체 주소를 입력해 주세요. 주소 뒷부분(경로)에는 영문 · 숫자와 - . _ ~ / 만 쓸 수 있고, 포트 · 계정 정보 · 물음표(?) · 샵(#) · % · 공백은 쓸 수 없습니다.';
export const URL_HOST_NOT_ALLOWED =
  '허용되지 않은 사이트입니다. 다른 사이트 링크는 운영 설정에 등록된 사이트만 쓸 수 있습니다. 필요하면 운영 담당자에게 등록을 요청해 주세요.';
export const CATEGORY_INVALID = '연결할 카테고리를 골라 주세요.';
export const TOO_MANY_ITEMS = `메뉴는 숨김을 포함해 ${LIMITS.items}개까지 둘 수 있습니다.`;
export const TOO_MANY_SIBLINGS = `한 메뉴 아래에는 ${LIMITS.siblings}개까지 둘 수 있습니다.`;
export const STRUCTURE_INVALID =
  '메뉴 구조를 저장하지 못했습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.';
export const TOO_DEEP = `메뉴는 ${LIMITS.depth}단계까지 둘 수 있습니다.`;

// BE 와 같은 기준 — Java `\p{Cc}`(C0 · DEL · C1)
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;

/**
 * 쇼핑몰 안 경로의 문제 문구. 없으면 null. BE `StoreNavigationUrlPolicy` 와 같다 — `/` 로 시작하고 영문 · 숫자 · `- . _ ~ /` 만,
 * `.` · `..` 구간 금지, 512자. `//` 는 위치와 관계없이 막는다(서버보다 엄격할 수 있다 — 스토어프론트도 그 항목을 그리지 않는다).
 */
export function pathProblem(path: string): string | null {
  if (
    path.length === 0 ||
    path.length > LIMITS.path ||
    !/^\/[A-Za-z0-9/._~-]*$/.test(path) ||
    path.includes('//')
  )
    return PATH_INVALID;
  if (
    path
      .slice(1)
      .split('/')
      .some((segment) => segment === '.' || segment === '..')
  )
    return PATH_INVALID;
  return null;
}

/**
 * 다른 사이트 주소의 형식 문제. 없으면 null. BE `allowed-external-hosts` 의 허용 사이트 목록은 서버 배포 설정이라 여기서는 형식만 본다 —
 * https, 공개 DNS 호스트(IP · localhost · 내부 도메인 금지), 포트 · 계정 정보 · 질의 · fragment · `%` 없음,
 * 경로는 영문 · 숫자 · `- . _ ~ /` 만(BE `StoreNavigationUrlPolicy` 와 같다).
 */
export function externalUrlProblem(url: string): string | null {
  if (url === '' || url.length > LIMITS.url || /[^\x21-\x7e]|[\\%]/.test(url)) return URL_INVALID;
  const match = /^https:\/\/([A-Za-z0-9.-]+)(\/[^?#]*)?$/i.exec(url);
  if (!match) return URL_INVALID;
  if (!externalHost((match[1] ?? '').toLowerCase())) return URL_INVALID;
  const path = match[2] ?? '';
  if (
    !/^[A-Za-z0-9/._~-]*$/.test(path) ||
    path.split('/').some((segment) => segment === '.' || segment === '..')
  )
    return URL_INVALID;
  return null;
}

export const categoryProblem = (categoryId: string): string | null =>
  /^[1-9]\d{0,17}$/.test(categoryId.trim()) ? null : CATEGORY_INVALID;

/** 유형별 링크 대상의 문제 */
export function targetProblem(node: MenuNode): string | null {
  switch (node.type) {
    case 'INTERNAL':
      return pathProblem(node.path.trim());
    case 'EXTERNAL':
      return externalUrlProblem(node.externalUrl.trim());
    case 'CATEGORY':
      return categoryProblem(node.categoryId);
    case 'NONE':
      return null;
  }
}

/** 오류 칸 이름 — 유형마다 다르다(서버 fieldErrors 의 `items[i].url` · `items[i].categoryId` 와 같다) */
export const targetField = (type: NavType): 'url' | 'categoryId' | null =>
  type === 'CATEGORY' ? 'categoryId' : type === 'NONE' ? null : 'url';

const textInvalid = (value: string, max: number) =>
  value.trim() === '' || value.trim().length > max || CONTROL.test(value);

/**
 * 초안 저장 전 검사. 오류 키는 서버 fieldErrors 를 `fieldKey` 로 바꾼 것과 같다
 * (`items.0.labels.ko`, `items.0.descriptions.en`, `items.0.url`, `items.0.categoryId`). 번호는 저장 본문 순서(트리 전위)다.
 * 숨김 항목도 같은 규칙이다(나중에 노출로 바꿔도 안전해야 한다 — 서버와 같다).
 */
export function draftErrors(tree: readonly MenuNode[]): FormErrors {
  const errors: FormErrors = {};
  const rows = flatten(tree);
  if (countAll(tree) > LIMITS.items) errors.items = [TOO_MANY_ITEMS];
  if (rows.some((row) => row.siblingCount > LIMITS.siblings))
    errors.items = [...(errors.items ?? []), TOO_MANY_SIBLINGS];
  if (rows.some((row) => row.depth >= LIMITS.depth))
    errors.items = [...(errors.items ?? []), TOO_DEEP];
  rows.forEach((row, index) => {
    const { node } = row;
    if (textInvalid(node.labelKo, LIMITS.label))
      errors[`items.${index}.labels.ko`] = [LABEL_INVALID];
    if (textInvalid(node.labelEn, LIMITS.label))
      errors[`items.${index}.labels.en`] = [LABEL_INVALID];
    if (descriptionsOf(node) !== null) {
      if (textInvalid(node.descriptionKo, LIMITS.description))
        errors[`items.${index}.descriptions.ko`] = [DESCRIPTION_INVALID];
      if (textInvalid(node.descriptionEn, LIMITS.description))
        errors[`items.${index}.descriptions.en`] = [DESCRIPTION_INVALID];
    }
    const field = targetField(node.type);
    const problem = targetProblem(node);
    if (field && problem) errors[`items.${index}.${field}`] = [problem];
  });
  // 화면이 만든 key 라 틀릴 일이 없지만, 서버 형식을 벗어나면 저장 전에 막는다
  if (rows.some((row) => !validKey(row.node.key)))
    errors.items = [...(errors.items ?? []), STRUCTURE_INVALID];
  return errors;
}

/** 이 줄(저장 본문 번호)에 오류가 있는가 */
export const rowHasError = (errors: FormErrors, index: number) =>
  Object.keys(errors).some((key) => key.startsWith(`items.${index}.`));

/** 링크 대상 요약 — 트리 줄 · 미리보기 보조 글자 */
export function targetSummary(
  node: MenuNode,
  categoryName?: (id: string) => string | null,
): string {
  switch (node.type) {
    case 'INTERNAL':
      return node.path.trim() || '경로 없음';
    case 'EXTERNAL':
      return node.externalUrl.trim().replace(/^https:\/\//i, '') || '주소 없음';
    case 'CATEGORY': {
      const id = node.categoryId.trim();
      if (!id) return '카테고리 없음';
      return categoryName?.(id) ?? `번호 ${id}`;
    }
    case 'NONE':
      return TYPE_LABELS.NONE;
  }
}

/** 공개 상태 한 줄 — 초안 저장은 공개되지 않고 게시만 공개된다는 것을 같이 알린다 */
export function publicStateText(detail: NavDetail | null): string {
  if (!detail)
    return '아직 저장한 메뉴가 없습니다. 스토어프론트에는 기본 메뉴(브랜드 설정)가 보입니다.';
  const { latestRevision, publishedRevision } = detail;
  if (publishedRevision === null)
    return `${latestRevision}번 초안까지 저장했습니다. 아직 게시하지 않아 고객에게는 기본 메뉴가 보입니다.`;
  if (publishedRevision === latestRevision)
    return `${publishedRevision}번이 공개 중입니다(최신 리비전).`;
  return `${publishedRevision}번이 공개 중입니다. 최신 ${latestRevision}번 초안은 게시 전이라 고객에게 보이지 않습니다.`;
}

/** 지금 최신 리비전을 게시할 수 있는가 */
export function publishState(
  tree: readonly MenuNode[],
  base: NavDetail | null,
): 'none' | 'published' | 'dirty' | 'ready' {
  if (!base) return 'none';
  if (isDirty(tree, base)) return 'dirty';
  if (base.publishedRevision === base.latestRevision) return 'published';
  return 'ready';
}

/** 칸 → 그 칸이 바뀌면 지울 오류 키(`items.{i}.` 뒤). 설명은 짝 검사라 두 언어를 함께, 링크는 유형 · 대상을 함께 지운다 */
const FIELD_ERRORS: [keyof MenuNode, string[]][] = [
  ['labelKo', ['labels.ko']],
  ['labelEn', ['labels.en']],
  ['descriptionKo', ['descriptions.ko', 'descriptions.en']],
  ['descriptionEn', ['descriptions.ko', 'descriptions.en']],
  ['type', ['url', 'categoryId']],
  ['path', ['url']],
  ['externalUrl', ['url']],
  ['categoryId', ['categoryId']],
];

/** 항목을 고친 뒤의 오류 — 바뀐 칸의 서버 · 저장 전 오류만 지운다(다른 칸 · 폼 전체 오류는 둔다) */
export function clearedErrors(
  errors: FormErrors,
  index: number,
  before: MenuNode,
  after: MenuNode,
): FormErrors {
  const stale = new Set(
    FIELD_ERRORS.filter(([field]) => before[field] !== after[field]).flatMap(([, keys]) =>
      keys.map((key) => `items.${index}.${key}`),
    ),
  );
  if (stale.size === 0) return errors;
  return Object.fromEntries(Object.entries(errors).filter(([key]) => !stale.has(key)));
}
