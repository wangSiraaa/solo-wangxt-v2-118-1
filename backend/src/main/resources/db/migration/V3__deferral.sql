-- ============================================================================
-- Batch-scoped deferral ("本次暂缓") support.
--
-- A treasurer may keep individual claims out of a single trial batch while
-- their vouchers are still being prepared. The choice lives only on this
-- batch's exclusion rows (reason_code = 'DEFERRED'); the original claim is
-- never touched and joins the pool again on the next run when not selected.
--
-- amount/currency snapshot the excluded claim so that exclusion rows make the
-- resulting amount movement auditable on their own.
-- ============================================================================

ALTER TABLE batch_exclusion ADD COLUMN amount   DECIMAL(20,2);
ALTER TABLE batch_exclusion ADD COLUMN currency VARCHAR(3);
