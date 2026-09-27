package org.gameyfin.app.games.entities

/** Each value is evidence with its own meaning; download/import times are never release dates. */
enum class VariantTimestampKind {
    RELEASE_METADATA,
    TORRENT_ADDED,
    QBITTORRENT_COMPLETED,
    FILESYSTEM_OBSERVED
}
