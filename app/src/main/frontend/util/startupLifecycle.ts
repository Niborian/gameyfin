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

type StartupTask = {signal?: AbortSignal; promise: Promise<void>; current(): boolean; dispose(): void};
const tasks = new WeakMap<object, StartupTask>();

/** One fetch/attachment per owner; replaced owners lose authority even if not aborted. */
export function runStartupState(state: object, signal: AbortSignal | undefined, clear: () => void,
                                run: (current: () => boolean) => Promise<void>): Promise<void> {
    if (signal?.aborted) return Promise.resolve();
    const previous = tasks.get(state);
    if (previous && previous.signal === signal && previous.current()) return previous.promise;
    previous?.dispose();
    let resolveTask!: () => void, rejectTask!: (error: unknown) => void;
    const completion = new Promise<void>((resolve, reject) => { resolveTask = resolve; rejectTask = reject; });
    const task: StartupTask = {
        signal, promise: completion,
        current: () => tasks.get(state) === task && !signal?.aborted,
        dispose: () => {
            if (tasks.get(state) !== task) return;
            tasks.delete(state);
            signal?.removeEventListener('abort', task.dispose);
            const subscribed = state as {subscription?: {cancel(): void}};
            const subscription = subscribed.subscription;
            subscribed.subscription = undefined;
            try { subscription?.cancel(); } finally { clear(); }
        }
    };
    tasks.set(state, task);
    signal?.addEventListener('abort', task.dispose, {once: true});
    // run starts synchronously so the original RPC timing is preserved. Store the
    // owner before invoking it; every continuation/callback checks current().
    let operation: Promise<void>;
    try { operation = run(task.current); } catch (error) { operation = Promise.reject(error); }
    operation.then(() => {
        if (task.current() && !(state as {isLoaded?: boolean}).isLoaded) task.dispose();
    }, error => {
        if (task.current()) task.dispose();
        throw error;
    }).then(resolveTask, rejectTask);
    return task.promise;
}

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
