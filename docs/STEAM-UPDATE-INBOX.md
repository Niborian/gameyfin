# Possible game update notices

Enable **Administration → Game Requests → Check Steam for possible game updates**.
For each game, open **Versions & content**, save the Steam store/product ID, and
choose **Watch Steam updates** to confirm the mapping. Local version text and
source files are unchanged. Only administrator-verified mappings are checked.
**Stop watching** pauses that variant's monitoring independently of its Steam
store identity, verification time and scan override. It does not clear metadata.
An app is still checked if another verified variant continues to watch it.

The first background check is after five minutes, then a serial batch of at most
ten different app IDs hourly. Each app is attempted at most once per day, including
failed attempts. Duplicate variants share one provider request. Each request has
a 12-second deadline and 512 KiB response bound. Monitoring defaults off; opening
or refreshing the inbox reads cached metadata only.

The administration inbox retains event URL/date, classification/reason and source.
Ignore applies to that exact app/event, across mapped variants; snooze lasts seven
days. Later events still appear. Failed checks preserve previous evidence and the
last successful check time. At most 100 events are retained per app and the inbox
returns at most 100 visible game/event rows. Removed/unverified mappings disappear
from the visible inbox without altering source files. Historical notices on first
check may predate the installed version: these are possible updates, not proof that
a newer compatible build exists.

Only the official public ISteamNews API is called. Titles/content/tags are classified
conservatively. Provider text is never rendered as HTML; evidence links are limited
to HTTPS Steam Store/Community origins and the exact legacy Steam news host
`steamstore-a.akamaihd.net/news/externalpost/`. Other CDN hosts/paths, userinfo,
nonstandard ports and insecure links are refused. Evidence links are not fetched
by Gameyfin. No SteamDB/depot/store scraping occurs.
There is no external notification delivery, automatic version claim, request approval,
Prowlarr search, torrent add, import or source mutation from these notices.

Prowlarr/qBittorrent acquisition is a separate opt-in adapter, already implemented
with request review and ownership-scoped stop/resume. It still requires isolated
provider configuration and deployment acceptance; do not reuse a client managing
unrelated torrents. See [provider setup](AUTHORIZED-ACQUISITION-PROVIDER.md).
Exact-selection qBittorrent library distribution is another separate function.
Automatic acquisition/import needs a later deliberate policy and release.
