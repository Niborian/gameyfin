-- Flyway Migration: V2.4.3.9
-- Purpose: Preserve administrator review state for an observed public Steam marker; no source path is changed.

ALTER TABLE GAME_VARIANT ADD STEAM_UPDATE_IGNORED_MARKER CHARACTER VARYING(512);
ALTER TABLE GAME_VARIANT ADD STEAM_UPDATE_SNOOZED_UNTIL TIMESTAMP WITH TIME ZONE;
