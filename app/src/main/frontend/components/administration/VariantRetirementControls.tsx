import {useEffect, useState} from "react";
import {Button, Card, CardBody, Input, Select, SelectItem} from "@heroui/react";
import {GameEndpoint, LibraryRetentionPolicyEndpoint} from "Frontend/generated/endpoints";
import VariantRetirementPreviewDto from "Frontend/generated/org/gameyfin/app/games/dto/VariantRetirementPreviewDto";
import VariantRetirementState from "Frontend/generated/org/gameyfin/app/games/entities/VariantRetirementState";
import LibraryRetentionPolicyMode from "Frontend/generated/org/gameyfin/app/libraries/entities/LibraryRetentionPolicyMode";
import VariantRetirementDecisionDto from "Frontend/generated/org/gameyfin/app/games/dto/VariantRetirementDecisionDto";
import VariantQuarantineDto from "Frontend/generated/org/gameyfin/app/games/dto/VariantQuarantineDto";
import {humanFileSize} from "Frontend/util/utils";

export default function VariantRetirementControls({gameId, libraryId, onChanged}: {gameId: number; libraryId: number; onChanged?: () => void}) {
    const [previews, setPreviews] = useState<VariantRetirementPreviewDto[]>([]);
    const [selectedId, setSelectedId] = useState<number>();
    const [reason, setReason] = useState("");
    const [confirmation, setConfirmation] = useState("");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const [mode, setMode] = useState(LibraryRetentionPolicyMode.KEEP_ALL);
    const [policyNumber, setPolicyNumber] = useState("30");
    const [replacementId, setReplacementId] = useState<number>();
    const [history, setHistory] = useState<VariantRetirementDecisionDto[]>([]);
    const [quarantineHistory, setQuarantineHistory] = useState<VariantQuarantineDto[]>([]);
    async function refresh() {
        const result = await GameEndpoint.getVariantRetirementPreview(gameId);
        setPreviews(result.filter((item): item is VariantRetirementPreviewDto => !!item));
    }
    useEffect(() => { refresh().catch((error) => setError(String(error))); }, [gameId]);
    useEffect(() => {
        LibraryRetentionPolicyEndpoint.get(libraryId).then((policy) => {
            setMode(policy.mode); setPolicyNumber(String(policy.keepLatestCount ?? policy.gracePeriodDays ?? 30));
        }).catch((error) => setError(String(error)));
    }, [libraryId]);
    useEffect(() => {
        if (!selectedId) { setHistory([]); setQuarantineHistory([]); return; }
        let canceled = false;
        GameEndpoint.getVariantRetirementHistory(gameId, selectedId).then((entries) => {
            if (!canceled) setHistory(entries.filter((entry): entry is VariantRetirementDecisionDto => !!entry));
        }).catch((error) => { if (!canceled) setError(String(error)); });
        GameEndpoint.getVariantQuarantineHistory(gameId, selectedId).then((entries) => {
            if (!canceled) setQuarantineHistory(entries.filter((entry): entry is VariantQuarantineDto => !!entry));
        }).catch((error) => { if (!canceled) setError(String(error)); });
        return () => { canceled = true; };
    }, [gameId, selectedId, previews]);
    const selected = previews.find((item) => item.variantId === selectedId);
    async function perform(action: () => Promise<unknown>) {
        setBusy(true); setError("");
        try { await action(); await refresh(); setConfirmation(""); onChanged?.(); }
        catch (error) { setError(error instanceof Error ? error.message : String(error)); }
        finally { setBusy(false); }
    }
    return <Card><CardBody className="gap-3">
        <p className="font-semibold">Version retirement</p>
        <p className="text-sm text-default-500">Archiving hides an older version and keeps its files. Quarantine moves only a verified, unused application mirror into recoverable storage. It never deletes files.</p>
        <Select label="Library retention policy" selectedKeys={[mode]} onSelectionChange={(keys) => setMode(Array.from(keys)[0] as LibraryRetentionPolicyMode)}>
            <SelectItem key={LibraryRetentionPolicyMode.KEEP_ALL}>Keep all versions</SelectItem>
            <SelectItem key={LibraryRetentionPolicyMode.KEEP_LATEST_N}>Keep latest N per variant</SelectItem>
            <SelectItem key={LibraryRetentionPolicyMode.GRACE_PERIOD}>Keep superseded versions for a grace period</SelectItem>
        </Select>
        {mode !== LibraryRetentionPolicyMode.KEEP_ALL && <Input type="number" min="1" label={mode === LibraryRetentionPolicyMode.GRACE_PERIOD ? "Grace period in days" : "Versions to keep"} value={policyNumber} onValueChange={setPolicyNumber}/>}
        <Button isDisabled={busy || (mode !== LibraryRetentionPolicyMode.KEEP_ALL && (!Number.isInteger(Number(policyNumber)) || Number(policyNumber) < 1))}
                onPress={() => perform(() => LibraryRetentionPolicyEndpoint.update(libraryId, {mode,
                    keepLatestCount: mode === LibraryRetentionPolicyMode.KEEP_LATEST_N ? Number(policyNumber) : undefined,
                    gracePeriodDays: mode === LibraryRetentionPolicyMode.GRACE_PERIOD ? Number(policyNumber) : undefined}))}>Save library policy</Button>
        <p className="text-xs text-default-500">Policies advise manual review. They do not schedule archive or quarantine actions.</p>
        <Select label="Review version" selectedKeys={selectedId ? [String(selectedId)] : []}
                onSelectionChange={(keys) => { setSelectedId(Number(Array.from(keys)[0])); setConfirmation(""); }}>
            {previews.map((item) => <SelectItem key={item.variantId}>{item.name} {item.version} — {item.retirementState}</SelectItem>)}
        </Select>
        {selected && <>
            <p className="text-sm">{selected.policyReason}</p>
            <p className="text-sm">{humanFileSize(selected.managedBytes)} · Selected content: {selected.selectedContentNames.join(", ") || "None"}</p>
            <p className="text-sm">Shared catalog paths: {selected.catalogDependentVariantIds.join(", ") || "None recorded"}. This preview does not verify physical hardlink dependencies or active downloads; both are checked before quarantine.</p>
            {selected.supersededAt && <p className="text-sm">Supersession observed: {String(selected.supersededAt)}</p>}
            <div className="text-xs break-all">{selected.effectivePaths.map((path) => <p key={path}>{path}</p>)}</div>
            {selected.quarantinePath && <p className="text-xs break-all">Recoverable mirror: {selected.quarantinePath}</p>}
            <Input label="Reason" value={reason} onValueChange={setReason} maxLength={255}/>
            <Select label="Newer replacement for supersession evidence" selectedKeys={replacementId ? [String(replacementId)] : []}
                    onSelectionChange={(keys) => setReplacementId(Number(Array.from(keys)[0]))}>
                {previews.filter((item) => item.variantId !== selected.variantId && item.retirementState === VariantRetirementState.ACTIVE)
                    .map((item) => <SelectItem key={item.variantId}>{item.name} {item.version}</SelectItem>)}
            </Select>
            <Button isDisabled={busy || !replacementId} onPress={() => perform(() => GameEndpoint.markVariantSuperseded(gameId, selected.variantId,
                {replacementVariantId: replacementId!, reason}))}>Record observed supersession</Button>
            <div className="flex gap-2 flex-wrap">
                <Button isDisabled={busy || !selected.archiveAllowed || !reason.trim()} onPress={() => perform(() => GameEndpoint.setVariantRetirementState(gameId, selected.variantId, {state: VariantRetirementState.ARCHIVED, reason}))}>Archive version</Button>
                <Button isDisabled={busy || selected.retirementState !== VariantRetirementState.ARCHIVED || !!selected.quarantinePath}
                        onPress={() => perform(() => GameEndpoint.setVariantRetirementState(gameId, selected.variantId, {state: VariantRetirementState.ACTIVE, reason}))}>Restore visibility</Button>
            </div>
            {selected.retirementState === VariantRetirementState.ARCHIVED && <>
                <Input label={selected.quarantinePath ? `Type RESTORE ${selected.variantId}` : `Type QUARANTINE ${selected.variantId}`}
                       value={confirmation} onValueChange={setConfirmation}/>
                {selected.quarantinePath ? <Button isDisabled={busy || confirmation !== `RESTORE ${selected.variantId}`}
                    onPress={() => perform(async () => {
                        const history = await GameEndpoint.getVariantQuarantineHistory(gameId, selected.variantId);
                        const record = [...history].reverse().find((item) => item && !item.restoredAt);
                        if (!record) throw new Error("No active quarantine record found");
                        return GameEndpoint.restoreVariantMirror(gameId, selected.variantId, record.id, confirmation);
                    })}>Restore mirror</Button> : <Button color="warning" isDisabled={busy || confirmation !== `QUARANTINE ${selected.variantId}` || !reason.trim()}
                    onPress={() => perform(() => GameEndpoint.quarantineVariantMirror(gameId, selected.variantId, {confirmation, reason, recoveryDays: 30}))}>Quarantine mirror for recovery</Button>}
            </>}
            <div className="text-xs text-default-500">{history.map((entry) => <p key={entry.id}>{String(entry.decidedAt)} · {entry.actor} · {entry.previousState} → {entry.newState} · {entry.reason}</p>)}</div>
            <div className="text-xs text-default-500">{quarantineHistory.map((entry) => <p key={entry.id}>
                {String(entry.quarantinedAt)} · Quarantined by {entry.actor} · {entry.reason} · Recovery retained at least until {String(entry.recoverableUntil)}
                {entry.restoredAt && ` · Restored ${entry.restoredAt} by ${entry.restoredBy}`}
            </p>)}</div>
        </>}
        {error && <p role="alert" className="text-sm text-danger">{error}</p>}
    </CardBody></Card>;
}
