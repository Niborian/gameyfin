import {Middleware, MiddlewareContext, MiddlewareNext} from '@vaadin/hilla-frontend';
import {addToast} from "@heroui/react";

export const ErrorHandlingMiddleware: Middleware = async function (
    context: MiddlewareContext,
    next: MiddlewareNext
) {
    const {endpoint, method} = context;

    // Names are generated identifiers, never request URLs/parameters or response data.
    const identifier = (value: string) => typeof value === 'string' && /^[A-Za-z_$][A-Za-z0-9_$]{0,127}$/.test(value) ? value : '[invalid]';
    const diagnose = (kind: string) => console.warn('Gameyfin endpoint failure', {
        kind, endpoint: identifier(endpoint), method: identifier(method)
    });
    let originalResponse: Response;
    try {
        originalResponse = await next(context);
    } catch (error) {
        // Expected lifecycle cancellation is not evidence of transport loss.
        if (error instanceof TypeError) diagnose('transport');
        throw error;
    }
    const jsonResponse = /\bapplication\/(?:[a-z0-9.-]+\+)?json\b/i.test(originalResponse.headers.get('Content-Type') ?? '');
    if (originalResponse.ok && !jsonResponse) diagnose('non-json-response');

    if (!originalResponse.ok) {
        // .clone() is necessary because response.json() is one-time only and Hilla accesses it in its internal error handler
        // @see https://developer.mozilla.org/en-US/docs/Web/API/Response/clone
        //Ignore calls to UserEndpoint.getUserInfo since they are managed by Hilla and called on initial load
        if (endpoint == "UserEndpoint" && method == "getUserInfo") return originalResponse;

        // Proxy HTML, an empty body or malformed JSON must not replace the actual
        // Hilla response/error with a secondary SyntaxError. Preserve its body.
        const json = jsonResponse ? await originalResponse.clone().json().catch(() => undefined) : undefined;
        if (!jsonResponse) diagnose('non-json-error-response');
        else if (json === undefined) diagnose('invalid-json-error-response');

        if ((json?.type == "dev.hilla.exception.EndpointException" || json?.type == "com.vaadin.hilla.exception.EndpointException") &&
            typeof json.message === 'string') {
            addToast({
                title: "Error",
                description: json.message,
                color: "danger"
            })
        } else {
            addToast({
                title: "Error",
                description: `${endpoint}.${method}`,
                color: "danger"
            })
        }
    }

    return originalResponse;
}
