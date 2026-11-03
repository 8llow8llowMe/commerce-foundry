import type {
  KnowledgeApproveRequest,
  KnowledgeDetail,
  KnowledgePublishRequest,
  KnowledgeStatusCode,
} from './types';

// 상세 화면의 작업 버튼. 상태 전이 정본은 BE `KnowledgeProcessor`(support-knowledge-guide.md):
// 초안(DRAFT) → 승인(APPROVED) → 게시(PUBLISHED). 승인 뒤 내용은 바꾸지 않고 새 초안을 만든다.
// 상세는 최신 리비전만 주므로 작업 대상도 늘 최신 리비전이다.

export type KnowledgeActions = {
  /** draft — 최신 초안 고치기(PUT), revision — 최신 내용을 복사해 새 초안 만들기(POST /revisions) */
  edit: 'draft' | 'revision';
  approve: boolean;
  publish: boolean;
  /** 게시 버튼을 막은 이유(유효기간 밖). 막지 않았으면 null */
  publishBlocked: string | null;
  /** 보관(DELETE). 게시 중인 리비전이면 게시가 해제된다 */
  archive: 'archive' | 'unpublish' | null;
};

export const NOT_YET_VALID = '유효 시작 시각 전이라 아직 게시할 수 없습니다.';
export const EXPIRED =
  '유효 종료 시각이 지나 게시할 수 없습니다. 기간을 고친 새 초안을 만들어 주세요.';

const statusOf = (detail: KnowledgeDetail) => detail.status.code as KnowledgeStatusCode;

/** 서버 게시 조건(`validFrom <= 지금 < validTo`)을 미리 본다. 정본은 서버(어기면 409 `SUPPORT_003`)다 */
export function publishBlockedReason(detail: KnowledgeDetail, now: Date): string | null {
  if (Date.parse(detail.validFrom) > now.getTime()) return NOT_YET_VALID;
  if (detail.validTo !== null && Date.parse(detail.validTo) <= now.getTime()) return EXPIRED;
  return null;
}

export function knowledgeActions(detail: KnowledgeDetail, now: Date): KnowledgeActions {
  const status = statusOf(detail);
  const approved = status === 'APPROVED';
  const blocked = approved ? publishBlockedReason(detail, now) : null;
  return {
    edit: status === 'DRAFT' ? 'draft' : 'revision',
    approve: status === 'DRAFT',
    publish: approved && blocked === null,
    publishBlocked: blocked,
    archive:
      status === 'ARCHIVED'
        ? null
        : detail.publishedRevision === detail.revisionNumber
          ? 'unpublish'
          : 'archive',
  };
}

export const approveRequest = (detail: KnowledgeDetail): KnowledgeApproveRequest => ({
  revisionNumber: detail.revisionNumber,
  expectedVersion: detail.revisionVersion,
});

export const publishRequest = (detail: KnowledgeDetail): KnowledgePublishRequest => ({
  revisionNumber: detail.revisionNumber,
  expectedVersion: detail.revisionVersion,
  expectedDocumentVersion: detail.documentVersion,
});

/** DELETE 는 본문 없이 필수 query 세 개다 */
export function archiveQuery(detail: KnowledgeDetail): string {
  const params = new URLSearchParams({
    revisionNumber: String(detail.revisionNumber),
    expectedVersion: String(detail.revisionVersion),
    expectedDocumentVersion: String(detail.documentVersion),
  });
  return params.toString();
}
