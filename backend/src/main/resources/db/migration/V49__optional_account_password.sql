ALTER TABLE buyer_accounts ADD COLUMN password_hash VARCHAR(100);
ALTER TABLE buyer_account_sessions ADD COLUMN sign_in_method VARCHAR(20) NOT NULL DEFAULT 'EMAIL_LINK';
