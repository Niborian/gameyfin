/** Auth forms must not start unrelated catalog requests or subscriptions. */
export function isAuthOnlyRoute(pathname: string): boolean {
    return ['/login', '/setup', '/reset-password', '/accept-invitation', '/confirm-email']
        .includes(pathname.replace(/\/+$/, '').toLowerCase() || '/');
}

/** Fetch cancellation and local side-effect guards are both required. */
export function startupIsCurrent(signal?: AbortSignal): boolean {
    return !signal?.aborted;
}

const owners = new WeakMap<object, object>();

/** Clear only this generation's cache and subscription, including pending loads. */
export function ownStartupState(state: object, signal: AbortSignal | undefined, clear: () => void): void {
    if (!signal) return; // Preserve existing standalone initializer callers.
    const owner = {};
    owners.set(state, owner);
    const cancel = () => {
        if (owners.get(state) !== owner) return;
        owners.delete(state);
        const subscribed = state as {subscription?: {cancel(): void}};
        const subscription = subscribed.subscription;
        if (subscription) subscribed.subscription = undefined;
        try { subscription?.cancel(); } finally { clear(); }
    };
    if (signal.aborted) cancel();
    else signal.addEventListener('abort', cancel, {once: true});
}

export function loginIsReady(initializing: boolean, loading: boolean, policySettled: boolean): boolean {
    return !initializing && !loading && policySettled;
}
