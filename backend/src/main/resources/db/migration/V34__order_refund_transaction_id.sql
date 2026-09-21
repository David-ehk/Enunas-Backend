-- Mollie refund id for an admin cancellation of a paid order. Denormalised from the ledger's
-- external_reference_id so the admin dashboard need not join ledger_entries. Null for orders that
-- were never paid, and for every order predating this column.
ALTER TABLE orders ADD COLUMN refund_transaction_id VARCHAR(64);
