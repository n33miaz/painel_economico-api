-- Tabela para armazenar as transações do extrato bancário (OFX)
CREATE TABLE bank_transactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    transaction_id VARCHAR(100) NOT NULL, 
    type VARCHAR(20) NOT NULL,            -- CREDIT ou DEBIT
    amount DECIMAL(19, 4) NOT NULL,
    description VARCHAR(255),
    date TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_bank_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT unique_transaction_per_user UNIQUE (user_id, transaction_id)
);

CREATE INDEX idx_bank_transactions_user_id ON bank_transactions(user_id);
CREATE INDEX idx_bank_transactions_date ON bank_transactions(date);