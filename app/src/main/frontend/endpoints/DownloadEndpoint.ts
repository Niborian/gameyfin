import {OwnedTorrentEndpoint} from "Frontend/generated/endpoints";

export const OWNED_TORRENT_PROVIDER = "owned-snapshot-qb";
let ownedTorrentOwner: object | undefined;

export function downloadGame(gameId: number, provider: string, variantId?: number, contentIds?: number[], signal?: AbortSignal) {
    ownedTorrentOwner = undefined;
    if (provider === OWNED_TORRENT_PROVIDER) {
        const owner = {};
        ownedTorrentOwner = owner;
        const current = () => ownedTorrentOwner === owner && !signal?.aborted;
        if (!current()) return;
        void prepareOwnedTorrent(gameId, variantId, contentIds, current).catch(() => {
            if (!current()) return;
            window.alert("Torrent preparation did not complete. No automatic retry was made. Ask an administrator to inspect retained requests.");
        });
        return;
    }
    const params = new URLSearchParams({provider});
    if (variantId !== undefined) params.set("variantId", variantId.toString());
    if (contentIds !== undefined) params.set("explicitSelection", "true");
    contentIds?.forEach((contentId) => params.append("contentIds", contentId.toString()));
    window.open(`/download/${gameId}?${params.toString()}`, '_top');
}

async function prepareOwnedTorrent(gameId: number, variantId: number | undefined, contentIds: number[] | undefined, current: () => boolean) {
    let request = await OwnedTorrentEndpoint.create({gameId, variantId, contentIds, explicitSelection: contentIds !== undefined});
    if (!current()) return;
    const deadline = performance.now() + 12 * 60 * 1000;
    while (request.state === "QUEUED" || request.state === "RUNNING") {
        if (performance.now() >= deadline) throw new Error("Preparation deadline");
        await new Promise((resolve) => window.setTimeout(resolve, 1000));
        if (!current()) return;
        request = await OwnedTorrentEndpoint.status(request.id);
        if (!current()) return;
    }
    if (request.state !== "READY" || !request.downloadUrl || !/^\/owned-torrent\/[0-9a-f-]{36}\/metadata$/.test(request.downloadUrl)) {
        window.alert(`Torrent request ${request.id}: ${request.state}. Ask an administrator to inspect this exact request; no retry was made.`);
        return;
    }
    if (current()) window.location.assign(request.downloadUrl);
}

export async function estimateDownloadSize(gameId: number, variantId?: number, contentIds?: number[]): Promise<number> {
    const params = new URLSearchParams();
    if (variantId !== undefined) params.set("variantId", variantId.toString());
    if (contentIds !== undefined) params.set("explicitSelection", "true");
    contentIds?.forEach((contentId) => params.append("contentIds", contentId.toString()));

    const response = await fetch(`/download/${gameId}/estimate?${params.toString()}`);
    return await response.json();
}
