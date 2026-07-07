CREATE TABLE combo_images (
    id UUID PRIMARY KEY,
    combo_id UUID NOT NULL REFERENCES combos(id) ON DELETE CASCADE,
    image_url TEXT NOT NULL,
    display_order INT NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT unique_combo_display_order UNIQUE (combo_id, display_order)
);
CREATE INDEX idx_combo_images_combo ON combo_images(combo_id);
CREATE UNIQUE INDEX unique_combo_primary ON combo_images(combo_id) WHERE is_primary = TRUE;
