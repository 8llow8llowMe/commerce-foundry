-- #468 PR-C. PostgreSQL 17 / operations schema.
-- Apply this additive DDL before deploying the application that writes provider.
-- Existing rows remain NULL: either provider may have been used before recording began.
-- Never infer or backfill provider from model names. No default or NOT NULL is added.
-- Source: OperationsSchemaSnapshotTest -> build/ddl/operations-schema.sql.
SET client_encoding = 'UTF8';
BEGIN;
ALTER TABLE operations.ai_usage_call ADD COLUMN provider varchar(32);
COMMENT ON COLUMN operations.ai_usage_call.provider IS '예약 시 선택한 AI Provider (anthropic/openai). null은 미기록 — 모델 이름으로 추정하지 않음';
COMMIT;
