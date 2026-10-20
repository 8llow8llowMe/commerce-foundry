'use client';

import { useDictionary } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';

/** 회원 API 가 401 — 세션이 끝났다. 로그인 뒤 `returnPath`(언어 없는 앱 경로)로 돌아온다 */
export function SignedOutNotice({ returnPath }: { returnPath: string }) {
  const t = useDictionary().account;
  return (
    <div className="flex flex-col items-center gap-4 py-16 text-center">
      <p role="alert" className="text-sm text-muted-fg">
        {t.signInRequired}
      </p>
      <Link
        href={`/login?next=${encodeURIComponent(returnPath)}`}
        className="text-sm underline underline-offset-4"
      >
        {t.login}
      </Link>
    </div>
  );
}
