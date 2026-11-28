import { cx } from '@cf/ui';
import Link from 'next/link';
import { CLAIM_PRESETS, claimsHref, isClaimPreset, type ClaimQuery } from '@/lib/claim/query';
import { DEFAULT_QUERY as DEFAULT_PAYMENT_QUERY, paymentsHref } from '@/lib/payment/query';

/** 결제 확인 화면의 "결제 확인 중"(UNKNOWN) — 결제 쪽 환불 실패는 서버 필터가 없어(`PAYMENT_198`) 두지 않는다 */
export const PAYMENT_UNKNOWN_HREF = paymentsHref({ ...DEFAULT_PAYMENT_QUERY, status: 'UNKNOWN' });

const linkClass = (active: boolean) =>
  cx(
    'inline-block rounded-md border px-3 py-1.5',
    active ? 'border-fg font-medium' : 'border-border hover:bg-muted',
  );

/**
 * 클레임 목록 위 "확인이 필요한 건" 바로가기(#30) — 반품 승인 대기 · 환불 실패 · 재고 복원 실패. 지금 조건과 같으면 선택 표시(`aria-current`)한다.
 * 결제 확인 권한(`SETTLEMENT_READ`, 대표 관리자)이 있으면 결제 확인 화면의 "결제 확인 중" 으로 가는 바로가기를 더한다.
 */
export function ClaimQuickFilters({
  query,
  showPayments,
}: {
  query: ClaimQuery;
  /** 결제 확인 화면에 들어갈 수 있는가 — 역할을 모르면 true 로 받는다(서버 403 안내에 맡긴다) */
  showPayments: boolean;
}) {
  return (
    <div className="flex flex-col gap-2">
      <p id="claim-quick-filters" className="text-sm font-medium">
        확인이 필요한 건
      </p>
      <ul aria-labelledby="claim-quick-filters" className="flex flex-wrap gap-2 text-sm">
        {CLAIM_PRESETS.map((preset) => {
          const active = isClaimPreset(query, preset.query);
          return (
            <li key={preset.label}>
              <Link
                href={claimsHref(preset.query)}
                aria-current={active ? 'page' : undefined}
                className={linkClass(active)}
              >
                {preset.label}
              </Link>
            </li>
          );
        })}
        {showPayments && (
          <li>
            <Link href={PAYMENT_UNKNOWN_HREF} className={linkClass(false)}>
              결제 확인 중
            </Link>
          </li>
        )}
      </ul>
    </div>
  );
}
