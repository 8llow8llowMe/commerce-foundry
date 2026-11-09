import type { FormErrors } from '@/lib/api-errors';
import {
  LIMITS,
  type FooterDetail,
  type FooterDraftRequest,
  type FooterLink,
  type FooterLinkType,
} from './types';

/** 편집기의 링크 한 줄. key 는 화면에서만 쓰는 정렬 · 포커스 식별자다(요청 · 응답에 링크 식별자는 없다) */
export type LinkRow = {
  key: string;
  type: FooterLinkType;
  labelKo: string;
  labelEn: string;
  url: string;
  visible: boolean;
};

/** 편집기 입력. 저장 전까지 화면에만 있다 — 미리보기도 이 값으로 그린다(저장 · 게시 없이) */
export type FooterForm = { copyrightKo: string; copyrightEn: string; links: LinkRow[] };

let seq = 0;
export const newKey = () => `link-${++seq}`;

export const blankRow = (type: FooterLinkType = 'INSTAGRAM'): LinkRow => ({
  key: newKey(),
  type,
  labelKo: '',
  labelEn: '',
  url: '',
  visible: true,
});

/** 최신 저장본 → 편집기. 순서는 displayOrder 오름차순(숨김 포함) */
export function formOf(detail: FooterDetail | null): FooterForm {
  if (!detail) return { copyrightKo: '', copyrightEn: '', links: [] };
  const { copyrightText, links } = detail.revision;
  return {
    copyrightKo: copyrightText.ko ?? '',
    copyrightEn: copyrightText.en ?? '',
    links: [...links]
      .sort((a, b) => a.displayOrder - b.displayOrder)
      .map((link) => ({
        key: newKey(),
        type: link.type,
        labelKo: link.labels.ko,
        labelEn: link.labels.en,
        url: link.url,
        visible: link.visible,
      })),
  };
}

/** 저장 본문의 내용 부분 — 화면 순서가 곧 displayOrder(0부터). 빈 저작권은 null(그 언어는 표시 안 함) */
export function contentOf(form: FooterForm): Omit<FooterDraftRequest, 'expectedVersion'> {
  const copy = (value: string) => (value.trim() === '' ? null : value.trim());
  return {
    copyrightText: { ko: copy(form.copyrightKo), en: copy(form.copyrightEn) },
    links: form.links.map((row, index): FooterLink => ({
      type: row.type,
      labels: { ko: row.labelKo.trim(), en: row.labelEn.trim() },
      url: row.url,
      displayOrder: index,
      visible: row.visible,
    })),
  };
}

/** 최신 저장본과 입력이 다른가 — 다르면 저장 전 입력이다(게시는 저장한 최신 리비전만 할 수 있다) */
export function isDirty(form: FooterForm, base: FooterDetail | null): boolean {
  return JSON.stringify(contentOf(form)) !== JSON.stringify(contentOf(formOf(base)));
}

export const LABEL_INVALID =
  '표시명을 공백이 아닌 80자 이내로 입력해 주세요(줄바꿈 · 제어 문자 제외).';
export const COPYRIGHT_INVALID =
  '저작권 문구는 200자 이내로 입력해 주세요(줄바꿈 · 제어 문자 제외).';
export const URL_FORMAT =
  'https:// 로 시작하는 전체 주소를 입력해 주세요. 포트 · 계정 정보 · 물음표(?) · 샵(#) · 공백은 쓸 수 없습니다.';
export const URL_INSTAGRAM =
  'Instagram 계정 주소를 입력해 주세요. 예: https://www.instagram.com/계정이름';
export const URL_KAKAO =
  '카카오톡 채널 주소를 입력해 주세요. 예: https://pf.kakao.com/_채널ID 또는 그 뒤에 /chat';
export const URL_CUSTOM_HOST =
  '허용되지 않은 도메인입니다. 기타 링크는 운영 설정에 등록된 도메인만 쓸 수 있습니다. 필요하면 운영 담당자에게 도메인 등록을 요청해 주세요.';
export const TOO_MANY_LINKS = `링크는 숨김을 포함해 ${LIMITS.links}개까지 둘 수 있습니다.`;

// BE 와 같은 기준 — Java `\p{Cc}` / `Character.isISOControl`(C0 · DEL · C1)
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;
const RESERVED_TLDS = new Set([
  'localhost',
  'local',
  'localdomain',
  'internal',
  'lan',
  'home',
  'corp',
  'invalid',
  'test',
  'onion',
]);

/** 공개 DNS 호스트인가 — IP · localhost · 내부 도메인 · 밑줄 등은 아니다(BE `externalDnsHost` 와 같다) */
export function externalHost(host: string): boolean {
  if (host.length > 253 || !/^[a-z0-9-]+(\.[a-z0-9-]+)+$/.test(host)) return false;
  const labels = host.split('.');
  const tld = labels.at(-1) ?? '';
  if (!/^[a-z]{2,63}$/.test(tld) || RESERVED_TLDS.has(tld)) return false;
  return labels.every(
    (label) => label.length <= 63 && !label.startsWith('-') && !label.endsWith('-'),
  );
}

