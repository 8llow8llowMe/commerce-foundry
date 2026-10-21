import type { Metadata } from 'next';
import { LoginForm } from '@/components/auth/LoginForm';
import { rateLimitText, readLoginRateLimit } from '@/lib/auth/rate-limit';
import { safeReturnPath } from '@/lib/auth/return-path';
import { getI18n, localeAlternates } from '@/lib/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return {
    title: dict.auth.login.title,
    alternates: await localeAlternates('/login'),
    // 검색 결과에 로그인 화면을 내지 않는다
    robots: { index: false },
  };
}

/**
 * 고객 로그인(#25). `?next=` 는 로그인 뒤 돌아갈 앱 경로다(언어 접두어 없이). `?signedUp=1` 은 가입 뒤 자동 로그인을 못 했을 때다.
 * `?limited=<초>&reason=<코드>` 는 그 자동 로그인이 요청 제한(429)이었을 때다 — 같은 안내를 보이고 버튼 쿨다운을 잇는다(#184).
 */
export default async function LoginPage({ searchParams }: PageProps<'/[locale]/login'>) {
  const { dict } = await getI18n();
  const params = await searchParams;
  const { next, signedUp } = params;
  const rateLimit = readLoginRateLimit(params, dict.auth);

  return (
    <main className="mx-auto flex w-full max-w-sm flex-col gap-10 px-4 py-16 lg:py-24">
      <h1 className="text-center font-serif text-[1.75rem] leading-tight">
        {dict.auth.login.title}
      </h1>
      {signedUp === '1' && (
        <p role="status" className="-mt-4 text-center text-sm text-muted-fg">
          {dict.auth.login.signedUp}
          {rateLimit && ` ${rateLimitText(rateLimit.code, rateLimit.retryAfterSec, dict.auth)}`}
        </p>
      )}
      <LoginForm returnPath={safeReturnPath(next)} rateLimited={rateLimit ?? undefined} />
    </main>
  );
}
