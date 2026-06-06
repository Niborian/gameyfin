-- Flyway Migration: V2.4.0.4
-- Purpose: Add per-library storage mode for direct paths or hardlink mirrors.

ALTER TABLE LIBRARY
    ADD STORAGE_MODE CHARACTER VARYING(255);

UPDATE LIBRARY
SET STORAGE_MODE = 'DIRECT'
WHERE STORAGE_MODE IS NULL;
