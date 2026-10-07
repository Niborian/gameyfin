import React, {useEffect, useState} from "react";
import {Button, Checkbox, Input, Select, SelectItem} from "@heroui/react";
import {AcquisitionTransferEndpoint, GameRequestCandidateEndpoint, GameRequestEndpoint} from "Frontend/generated/endpoints";
import GameRequestDto from "Frontend/generated/org/gameyfin/app/requests/dto/GameRequestDto";
import GameRequestStatus from "Frontend/generated/org/gameyfin/app/requests/status/GameRequestStatus";

type Transfer = Awaited<ReturnType<typeof AcquisitionTransferEndpoint.list>>[number];

/** Administrator-only parent render; server independently authorizes every endpoint and action. */
export default function AuthorizedAcquisitionPanel({requests, indexers}: {requests: GameRequestDto[], indexers: string[]}) {
    const [requestId, setRequestId] = useState("");
    const [indexer, setIndexer] = useState("");
    const [query, setQuery] = useState("");
    const [reason, setReason] = useState("");
    const [authorized, setAuthorized] = useState(false);
    const [transfers, setTransfers] = useState<Transfer[]>([]);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const [audit, setAudit] = useState<Awaited<ReturnType<typeof AcquisitionTransferEndpoint.audit>>>([]);
    const selected = requests.find(request => String(request.id) === requestId);

    async function refresh() {
        if (requestId) setTransfers(await AcquisitionTransferEndpoint.list(Number(requestId)));
    }
    async function run(action: () => Promise<unknown>) {
        setBusy(true); setError("");
        try { await action(); await refresh(); }
        catch (failure) { setError(failure instanceof Error ? failure.message : "Acquisition action failed; inspect audit before retry"); }
        finally { setBusy(false); }
    }
    useEffect(() => {
        setTransfers([]); setAudit([]); setAuthorized(false);
        if (requestId) AcquisitionTransferEndpoint.list(Number(requestId)).then(setTransfers).catch(() => setError("Cannot load transfer audit"));
    }, [requestId]);

    return <section className="border border-default-200 rounded p-4 mb-4 flex flex-col gap-3">
        <h2>Authorized acquisition review</h2>
        <p className="text-sm">Only user-owned, open-source, public-domain or otherwise authorized content. Submission adds a torrent to the configured isolated client. Stop never deletes files; resume uses the same audited hash.</p>
        <Select label="Request" selectedKeys={requestId ? [requestId] : []} onSelectionChange={keys => setRequestId(String(Array.from(keys)[0] ?? ""))} isDisabled={busy}>
            {requests.map(request => <SelectItem key={String(request.id)}>{request.title}</SelectItem>)}
        </Select>
        <Button isDisabled={busy || !selected || selected.status === GameRequestStatus.QUEUED} onPress={() => run(() => GameRequestEndpoint.changeStatus(Number(requestId), GameRequestStatus.AWAITING_APPROVAL))}>Prepare request for candidate review</Button>
        <Select label="Verified active approved indexer" selectedKeys={indexer ? [indexer] : []} onSelectionChange={keys => setIndexer(String(Array.from(keys)[0] ?? ""))} isDisabled={busy}>
            {indexers.map(id => <SelectItem key={id}>{id}</SelectItem>)}
        </Select>
        <Input label="Search authorized content" value={query} onValueChange={setQuery} maxLength={256}/>
        <Button isDisabled={busy || !requestId || !indexer || !query.trim() || selected?.status !== GameRequestStatus.AWAITING_APPROVAL} onPress={() => run(() => AcquisitionTransferEndpoint.search(Number(requestId), indexer, query))}>Search without downloading</Button>
        <Input label="Authorization evidence or action reason" value={reason} onValueChange={setReason} maxLength={4096}/>
        <Checkbox isSelected={authorized} onValueChange={setAuthorized}>I am authorized to obtain this content and approve the explicit add/resume action</Checkbox>
        {error && <p role="alert" className="text-danger">{error}</p>}
        {transfers.map(transfer => <div key={transfer.candidateId} className="border-t border-default-200 pt-3">
            <p>{transfer.displayName} — indexer {transfer.indexerId} — {transfer.state}</p>
            <p className="font-mono text-xs">{transfer.torrentHash}</p>
            <div className="flex gap-2 flex-wrap">
                <Button isDisabled={busy || !reason.trim() || transfer.state !== "REVIEW" || selected?.status !== GameRequestStatus.AWAITING_APPROVAL} onPress={() => run(async () => {
                    await GameRequestCandidateEndpoint.approve(transfer.candidateId, {reason});
                    await GameRequestCandidateEndpoint.select(transfer.candidateId);
                })}>Approve candidate and queue review record</Button>
                <Button color="warning" isDisabled={busy || !authorized || !reason.trim() || transfer.state !== "REVIEW" || selected?.status !== GameRequestStatus.QUEUED} onPress={() => run(() => AcquisitionTransferEndpoint.submit(transfer.candidateId, reason))}>Add approved torrent</Button>
                <Button isDisabled={busy || !reason.trim() || transfer.state !== "ACTIVE"} onPress={() => run(() => AcquisitionTransferEndpoint.cancel(transfer.candidateId, reason))}>Stop (keep files)</Button>
                <Button isDisabled={busy || !authorized || !reason.trim() || transfer.state !== "STOPPED"} onPress={() => run(() => AcquisitionTransferEndpoint.retry(transfer.candidateId, reason))}>Resume owned torrent</Button>
                <Button isDisabled={busy || !reason.trim() || !["UNCERTAIN", "IN_FLIGHT"].includes(transfer.state)} onPress={() => run(() => AcquisitionTransferEndpoint.reconcile(transfer.candidateId, reason))}>Reconcile identity and stop</Button>
                <Button isDisabled={busy} onPress={() => run(async () => setAudit(await AcquisitionTransferEndpoint.audit(transfer.candidateId)))}>Show audit</Button>
            </div>
        </div>)}
        {audit.map((entry, index) => <p key={index} className="text-sm">{entry.recordedAt} — {entry.actor}: {entry.operation} — {entry.reason}</p>)}
    </section>;
}
