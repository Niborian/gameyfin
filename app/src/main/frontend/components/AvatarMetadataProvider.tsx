import {createContext, useContext, useLayoutEffect, useState, type PropsWithChildren} from 'react';
import {UserAvatarEndpoint, UserEndpoint} from 'Frontend/generated/endpoints';
import {ActionOnLostSubscription, type Subscription} from '@vaadin/hilla-frontend';
import {avatarRefresher, type AvatarMetadata} from 'Frontend/util/avatarMetadata';
import {offerSessionSignIn} from 'Frontend/util/middleware';
import {userState} from 'Frontend/state/UserState';

const Context = createContext<AvatarMetadata | undefined>(undefined);
const FOCUS_CHECK_INTERVAL_MS = 120_000;
export const useAvatarMetadata = () => useContext(Context);

/** Metadata only; auth state/roles/session remain owned by the existing auth provider.
 * Invalidation streams are best-effort, reconciled on focus, never idle polling. */
export default function AvatarMetadataProvider({user, active, admin, children}: PropsWithChildren<{
    user?: AvatarMetadata; active: boolean; admin: boolean;
}>) {
    const [snapshot, setSnapshot] = useState<AvatarMetadata>();
    useLayoutEffect(() => {
        setSnapshot(undefined);
        if (!active || user?.id === undefined || !user.username) return;
        const identity = {id: user.id, username: user.username};
        const lifecycle = new AbortController();
        const signal = lifecycle.signal;
        let own: Subscription<number> | undefined;
        let all: Subscription<number> | undefined;
        let lastOwnCheck = performance.now();
        const refreshOwn = avatarRefresher(signal, () => {
            lastOwnCheck = performance.now();
            return UserEndpoint.getUserInfo({signal, mute: true});
        }, next => {
            if (!next) {
                offerSessionSignIn();
                return;
            }
            if (next.id !== identity.id || next.username !== identity.username) return;
            setSnapshot({id: next.id, username: next.username, hasAvatar: next.hasAvatar,
                avatarVersion: next.avatarVersion, avatarId: next.avatarId});
        });
        const refreshAll = avatarRefresher(signal, async () => {
            if (!admin || !userState.isLoaded) return undefined;
            return UserEndpoint.getAllUsers({signal, mute: true});
        }, users => {
            if (!users || !userState.isLoaded) return;
            // Avatar invalidation is not account creation/deletion/role authority.
            for (const next of users) {
                const previous = userState.state[next.id];
                if (previous) userState.state[next.id] = {...previous,
                    hasAvatar: next.hasAvatar, avatarId: next.avatarId, avatarVersion: next.avatarVersion};
            }
        });
        const refresh = () => { if (!signal.aborted) { void refreshOwn(); void refreshAll(); } };
        const attach = () => {
            if (signal.aborted) return;
            if (!own) {
                const subscription = UserAvatarEndpoint.subscribeOwn();
                own = subscription;
                subscription.onNext(() => { void refreshOwn(); })
                    .onError(() => { if (own === subscription) own = undefined; if (!signal.aborted) void refreshOwn(); })
                    .onComplete(() => { if (own === subscription) own = undefined; })
                    .onSubscriptionLost(() => {
                        if (!signal.aborted && own === subscription) {
                            own = undefined;
                            // Retire the old server ID before attaching after Hilla finishes reopening.
                            subscription.cancel();
                            queueMicrotask(() => { attach(); void refreshOwn(); });
                        }
                        return ActionOnLostSubscription.REMOVE;
                    });
            }
            if (admin && !all) {
                const subscription = UserAvatarEndpoint.subscribeAll();
                all = subscription;
                subscription.onNext(() => { void refreshAll(); })
                    .onError(() => { if (all === subscription) all = undefined; if (!signal.aborted) void refreshAll(); })
                    .onComplete(() => { if (all === subscription) all = undefined; })
                    .onSubscriptionLost(() => {
                        if (!signal.aborted && all === subscription) {
                            all = undefined;
                            subscription.cancel();
                            queueMicrotask(() => { attach(); void refreshAll(); });
                        }
                        return ActionOnLostSubscription.REMOVE;
                    });
            }
        };
        const focus = () => {
            if (document.visibilityState !== 'visible') return;
            attach();
            if (performance.now() - lastOwnCheck >= FOCUS_CHECK_INTERVAL_MS) void refreshOwn();
        };
        attach();
        window.addEventListener('focus', focus);
        document.addEventListener('visibilitychange', focus);
        window.addEventListener('gameyfin:avatar-changed', refresh);
        return () => {
            lifecycle.abort();
            try { own?.cancel(); } catch { /* Already closed. */ }
            try { all?.cancel(); } catch { /* Already closed. */ }
            window.removeEventListener('focus', focus);
            document.removeEventListener('visibilitychange', focus);
            window.removeEventListener('gameyfin:avatar-changed', refresh);
        };
    }, [user?.id, user?.username, active, admin]);
    const current = active && snapshot?.id === user?.id && snapshot?.username === user?.username ? snapshot : user;
    return <Context.Provider value={current}>{children}</Context.Provider>;
}
