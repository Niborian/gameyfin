import React, {useState} from "react";
import {OwnedTorrentEndpoint} from "Frontend/generated/endpoints";
import OwnedTorrentRecoveryAction from "Frontend/generated/org/gameyfin/app/core/download/torrent/OwnedTorrentRecoveryAction";

type Settings = Awaited<ReturnType<typeof OwnedTorrentEndpoint.getSettings>>;

/** Dedicated write-only credential form; never enters generic ConfigState or subscriptions. */
export function OwnedTorrentSettings() {
    const [settings, setSettings] = useState<Settings>();
    const [configuration, setConfiguration] = useState("{}");
    const [username, setUsername] = useState("");
    const [password, setPassword] = useState("");
    const [clearCredentials, setClearCredentials] = useState(false);
    const [rotateRetainedCredentials, setRotateRetainedCredentials] = useState(false);
    const [message, setMessage] = useState("");
    const [busy, setBusy] = useState(false);
    const [requestId, setRequestId] = useState("");
    const [retryRemoval, setRetryRemoval] = useState(false);
    const [retained, setRetained] = useState<Awaited<ReturnType<typeof OwnedTorrentEndpoint.retained>>>([]);
    const [exitAttestation, setExitAttestation] = useState("");
    const load = async () => {
        setBusy(true);
        try {
            const result = await OwnedTorrentEndpoint.getSettings();
            setSettings(result); setConfiguration(JSON.stringify(result.configuration, null, 2));
            setUsername(""); setPassword(""); setClearCredentials(false); setRotateRetainedCredentials(false); setMessage("");
        } catch { setMessage("Unable to load settings. An active administrator session is required."); }
        finally { setBusy(false); }
    };
    const save = async () => {
        if (!settings) return;
        setBusy(true);
        try {
            const result = await OwnedTorrentEndpoint.updateSettings({expectedRevision: settings.revision,
                configuration: JSON.parse(configuration), username: username || undefined,
                password: password || undefined, clearCredentials, rotateRetainedCredentials});
            setSettings(result); setConfiguration(JSON.stringify(result.configuration, null, 2));
            setMessage("Saved. Enabling requires separate operator parity admission.");
        } catch { setMessage("Save refused. Check the mapping, confirmations and revision; reload if changed."); }
        finally { setPassword(""); setUsername(""); setClearCredentials(false); setRotateRetainedCredentials(false); setBusy(false); }
    };
    return <section className="flex flex-col gap-3 mt-6">
        <h2 className="text-xl font-bold">Owned private torrent distribution (opt-in)</h2>
        <p>Off by default. Requires reviewed Linux descriptor acquisition, a dedicated read-only snapshot client,
            private trackers/peer egress and real transfer parity. Confirmation fields do not verify deployment.</p>
        <button type="button" disabled={busy} onClick={() => void load()}>Load administrator settings</button>
        {settings && <>
            <p>Credentials configured: {settings.credentialsConfigured ? "yes" : "no"}; operator parity gate: {settings.coreParityEnabled ? "enabled" : "disabled"}.</p>
            <label>Exact configuration (JSON; no credentials)
                <textarea className="w-full font-mono" rows={18} value={configuration} disabled={busy}
                    onChange={(event) => setConfiguration(event.target.value)}/></label>
            <label>New client username <input autoComplete="off" value={username} disabled={busy}
                onChange={(event) => setUsername(event.target.value)}/></label>
            <label>New client password (blank keeps stored credentials) <input type="password" autoComplete="new-password"
                value={password} disabled={busy} onChange={(event) => setPassword(event.target.value)}/></label>
            <label><input type="checkbox" checked={clearCredentials} disabled={busy}
                onChange={(event) => setClearCredentials(event.target.checked)}/> Clear stored credentials</label>
            <label><input type="checkbox" checked={rotateRetainedCredentials} disabled={busy}
                onChange={(event) => setRotateRetainedCredentials(event.target.checked)}/> Explicitly rotate cleanup credentials for retained requests with this identical client and configuration.
                Original encrypted profiles are retained; no client or path may change. Clearing credentials remains refused while requests are retained.</label>
            <button type="button" disabled={busy} onClick={() => void save()}>Save explicit configuration</button>
            <label>Request UUID for exact retirement <input value={requestId} disabled={busy}
                onChange={(event) => setRequestId(event.target.value)}/></label>
            <button type="button" disabled={busy} onClick={() => {
                setBusy(true);
                void OwnedTorrentEndpoint.retained().then(setRetained).catch(() => setMessage("Unable to list retained requests."))
                    .finally(() => setBusy(false));
            }}>Find retained requests (maximum 64; IDs and states only)</button>
            {retained.map((request) => <button key={request.id} type="button" disabled={busy}
                onClick={() => setRequestId(request.id)}>{request.id}: {request.state}</button>)}
            <label><input type="checkbox" checked={retryRemoval} disabled={busy}
                onChange={(event) => setRetryRemoval(event.target.checked)}/> Explicitly retry an uncertain removal (fresh exact ownership and stopped checks are required)</label>
            <button type="button" disabled={busy || !/^[0-9a-f-]{36}$/.test(requestId)} onClick={() => {
                setBusy(true);
                void OwnedTorrentEndpoint.inspect(requestId).then((result) => setMessage(`Request ${result.id}: ${result.state}`))
                    .catch(() => setMessage("Unable to inspect this request."))
                    .finally(() => setBusy(false));
            }}>Inspect exact request</button>
            <p>Recovery only acknowledges existing remote state; it never repeats create/add/start. After recovery, inspect and retire explicitly.</p>
            <button type="button" disabled={busy || !/^[0-9a-f-]{36}$/.test(requestId)} onClick={() => {
                setBusy(true);
                void OwnedTorrentEndpoint.creatorIdentity(requestId).then((identity) => {
                    setExitAttestation(JSON.stringify({expectedScope: identity.scope, expectedPid: identity.pid,
                        expectedStartedMillis: identity.startedMillis, originalContainerId: "", originalContainerRemoved: false,
                        allOldWorkersExited: false, evidenceSha256: ""}, null, 2));
                    setMessage("Identity loaded. A receipt must prove this exact original container and all old workers are gone; live/unknown same-scope creators cannot be overridden.");
                }).catch(() => setMessage("Unable to load exact creator identity."))
                    .finally(() => setBusy(false));
            }}>Load resource-free cancellation identity</button>
            <label>Optional foreign-scope exit receipt (JSON; leave blank for independently proved local creator exit)
                <textarea rows={8} className="w-full font-mono" value={exitAttestation} disabled={busy}
                    onChange={(event) => setExitAttestation(event.target.value)}/></label>
            <button type="button" disabled={busy || !/^[0-9a-f-]{36}$/.test(requestId)} onClick={() => {
                setBusy(true);
                void Promise.resolve().then(() => OwnedTorrentEndpoint.cancelResourceFree(requestId,
                    exitAttestation.trim() ? JSON.parse(exitAttestation) : undefined))
                    .then(() => setMessage("Resource-free request cancelled after exact exit and absence proofs; no resource was deleted."))
                    .catch(() => setMessage("Cancellation refused: prove original creator exit and reconcile any existing planned snapshot/intent first."))
                    .finally(() => setBusy(false));
            }}>Cancel only a proved resource-free request</button>
            {Object.values(OwnedTorrentRecoveryAction).map((action) => <button key={action} type="button"
                disabled={busy || !/^[0-9a-f-]{36}$/.test(requestId)} onClick={() => {
                    setBusy(true);
                    void OwnedTorrentEndpoint.recover(requestId, action).then(() => setMessage("Exact recovery queued; inspect its result."))
                        .catch(() => setMessage("Recovery refused or uncertain; no create/add/start retry was made."))
                        .finally(() => setBusy(false));
                }}>{action.replaceAll("_", " ")}</button>)}
            <button type="button" disabled={busy || !/^[0-9a-f-]{36}$/.test(requestId)} onClick={() => {
                setBusy(true);
                void OwnedTorrentEndpoint.retire(requestId, retryRemoval).then(() => setMessage("Retirement queued; verify status before considering storage released."))
                    .catch(() => setMessage("Retirement refused or uncertain. Preserve ownership and inspect operator receipts."))
                    .finally(() => setBusy(false));
            }}>Stop, remove and retire this owned request</button>
        </>}
        {message && <p role="status">{message}</p>}
    </section>;
}
