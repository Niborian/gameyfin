import {configureAuth} from '@vaadin/hilla-react-auth';
import {UnauthorizedResponseError} from '@vaadin/hilla-frontend';
import {PropsWithChildren, useLayoutEffect, useMemo, useRef, useState} from 'react';
import {UserEndpoint} from 'Frontend/generated/endpoints';

// All configured providers use Hilla's shared AuthContext; retain its typed hook.
export const useAuth = configureAuth(UserEndpoint.getUserInfo).useAuth;

export function AuthProvider({children}: PropsWithChildren) {
    const [failed, setFailed] = useState(false);
    const owner = useRef({active: true, request: 0});
    useLayoutEffect(() => {
        owner.current.active = true;
        return () => {owner.current.active = false; owner.current.request++;};
    }, []);
    const Provider = useMemo(() => configureAuth(async () => {
        const request = ++owner.current.request;
        try {
            return await UserEndpoint.getUserInfo();
        } catch (error) {
            // Hilla handles a genuinely unauthenticated response itself. A failed
            // transport must not be converted to an anonymous/successful session.
            if (!(error instanceof UnauthorizedResponseError) && owner.current.active && request === owner.current.request) {
                setFailed(true);
            }
            throw error;
        }
    }).AuthProvider, []);

    if (failed) return <section role="alert" className="flex flex-col items-center justify-center min-h-screen gap-4 p-6 text-center">
        <h1 className="text-xl font-semibold">Could not check your session</h1>
        <p>Your session could not be verified. Reload to try again.</p>
        <button type="button" className="rounded-lg bg-primary text-primary-foreground px-4 py-2" onClick={() => window.location.reload()}>Reload Gameyfin</button>
    </section>;
    return <Provider>{children}</Provider>;
}
