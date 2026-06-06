-- Flyway Migration: V2.4.0.5
-- Purpose: Allow manually/grouped external variants to survive filesystem rescans.

ALTER TABLE GAME_VARIANT
    ADD SCAN_MANAGED BOOLEAN DEFAULT TRUE NOT NULL;
