import type { Locale } from '@cf/ui';
import { en } from './en';
import { ko, type Dictionary } from './ko';

export type { Dictionary };

const dictionaries: Record<Locale, Dictionary> = { ko, en };

/** 언어의 UI 문구. 서버에서 읽고, 클라이언트 컴포넌트에는 필요한 문구만 props 로 넘긴다 */
export function getDictionary(locale: Locale): Dictionary {
  return dictionaries[locale];
}
