export function downloadGame(gameId: number, provider: string, variantId?: number, contentIds?: number[]) {
    const params = new URLSearchParams({provider});
    if (variantId !== undefined) params.set("variantId", variantId.toString());
    contentIds?.forEach((contentId) => params.append("contentIds", contentId.toString()));
    window.open(`/download/${gameId}?${params.toString()}`, '_top');
}

export async function estimateDownloadSize(gameId: number, variantId?: number, contentIds?: number[]): Promise<number> {
    const params = new URLSearchParams();
    if (variantId !== undefined) params.set("variantId", variantId.toString());
    contentIds?.forEach((contentId) => params.append("contentIds", contentId.toString()));

    const response = await fetch(`/download/${gameId}/estimate?${params.toString()}`);
    return await response.json();
}
