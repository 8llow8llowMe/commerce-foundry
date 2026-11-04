import type { FooterLink, HomeMediaSlot } from '@cf/ui';
import { cx } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import { businessVerificationUrl, registrationLabel } from '@/lib/business-info';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import type { StoreProfile, StoreProfileState } from '@/lib/store-profile';
import { FooterSurface } from './FooterSurface';
import { Wordmark } from './Wordmark';

export type SiteFooterProps = {
  /** 워드마크(브랜드 패키지 — 로고와 같은 층) */
  brandName: string;
  links: FooterLink[];
  /** 쇼핑몰 공개 정보(#566). 저작권 표시 이름은 등록된 쇼핑몰 이름, 없으면 brandName */
  store: StoreProfileState;
  labels: Dictionary['footer'];
  /** 메인에서만 까는 전폭 화보(브랜드 설정). null 이면 어디서나 muted 면 */
  backdrop?: HomeMediaSlot | null;
  /** 저작권 연도. 테스트에서 고정하려고 받는다 */
  year?: number;
};

/**
 * 스토어프론트 공통 푸터(#59 푸터). 위 줄: 고객 안내 링크(브랜드 설정) · 고객센터 연락처,
 * 가운데: 화면 폭 가득 워드마크, 아래: 법정 표시 정보(쇼핑몰 공개 정보 API, #568) 13px. 모바일은 링크 1열 → 워드마크 → 법정 정보.
 * 미등록이면 사업자 · 고객센터 블록을 그리지 않고, 장애면 지난 값 대신 안내 한 줄만 둔다 — 어느 쪽도 푸터가 깨지지 않는다.
 * 등록번호 이름 · 확인 링크는 국가 코드로 고른다(`lib/business-info.ts`).
 * 보조 글자는 바탕을 따라 바뀐다 — muted 면에선 muted-fg, 화보 위에선 Porcelain 을 조금 낮춘 불투명도.
 */
export function SiteFooter({
  brandName,
  links,
  store,
  labels,
  backdrop = null,
  year = new Date().getFullYear(),
}: SiteFooterProps) {
  const profile = store.kind === 'registered' ? store.profile : null;
  const rows: { label: string; value: string | null }[] = profile
    ? [
        { label: labels.businessName, value: profile.companyName },
        { label: labels.representative, value: profile.representativeName },
        {
          label: registrationLabel(labels, profile.countryCode),
          value: profile.businessRegistrationNumber,
        },
        { label: labels.mailOrderNumber, value: profile.mailOrderRegistrationNumber },
        { label: labels.address, value: profile.address },
      ]
    : [];
  const shown = rows.filter((row): row is { label: string; value: string } => row.value !== null);
  const verifyUrl =
    profile && businessVerificationUrl(profile.countryCode, profile.businessRegistrationNumber);
  const displayName = profile?.storeName ?? brandName;
  const subtle = 'text-muted-fg group-data-backdrop/footer:text-bg/85';

  return (
    <FooterSurface backdrop={backdrop}>
      <div className="mx-auto flex max-w-screen-2xl flex-col gap-12 px-4 pt-14 pb-8 lg:gap-20 lg:px-8 lg:pt-24">
        {(links.length > 0 || profile) && (
          <div className="flex flex-col gap-8 lg:flex-row lg:items-start lg:justify-between">
            {links.length > 0 && (
              <nav aria-label={labels.customerLinks}>
                <ul className="flex flex-col gap-3 text-sm lg:flex-row lg:gap-x-8">
                  {links.map((link) => (
                    <li key={`${link.href}-${link.label}`}>
                      <Link
                        href={link.href}
                        className={cx('hover:underline', link.emphasis ? 'font-semibold' : subtle)}
                      >
                        {link.label}
                      </Link>
                    </li>
                  ))}
                </ul>
              </nav>
            )}
            {profile && <CustomerService profile={profile} labels={labels} subtle={subtle} />}
          </div>
        )}

        <Link href="/" aria-label={brandName} className="block">
          <Wordmark text={brandName} />
        </Link>

        <div className={cx('flex flex-col gap-3 text-xs leading-6', subtle)}>
          {store.kind === 'unavailable' && <p role="status">{labels.businessUnavailable}</p>}
          {shown.length > 0 && (
            <section aria-label={labels.business}>
              <dl className="flex flex-wrap gap-x-4">
                {shown.map((row) => (
                  <div key={row.label} className="flex gap-1">
                    <dt>{row.label}</dt>
                    <dd>{row.value}</dd>
                  </div>
                ))}
              </dl>
              {verifyUrl && (
                <a
                  href={verifyUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="underline underline-offset-4 hover:opacity-80"
                >
                  {labels.verify}
                </a>
              )}
            </section>
          )}
          <p className="tracking-[0.15em]">
            © {year} {displayName}
          </p>
        </div>
      </div>
    </FooterSurface>
  );
}

/** 고객센터 — 전화(선택) · 이메일(필수) · 운영 시간(선택). 전화는 숫자와 + 만 남겨 tel 링크로 */
function CustomerService({
  profile,
  labels,
  subtle,
}: {
  profile: StoreProfile;
  labels: Dictionary['footer'];
  subtle: string;
}) {
  const phone = profile.customerServicePhone;
  const dial = phone?.replace(/[^\d+]/g, '');

  return (
    <section aria-label={labels.contact} className="flex flex-col gap-1 text-sm lg:items-end">
      <span className={subtle}>{labels.contact}</span>
      {phone &&
        (dial ? (
          <a href={`tel:${dial}`} className="text-lg">
            {phone}
          </a>
        ) : (
          <span className="text-lg">{phone}</span>
        ))}
      <a href={`mailto:${profile.customerServiceEmail}`} className="hover:underline">
        <span className="sr-only">{labels.email} </span>
        {profile.customerServiceEmail}
      </a>
      {profile.customerServiceHours && (
        <p className={subtle}>
          <span className="sr-only">{labels.hours} </span>
          {profile.customerServiceHours}
        </p>
      )}
    </section>
  );
}
