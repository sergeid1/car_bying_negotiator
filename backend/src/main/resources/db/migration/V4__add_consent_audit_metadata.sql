ALTER TABLE consent_records ADD COLUMN actor VARCHAR(40);
ALTER TABLE consent_records ADD COLUMN source VARCHAR(40);
ALTER TABLE consent_records ADD COLUMN schema_version VARCHAR(100);

UPDATE consent_records
SET actor = 'BUYER', source = 'BUYER_API', schema_version = 'consent-record.v1';

ALTER TABLE consent_records ALTER COLUMN actor SET NOT NULL;
ALTER TABLE consent_records ALTER COLUMN source SET NOT NULL;
ALTER TABLE consent_records ALTER COLUMN schema_version SET NOT NULL;
