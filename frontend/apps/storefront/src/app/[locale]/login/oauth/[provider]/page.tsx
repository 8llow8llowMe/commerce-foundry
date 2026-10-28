import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { SocialCallback } from '@/components/auth/SocialCallback';
import { isSocialProvider } from '@/lib/auth/social';
import { getI18n } from '@/lib/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return {
    title: dict.auth.social.callbackTitle,
    // 일회용 콜백 화면이다 — 검색 결과에 내지 않는다
    robots: { index: false },
  };
}

/**
 * 소셜 로그인 콜백(#25 · BE #5). 제공자 콘솔 · BE `KAKAO_REDIRECT_URI` · `NAVER_REDIRECT_URI` 에 등록하는 주소는
 * `<스토어프론트 origin>/login/oauth/{kakao|naver}` 다. 쿼리(code · state · error)는 서버에서 읽지 않는다 —
 * 브라우저가 sessionStorage 의 흐름과 맞춰 보고 곧바로 주소에서 지운다(`SocialCallback`).
 */
export default async function SocialCallbackPage({
  params,
}: PageProps<'/[locale]/login/oauth/[provider]'>) {
  const { provider } = await params;
  if (!isSocialProvider(provider)) notFound();
  const { dict } = await getI18n();

  return (
    <main className="mx-auto flex w-full max-w-sm flex-col gap-10 px-4 py-16 lg:py-24">
      <h1 className="text-center font-serif text-[1.75rem] leading-tight">
        {dict.auth.social.callbackTitle}
      </h1>
      <SocialCallback provider={provider} />
    </main>
  );
}
