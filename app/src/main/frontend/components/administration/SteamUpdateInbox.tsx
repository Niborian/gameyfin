import {useEffect, useState} from "react";
import {Button} from "@heroui/react";
import {Link} from "react-router";
import {SteamNewsInboxEndpoint} from "Frontend/generated/endpoints";

type Inbox = Awaited<ReturnType<typeof SteamNewsInboxEndpoint.inbox>>;

/** Cached metadata only: opening this component never contacts Steam or a download client. */
export default function SteamUpdateInbox() {
    const [inbox, setInbox] = useState<Inbox>();
    const [error, setError] = useState(false);
    const [pending, setPending] = useState(false);
    const [revision, setRevision] = useState(0);
    useEffect(() => {
        let active = true;
        let request = 0;
        async function load() {
            const current = ++request;
            try {
                const value = await SteamNewsInboxEndpoint.inbox();
                if (active && current === request) {setInbox(value); setError(false);}
            } catch {if (active && current === request) setError(true);}
        }
        void load();
        const timer = window.setInterval(() => {if (!document.hidden) void load();}, 60_000);
        return () => {active = false; request++; window.clearInterval(timer);};
    }, [revision]);

    async function review(appId: string, eventId: string, ignore: boolean) {
        if (pending) return;
        setPending(true);
        try {
            if (ignore) await SteamNewsInboxEndpoint.ignore(appId, eventId);
            else await SteamNewsInboxEndpoint.snooze(appId, eventId);
            setRevision(value => value + 1);
        } catch {setError(true);}
        finally {setPending(false);}
    }

    return <div className="flex flex-col gap-3 mt-3">
        <p className="text-sm text-default-500">Possible updates from Steam news, not a verified comparison with your installed files.
            To track a game, save its Steam product ID in Versions &amp; content, then choose Watch Steam updates.</p>
        {error && <p role="alert">Could not refresh the inbox or save your decision. Existing notices are retained; try again.</p>}
        <Button type="button" size="sm" variant="flat" className="self-start" onPress={() => setRevision(value => value + 1)}>Refresh inbox</Button>
        {inbox?.notices.length === 0 && <p className="text-sm">No unsnoozed update notices. Checks begin after monitoring is enabled.</p>}
        {inbox?.notices.map(notice => <article key={`${notice.appId}:${notice.eventId}:${notice.gameId}`} className="rounded-lg bg-default-100 p-3 flex flex-col gap-2">
            <Link className="font-semibold" to={`/game/${notice.gameId}`}>{notice.gameTitle}</Link>
            <a href={notice.url} target="_blank" rel="noopener noreferrer" className="underline">{notice.title}</a>
            <p className="text-sm">{notice.classification === "REVIEW_NEEDED" ? "Needs review" : "Possible content update"} · {new Date(notice.publishedAt).toLocaleDateString()}</p>
            <p className="text-xs text-default-500">{notice.reason} · {notice.source} · Steam app {notice.appId}</p>
            <p className="text-xs text-default-500">{notice.feedLabel || notice.feedName}{notice.tags && ` · Tags: ${notice.tags}`}</p>
            <div className="flex gap-2">
                <Button type="button" size="sm" variant="flat" isDisabled={pending} onPress={() => review(notice.appId, notice.eventId, false)}>Snooze 7 days</Button>
                <Button type="button" size="sm" variant="light" isDisabled={pending} onPress={() => review(notice.appId, notice.eventId, true)}>Ignore this event</Button>
            </div>
        </article>)}
        {inbox && inbox.checks.length > 0 && <details className="text-xs text-default-500">
            <summary>Recent Steam checks ({inbox.checks.length})</summary>
            {inbox.checks.map(check => <p key={check.appId}>App {check.appId}: {check.failed ? "Check failed; previous evidence retained" : "Checked"} · {new Date(check.attemptedAt).toLocaleString()}
                {check.succeededAt && ` · Last successful check ${new Date(check.succeededAt).toLocaleString()}`}</p>)}
        </details>}
    </div>;
}
