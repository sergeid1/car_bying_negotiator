ALTER TABLE operations ADD COLUMN idempotency_key VARCHAR(200);
CREATE UNIQUE INDEX uq_operations_idempotency_key ON operations(idempotency_key);
