'use client';

import { SignedOutNotice } from '@/components/account/SignedOutNotice';
import { useDictionary } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import { SOCIAL_LINK_PAGE, type SocialLinkFailure } from '@/lib/auth/social-link';

/**
 * 계정 연결(#356) 콜백 실패 화면. state 는 이미 쓰였으므로 연결 화면(비밀번호 확인부터)으로 돌려보낸다.
 * 세션이 끝났으면(401) 다시 로그인한 뒤 연결 화면으로 돌아온다.
 */
export function SocialLinkFailureView({ failure }: { failure: SocialLinkFailure }) {
  const t = useDictionary().auth;
  if (failure.kind === 'signedOut') return <SignedOutNotice returnPath={SOCIAL_LINK_PAGE} />;
  return (
    <div className="flex flex-col gap-8">
      <p role="alert" className="text-center text-sm">
        {failure.message}
      </p>
      <Link
        href={SOCIAL_LINK_PAGE}
        className="inline-flex h-12 w-full items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-fg"
      >
        {t.socialLink.back}
      </Link>
    </div>
  );
}
