-- Flyway Migration: V2.4.0.7
-- Purpose: Track when an admin explicitly pins the default downloadable variant.

ALTER TABLE GAME_VARIANT
    ADD DEFAULT_LOCKED BOOLEAN DEFAULT FALSE NOT NULL;