/** `%XX` 는 영문 · 숫자 · `-._~` 만 풀어 준다. 그 밖의 인코딩(구분자 · 이중 인코딩)은 null */
function decodePath(raw: string): string | null {
  let out = '';
  for (let index = 0; index < raw.length; index++) {
    const char = raw[index];
    if (char !== '%') {
      out += char;
      continue;
    }
    const hex = raw.slice(index + 1, index + 3);
    if (!/^[0-9a-fA-F]{2}$/.test(hex)) return null;
    const decoded = String.fromCharCode(parseInt(hex, 16));
    if (!/^[A-Za-z0-9\-._~]$/.test(decoded)) return null;
    out += decoded;
    index += 2;
  }
  return out;
}

/**
 * 링크 주소의 문제 문구. 문제가 없으면 null. BE `StoreFooterUrlPolicy` 를 화면에서 먼저 따라 본다 —
 * 서버가 저장 · 게시 때 다시 검사하며, 기타 링크(CUSTOM)의 허용 도메인 목록은 서버 배포 설정이라 여기서는 형식만 본다.
 */
export function urlProblem(type: FooterLinkType, url: string): string | null {
  if (url === '' || url.length > LIMITS.url || /[^\x21-\x7e]|\\/.test(url)) return URL_FORMAT;
  // scheme · host 만 소문자로 비교한다. 계정 정보(@) · 포트(:) 는 호스트 칸에 들어갈 수 없다
  const match = /^https:\/\/([A-Za-z0-9.-]+)(\/[^?#]*)?$/i.exec(url);
  if (!match) return URL_FORMAT;
  const host = (match[1] ?? '').toLowerCase();
  if (!externalHost(host)) return URL_FORMAT;
  const path = decodePath(match[2] ?? '');
  if (
    path === null ||
    !/^[A-Za-z0-9/._~!$&'()*+,;=:@-]*$/.test(path) ||
    path.split('/').some((segment) => segment === '.' || segment === '..')
  )
    return URL_FORMAT;
  if (type === 'INSTAGRAM')
    return ['instagram.com', 'www.instagram.com'].includes(host) &&
      /^\/[A-Za-z0-9_][A-Za-z0-9._]{0,29}\/?$/.test(path)
      ? null
      : URL_INSTAGRAM;
  if (type === 'KAKAO_CHANNEL')
    return host === 'pf.kakao.com' && /^\/_[A-Za-z0-9-]+(\/chat)?$/.test(path) ? null : URL_KAKAO;
  return null;
}

const labelInvalid = (value: string) =>
  value.trim() === '' || value.trim().length > LIMITS.label || CONTROL.test(value);
const copyrightInvalid = (value: string) =>
  value.trim().length > LIMITS.copyright || CONTROL.test(value);

/**
 * 초안 저장 전 검사. 오류 키는 서버 fieldErrors 를 `fieldKey` 로 바꾼 것과 같다
 * (`links.0.labels.ko`, `links.0.url`, `copyrightText.ko`). 숨김 링크도 같은 규칙이다.
 */
export function draftErrors(form: FooterForm): FormErrors {
  const errors: FormErrors = {};
  if (copyrightInvalid(form.copyrightKo)) errors['copyrightText.ko'] = [COPYRIGHT_INVALID];
  if (copyrightInvalid(form.copyrightEn)) errors['copyrightText.en'] = [COPYRIGHT_INVALID];
  if (form.links.length > LIMITS.links) errors.links = [TOO_MANY_LINKS];
  form.links.forEach((row, index) => {
    if (labelInvalid(row.labelKo)) errors[`links.${index}.labels.ko`] = [LABEL_INVALID];
    if (labelInvalid(row.labelEn)) errors[`links.${index}.labels.en`] = [LABEL_INVALID];
    const problem = urlProblem(row.type, row.url);
    if (problem) errors[`links.${index}.url`] = [problem];
  });
  return errors;
}

/** 공개 상태 한 줄 — 초안 저장은 공개되지 않고 게시만 공개된다는 것을 같이 알린다 */
export function publicStateText(detail: FooterDetail | null): string {
  if (!detail)
    return '아직 저장한 푸터 설정이 없습니다. 스토어프론트에는 채널 링크 · 저작권 문구 없이 기본 푸터가 보입니다.';
  const { latestRevision, publishedRevision } = detail;
  if (publishedRevision === null)
    return `${latestRevision}번 초안까지 저장했습니다. 아직 게시하지 않아 고객에게 보이지 않습니다.`;
  if (publishedRevision === latestRevision)
    return `${publishedRevision}번이 공개 중입니다(최신 리비전).`;
  return `${publishedRevision}번이 공개 중입니다. 최신 ${latestRevision}번 초안은 게시 전이라 고객에게 보이지 않습니다.`;
}

/** 지금 최신 리비전을 게시할 수 있는가 */
export function publishState(
  form: FooterForm,
  base: FooterDetail | null,
): 'none' | 'published' | 'dirty' | 'ready' {
  if (!base) return 'none';
  if (isDirty(form, base)) return 'dirty';
  if (base.publishedRevision === base.latestRevision) return 'published';
  return 'ready';
}
