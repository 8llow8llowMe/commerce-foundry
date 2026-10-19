import type { FooterLink, HomeMediaSlot } from '@cf/ui';
import { cx } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import { businessVerificationUrl, type BusinessInfo } from '@/lib/business-info';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { FooterSurface } from './FooterSurface';
import { Wordmark } from './Wordmark';

export type SiteFooterProps = {
  brandName: string;
  links: FooterLink[];
  business: BusinessInfo;
  labels: Dictionary['footer'];
  /** 메인에서만 까는 전폭 화보(브랜드 설정). null 이면 어디서나 muted 면 */
  backdrop?: HomeMediaSlot | null;
  /** 저작권 연도. 테스트에서 고정하려고 받는다 */
  year?: number;
};

/**
 * 스토어프론트 공통 푸터(#59 푸터). 위 줄: 고객 안내 링크(브랜드 설정) · 고객센터 연락처,
 * 가운데: 화면 폭 가득 워드마크, 아래: 법정 표시 정보(서버 env) 13px. 모바일은 링크 1열 → 워드마크 → 법정 정보.
 * 사업자 정보는 값이 있는 줄만 그린다 — 로컬 · 미설정 환경에서도 깨지지 않는다.
 * 보조 글자는 바탕을 따라 바뀐다 — muted 면에선 muted-fg, 화보 위에선 Porcelain 을 조금 낮춘 불투명도.
 */
export function SiteFooter({
  brandName,
  links,
  business,
  labels,
  backdrop = null,
  year = new Date().getFullYear(),
}: SiteFooterProps) {
  const rows: { label: string; value: string | null }[] = [
    { label: labels.businessName, value: business.name },
    { label: labels.representative, value: business.representative },
    { label: labels.registrationNumber, value: business.registrationNumber },
    { label: labels.mailOrderNumber, value: business.mailOrderNumber },
    { label: labels.address, value: business.address },
  ];
  const shown = rows.filter((row): row is { label: string; value: string } => row.value !== null);
  const verifyUrl = businessVerificationUrl(business.registrationNumber);
  const subtle = 'text-muted-fg group-data-backdrop/footer:text-bg/85';

  return (
    <FooterSurface backdrop={backdrop}>
      <div className="mx-auto flex max-w-screen-2xl flex-col gap-12 px-4 pt-14 pb-8 lg:gap-20 lg:px-8 lg:pt-24">
        {(links.length > 0 || business.contact) && (
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
            {business.contact && (
              <p className="flex flex-col gap-1 text-sm lg:items-end">
                <span className={subtle}>{labels.contact}</span>
                <a href={`tel:${business.contact.replace(/[^\d+]/g, '')}`} className="text-lg">
                  {business.contact}
                </a>
              </p>
            )}
          </div>
        )}

        <Link href="/" aria-label={brandName} className="block">
          <Wordmark text={brandName} />
        </Link>

        <div className={cx('flex flex-col gap-3 text-xs leading-6', subtle)}>
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
            © {year} {brandName}
          </p>
        </div>
      </div>
    </FooterSurface>
  );
}
