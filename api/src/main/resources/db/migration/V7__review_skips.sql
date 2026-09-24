-- M4 / CP4.3: review inbox (DESIGN: Review inbox). "Every card is skippable; skipped cards return after the next
-- upload, not immediately": a card is hidden while its skip is newer than the latest upload.
CREATE TABLE review_skip (
    card_key   text PRIMARY KEY CHECK (card_key <> ''),
    skipped_at timestamptz NOT NULL DEFAULT now()
);
