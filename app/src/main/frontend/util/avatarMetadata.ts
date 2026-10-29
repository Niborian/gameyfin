export type AvatarMetadata = {
    id?: number;
    username?: string;
    hasAvatar?: boolean;
    avatarVersion?: number;
    avatarId?: number;
};

/** Other users never inherit the viewer's avatar presence or cache version. */
export function avatarSource(current: AvatarMetadata | undefined, requested: AvatarMetadata): string | undefined {
    const username = requested.username || current?.username;
    if (!username) return undefined;
    const own = username === current?.username;
    const present = requested.hasAvatar ?? (own ? current?.hasAvatar : false);
    const version = requested.avatarVersion ?? (own ? current?.avatarVersion : undefined);
    const image = requested.avatarId ?? (own ? current?.avatarId : undefined);
    if (!present) return undefined;
    const query = new URLSearchParams({username});
    if (version !== undefined) query.set('v', String(version));
    else if (image !== undefined) query.set('image', String(image));
    return `/images/avatar?${query}`;
}

/** One in-flight read plus coalesced invalidation; no interval/retry loop. */
export function avatarRefresher<T>(signal: AbortSignal, load: () => Promise<T>, apply: (value: T) => void) {
    let busy = false;
    let dirty = false;
    return async () => {
        dirty = true;
        if (busy || signal.aborted) return;
        busy = true;
        try {
            while (dirty && !signal.aborted) {
                dirty = false;
                const value = await load();
                if (signal.aborted) return;
                apply(value);
            }
        } catch {
            // Keep last known avatar; a later event/focus can reconcile without auth mutations.
        } finally { busy = false; }
    };
}
