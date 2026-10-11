# Official Chrome for Testing + Node/Puppeteer; no mutable apt/npm downloads.
FROM ghcr.io/puppeteer/puppeteer:25.13.0@sha256:893118e0546f8cee371a0ae09c8489d1e9c40de28c45df9d811d4233203b81a3
LABEL org.gameyfin.browser.fixture="hosted-ci-only" org.gameyfin.browser.base-digest="sha256:893118e0546f8cee371a0ae09c8489d1e9c40de28c45df9d811d4233203b81a3"
USER root
COPY --chown=pptruser:pptruser *.mjs /opt/gameyfin-browser/
USER pptruser
ENV HOME=/home/pptruser
ENTRYPOINT ["node", "/opt/gameyfin-browser/browser_entry.mjs"]
