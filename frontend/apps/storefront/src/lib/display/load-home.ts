import { getI18n } from '@/lib/i18n/server';
import { serverFetch } from '@/lib/server-api';
import { resolveHomeBlocks, type HomeBlock } from './home-sections';

export const DISPLAY_MAIN_PATH = '/api/v1/displays/main';

/**
 * 메인 전시 응답을 서버 데이터 캐시에 두는 시간(초). BE 응답의 `Cache-Control: max-age=60, public` 과 같은 값이다 —
 * 어드민 변경이 메인에 보이기까지 늦어질 수 있는 시간이다(만료 뒤 첫 요청은 이전 값을 받고 뒤에서 새로 받는다).
 */
export const DISPLAY_MAIN_REVALIDATE_SEC = 60;

/**
 * 지금 언어의 메인 블록. 전시 API 는 공개 · 사용자 무관이라 토큰 없이 부르고 60초 캐시한다.
 * 실패(장애 · 봉투 아님 · 모양 불일치)나 그릴 섹션이 없으면 브랜드 설정(content.home)이다 — 메인이 깨지지 않는다.
 * 200 응답만 캐시되므로 실패는 다음 요청에서 다시 부른다.
 */
export async function loadHomeBlocks(): Promise<HomeBlock[]> {
  const { dict, content } = await getI18n();
  const body = await serverFetch<unknown>(
    DISPLAY_MAIN_PATH,
    { cache: 'force-cache', next: { revalidate: DISPLAY_MAIN_REVALIDATE_SEC } },
    { anonymous: true },
  ).catch(() => null);
  return resolveHomeBlocks(body, content.home, dict.home);
}
