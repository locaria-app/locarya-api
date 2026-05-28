-- Providers (Locadores)
CREATE TABLE providers (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    cnpj VARCHAR(14) NOT NULL UNIQUE,
    business_name VARCHAR(255) NOT NULL,
    trade_name VARCHAR(255) NOT NULL,
    contract_status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_providers_email ON providers(email);
CREATE INDEX idx_providers_cnpj ON providers(cnpj);

-- Plans (Platform subscription plans for providers)
CREATE TABLE plans (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    monthly_fee DECIMAL(10, 2) NOT NULL,
    transaction_fee_percent DECIMAL(5, 2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Contracts (Provider-Platform contracts)
CREATE TABLE contracts (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES providers(id),
    plan_id UUID NOT NULL REFERENCES plans(id),
    status VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_contracts_provider ON contracts(provider_id);
CREATE INDEX idx_contracts_status ON contracts(status);

-- Items
CREATE TABLE items (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES providers(id),
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    daily_rate DECIMAL(10, 2) NOT NULL,
    stock INT NOT NULL DEFAULT 0,
    attendant_requirement VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_items_provider ON items(provider_id);

-- Item Images (1 primary + up to 4 additional = max 5 per item)
CREATE TABLE item_images (
    id UUID PRIMARY KEY,
    item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    image_url TEXT NOT NULL,
    display_order INT NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT unique_item_primary UNIQUE (item_id, is_primary) WHERE is_primary = TRUE,
    CONSTRAINT unique_item_display_order UNIQUE (item_id, display_order)
);

CREATE INDEX idx_item_images_item ON item_images(item_id);

-- Combos
CREATE TABLE combos (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES providers(id),
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    daily_rate DECIMAL(10, 2) NOT NULL,
    attendant_requirement VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_combos_provider ON combos(provider_id);

-- Combo Items (defines which items are in each combo)
CREATE TABLE combo_items (
    id UUID PRIMARY KEY,
    combo_id UUID NOT NULL REFERENCES combos(id) ON DELETE CASCADE,
    item_id UUID NOT NULL REFERENCES items(id),
    quantity INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT positive_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_combo_items_combo ON combo_items(combo_id);
CREATE INDEX idx_combo_items_item ON combo_items(item_id);

-- Attendants (Monitores)
CREATE TABLE attendants (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES providers(id),
    name VARCHAR(255) NOT NULL,
    cpf VARCHAR(11) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_attendants_provider ON attendants(provider_id);
CREATE INDEX idx_attendants_cpf ON attendants(cpf);

-- Customers (Clientes)
CREATE TABLE customers (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    cpf VARCHAR(11) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_customers_email ON customers(email);
CREATE INDEX idx_customers_cpf ON customers(cpf);

-- Bookings (Reservas)
CREATE TABLE bookings (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES providers(id),
    customer_id UUID NOT NULL REFERENCES customers(id),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    total_amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT valid_date_range CHECK (start_date < end_date)
);

CREATE INDEX idx_bookings_provider ON bookings(provider_id);
CREATE INDEX idx_bookings_customer ON bookings(customer_id);
CREATE INDEX idx_bookings_status ON bookings(status);
CREATE INDEX idx_bookings_dates ON bookings(start_date, end_date);

-- Booking Items (individual items or combos in a booking)
CREATE TABLE booking_items (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    item_type VARCHAR(20) NOT NULL, -- 'INDIVIDUAL' or 'COMBO'
    item_id UUID, -- references items(id) if item_type = 'INDIVIDUAL'
    combo_id UUID, -- references combos(id) if item_type = 'COMBO'
    quantity INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT positive_quantity CHECK (quantity > 0),
    CONSTRAINT valid_item_reference CHECK (
        (item_type = 'INDIVIDUAL' AND item_id IS NOT NULL AND combo_id IS NULL) OR
        (item_type = 'COMBO' AND combo_id IS NOT NULL AND item_id IS NULL)
    )
);

CREATE INDEX idx_booking_items_booking ON booking_items(booking_id);

-- Booking Attendants (monitors assigned to bookings)
CREATE TABLE booking_attendants (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    attendant_id UUID NOT NULL REFERENCES attendants(id),
    assigned_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT unique_booking_attendant UNIQUE (booking_id, attendant_id)
);

CREATE INDEX idx_booking_attendants_booking ON booking_attendants(booking_id);
CREATE INDEX idx_booking_attendants_attendant ON booking_attendants(attendant_id);

-- Payments
CREATE TABLE payments (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(id),
    amount DECIMAL(10, 2) NOT NULL,
    payment_method VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    asaas_payment_id VARCHAR(255), -- External payment gateway reference
    paid_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payments_booking ON payments(booking_id);
CREATE INDEX idx_payments_status ON payments(status);
CREATE INDEX idx_payments_asaas_id ON payments(asaas_payment_id);
