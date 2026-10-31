# 2.4.9 candidate

This version includes the reviewed changes merged after immutable 2.4.8:

- Administrator Steam update inbox using opt-in official ISteamNews checks, persistent Ignore/Snooze decisions and preserved failure/last-success evidence. Watching can be stopped without erasing Steam identity. News indicates possible updates, not a verified installed-version comparison.
- Visible download-size and wrapping edition pills, without the redundant Downloading line or an unsupported public Latest claim. Exact content selections and source files are unchanged.
- Compatibility with exact official legacy Steam news CDN evidence links; no server-side link fetching or arbitrary CDN trust.

References: #190, #192, #193; completed implementation issues #189 and #191. Release preparation is tracked under #61; its production acceptance remains open.

The existing footer, native avatar integration and OIDC behavior from 2.4.8 remain included. A versioned image is not live deployment acceptance. Avatar-safe Main/Root runtime transition and recovery readiness are separate requirements before any live upgrade. Publishing this candidate must not overwrite 2.4.8, move latest, change production settings or configure download providers.
