import type { FooterLink } from '@cf/ui';
import { cx } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import { businessVerificationUrl, type BusinessInfo } from '@/lib/business-info';
import type { Dictionary } from '@/lib/i18n/dictionaries';

export type SiteFooterProps = {
  brandName: string;
  links: FooterLink[];
  business: BusinessInfo;
  labels: Dictionary['footer'];
  /** 저작권 연도. 테스트에서 고정하려고 받는다 */
  year?: number;
};

/**
 * 스토어프론트 공통 푸터. 고객 안내 링크(브랜드 설정)와 사업자 정보(서버 env)를 둔다.
 * 사업자 정보는 값이 있는 줄만 그린다 — 로컬 · 미설정 환경에서도 깨지지 않는다.
 */
export function SiteFooter({
  brandName,
  links,
  business,
  labels,
  year = new Date().getFullYear(),
}: SiteFooterProps) {
  const rows: { label: string; value: string | null }[] = [
    { label: labels.businessName, value: business.name },
    { label: labels.representative, value: business.representative },
    { label: labels.registrationNumber, value: business.registrationNumber },
    { label: labels.mailOrderNumber, value: business.mailOrderNumber },
    { label: labels.address, value: business.address },
    { label: labels.contact, value: business.contact },
  ];
  const shown = rows.filter((row): row is { label: string; value: string } => row.value !== null);
  const verifyUrl = businessVerificationUrl(business.registrationNumber);

  return (
    // 위 내용과는 선 대신 배경 톤 한 단계(muted)로 나눈다
    <footer className="bg-muted">
      <div className="mx-auto flex max-w-screen-xl flex-col gap-10 px-4 py-12 lg:flex-row lg:justify-between lg:gap-16 lg:px-8 lg:py-16">
        <div className="flex flex-col gap-6">
          <Link href="/" className="font-display text-2xl tracking-[0.25em] uppercase">
            {brandName}
          </Link>
          {links.length > 0 && (
            <nav aria-label={labels.customerLinks}>
              <ul className="flex flex-wrap gap-x-6 gap-y-2 text-xs">
                {links.map((link) => (
                  <li key={`${link.href}-${link.label}`}>
                    <Link
                      href={link.href}
                      className={cx(
                        'hover:underline',
                        link.emphasis ? 'font-semibold' : 'text-muted-fg',
                      )}
                    >
                      {link.label}
                    </Link>
                  </li>
                ))}
              </ul>
            </nav>
          )}
        </div>

        {shown.length > 0 && (
          <section
            aria-label={labels.business}
            className="text-xs leading-6 text-muted-fg lg:max-w-xl"
          >
            <dl className="flex flex-wrap gap-x-4">
              {shown.map((row) => (
                <div key={row.label} className="flex gap-1">
                  <dt>{row.label}</dt>
                  <dd className="text-fg">{row.value}</dd>
                </div>
              ))}
            </dl>
            {verifyUrl && (
              <a
                href={verifyUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="mt-2 inline-block underline underline-offset-4 hover:text-fg"
              >
                {labels.verify}
              </a>
            )}
          </section>
        )}
      </div>

      <p className="px-4 pt-4 pb-8 text-center text-xs tracking-[0.15em] text-muted-fg">
        © {year} {brandName}
      </p>
    </footer>
  );
}
