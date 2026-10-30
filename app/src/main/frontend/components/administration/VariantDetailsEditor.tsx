import {useLayoutEffect, useRef, useState} from "react";
import {Button, Checkbox, Chip, Input, Select, SelectItem} from "@heroui/react";
import {GameEndpoint, VariantDetailsEndpoint} from "Frontend/generated/endpoints";
import GameVariantDto from "Frontend/generated/org/gameyfin/app/games/dto/GameVariantDto";
import VariantDetailsDto from "Frontend/generated/org/gameyfin/app/games/dto/VariantDetailsDto";
import VariantDetailsPreviewDto from "Frontend/generated/org/gameyfin/app/games/dto/VariantDetailsPreviewDto";
import VariantStore from "Frontend/generated/org/gameyfin/app/games/entities/VariantStore";
import {useAuth} from "Frontend/util/auth";
import {isAdmin} from "Frontend/util/utils";

type Fields = {store: VariantStore; storeProductId: string; edition: string; libraryVersion: string; buildId: string; manualOverride: boolean};
function fields(value?: VariantDetailsDto): Fields {
    return {store: value?.store ?? VariantStore.UNKNOWN, storeProductId: value?.storeProductId ?? "", edition: value?.edition ?? "",
        libraryVersion: value?.libraryVersion ?? "", buildId: value?.buildId ?? "", manualOverride: value?.detailsManualOverride ?? false};
}

/** Metadata only. No scan, network detection or source/content changes on mount. */
export default function VariantDetailsEditor({gameId, variant, onChanged, onDirtyChange}: {gameId: number; variant: GameVariantDto; onChanged?: () => void; onDirtyChange?: (dirty: boolean) => void}) {
    const auth = useAuth();
    const permitted = !auth.state.initializing && !auth.state.loading && isAdmin(auth);
    const identity = `${gameId}:${variant.id}`;
    const owner = useRef({identity, actor: auth.state.user, permitted, epoch: 0, mounted: true, request: 0});
    // Fence old completions synchronously, before effect cleanup on actor/target changes.
    if (owner.current.identity !== identity || owner.current.actor !== auth.state.user || owner.current.permitted !== permitted) {
        owner.current = {identity, actor: auth.state.user, permitted, epoch: owner.current.epoch + 1, mounted: true, request: owner.current.request + 1};
    }
    const [draft, setDraft] = useState<Fields>(() => fields());
    const [saved, setSaved] = useState<Fields>(() => fields());
    const [revision, setRevision] = useState("");
    const [manualProtected, setManualProtected] = useState(false);
    const [provenance, setProvenance] = useState("Unknown");
    const [evidence, setEvidence] = useState("");
    const [loadedEpoch, setLoadedEpoch] = useState<number>();
    const [loadError, setLoadError] = useState(false);
    const [reload, setReload] = useState(0);
    const [preview, setPreview] = useState<VariantDetailsPreviewDto>();
    const [busy, setBusy] = useState<"preview" | "save" | "apply" | "track">();
    const [message, setMessage] = useState("");
    const dirty = JSON.stringify(draft) !== JSON.stringify(saved);
    const update = (change: Partial<Fields>) => setDraft(current => ({...current, ...change, manualOverride: change.manualOverride ?? true}));
    const begin = () => {
        const snapshot = owner.current;
        const token = {epoch: snapshot.epoch, request: ++snapshot.request, actor: snapshot.actor, identity: snapshot.identity};
        return () => owner.current.mounted && owner.current.permitted && owner.current.epoch === token.epoch &&
            owner.current.request === token.request && owner.current.actor === token.actor && owner.current.identity === token.identity;
    };
    function accept(value: VariantDetailsDto) {
        const next = fields(value);
        setDraft(next); setSaved(next); setRevision(value.detailsRevision); setManualProtected(value.detailsManualOverride);
        setProvenance(value.detailsSource ? String(value.detailsSource) : "Unknown"); setEvidence(value.detailsEvidence ?? ""); setPreview(undefined);
        setMessage("Details saved. Source files and download selections were not changed."); onChanged?.();
    }
    useLayoutEffect(() => {
        owner.current.mounted = true;
        setLoadedEpoch(undefined); setLoadError(false); setBusy(undefined); setPreview(undefined); setMessage("");
        if (permitted) {
            const current = begin();
            VariantDetailsEndpoint.getDetails(gameId, variant.id).then(value => {
                if (!current()) return;
                const next = fields(value);
                setDraft(next); setSaved(next); setRevision(value.detailsRevision); setManualProtected(value.detailsManualOverride);
                setProvenance(value.detailsSource ? String(value.detailsSource) : "Unknown"); setEvidence(value.detailsEvidence ?? ""); setLoadedEpoch(owner.current.epoch);
            }).catch(() => {if (current()) setLoadError(true);});
        }
        return () => {owner.current.mounted = false; owner.current.epoch++; owner.current.request++;};
    }, [identity, auth.state.user, permitted, reload]);
    useLayoutEffect(() => {
        onDirtyChange?.(dirty && loadedEpoch === owner.current.epoch);
        return () => onDirtyChange?.(false);
    }, [dirty, loadedEpoch, onDirtyChange]);
    function reloadSaved() {
        if (busy) return;
        if (dirty && !window.confirm("Discard your unsaved variant details and reload the saved values?")) return;
        owner.current.epoch++; owner.current.request++;
        setReload(value => value + 1);
    }
    async function watchSteamUpdates(enabled: boolean) {
        if (!permitted || busy || dirty || loadedEpoch !== owner.current.epoch || (enabled && saved.store !== VariantStore.STEAM)) return;
        const current = begin(); setBusy("track"); setMessage("");
        try {
            await GameEndpoint.setVariantSteamNewsWatch(gameId, variant.id, enabled, enabled ? saved.storeProductId : undefined);
            if (current()) {setMessage(enabled ? "Steam app mapping verified for update notices. Enable monitoring under Administration → Game Requests. No download was started." : "Steam update tracking stopped for this variant. Local details and files are unchanged."); onChanged?.();}
        } catch {if (current()) setMessage("Could not verify the Steam mapping. Check the saved numeric product ID and try again.");}
        finally {if (current()) setBusy(undefined);}
    }
    async function detect() {
        if (!permitted || busy || dirty || loadedEpoch !== owner.current.epoch) return;
        const current = begin(); setBusy("preview"); setMessage("");
        try {
            const value = await VariantDetailsEndpoint.preview(gameId, variant.id);
            if (!current()) return;
            setPreview(value); setMessage(value.message);
        } catch {if (current()) setMessage("Local preview failed. No details were changed. Try again or edit manually.");}
        finally {if (current()) setBusy(undefined);}
    }
    async function save() {
        if (!permitted || busy || !dirty || loadedEpoch !== owner.current.epoch) return;
        const current = begin(); setBusy("save"); setMessage("");
        try {
            const value = await VariantDetailsEndpoint.update(gameId, variant.id, {expectedRevision: revision, ...draft,
                storeProductId: draft.storeProductId.trim() || undefined, edition: draft.edition.trim() || undefined,
                libraryVersion: draft.libraryVersion.trim() || undefined, buildId: draft.buildId.trim() || undefined});
            if (current()) accept(value);
        } catch {if (current()) setMessage("Details were not saved: check the field formats, or reload if saved details changed elsewhere. Your edits remain here until you choose to discard them.");}
        finally {if (current()) setBusy(undefined);}
    }
    async function apply(index: number) {
        if (!permitted || busy || dirty || manualProtected || !preview || loadedEpoch !== owner.current.epoch) return;
        const current = begin(); setBusy("apply"); setMessage("");
        try {
            const value = await VariantDetailsEndpoint.applyPreview(gameId, variant.id,
                {expectedRevision: revision, previewDigest: preview.previewDigest, suggestionIndex: index});
            if (current()) accept(value);
        } catch {if (current()) setMessage("Suggestion was not applied. Local evidence or details may have changed; request a fresh preview.");}
        finally {if (current()) setBusy(undefined);}
    }
    if (!permitted) return null;
    if (loadedEpoch !== owner.current.epoch) return <section className="rounded-lg border border-default-200 p-3" aria-label="Variant identity details">
        <h3 className="font-semibold">{variant.name} · variant details</h3>
        {loadError ? <><p role="status">Details could not be loaded. Editing is unavailable; no defaults were saved.</p>
            <Button size="sm" variant="flat" onPress={() => setReload(value => value + 1)}>Retry loading details</Button></>
            : <p role="status">Loading saved details…</p>}
    </section>;
    return <section className="rounded-lg border border-default-200 p-3 flex flex-col gap-3" aria-label="Variant identity details">
        <div className="flex flex-wrap gap-2 items-center"><h3 className="font-semibold">{variant.name} · variant details</h3>
            <Chip size="sm" variant="flat">{provenance}</Chip>{dirty && <Chip size="sm" variant="flat">Unsaved edits</Chip>}</div>
        <p className="text-xs text-default-500">Store → edition → version/build in your library. These describe this copy, not store ownership or whether it is up to date.</p>
        {evidence && <p className="text-xs text-default-500 break-all">Saved evidence: {evidence}</p>}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            <Select size="sm" label="Store" selectedKeys={[draft.store]} isDisabled={!!busy}
                    onSelectionChange={keys => {const value = Array.from(keys)[0]; if (value) update({store: String(value) as VariantStore});}}>
                {Object.values(VariantStore).map(store => <SelectItem key={store}>{store === VariantStore.UNKNOWN ? "Unknown" : store}</SelectItem>)}
            </Select>
            <Input size="sm" label="Store product ID (optional)" description="Steam/GOG: numeric ID. Epic: catalog item ID. Not a URL." maxLength={128} value={draft.storeProductId} isDisabled={!!busy} onValueChange={storeProductId => update({storeProductId})}/>
            <Input size="sm" label="Edition" placeholder="Unknown / e.g. Deluxe" maxLength={128} value={draft.edition} isDisabled={!!busy} onValueChange={edition => update({edition})}/>
            <Input size="sm" label="Library version" placeholder="Unknown" maxLength={128} value={draft.libraryVersion} isDisabled={!!busy} onValueChange={libraryVersion => update({libraryVersion})}/>
            <Input size="sm" label="Library build ID" placeholder="Unknown" maxLength={128} value={draft.buildId} isDisabled={!!busy} onValueChange={buildId => update({buildId})}/>
        </div>
        <div className="flex flex-wrap gap-2">
            {saved.store === VariantStore.STEAM && <Button type="button" size="sm" variant="flat" isLoading={busy === "track"}
                isDisabled={!!busy || dirty || !/^[1-9][0-9]{0,19}$/.test(saved.storeProductId)} onPress={() => watchSteamUpdates(true)}>Watch Steam updates</Button>}
            {variant.steamAppId && variant.steamNewsWatchEnabled !== false && <Button type="button" size="sm" variant="light" isDisabled={!!busy || dirty}
                onPress={() => watchSteamUpdates(false)}>Stop watching Steam app {variant.steamAppId}</Button>}
            {variant.steamNewsWatchEnabled === false && <span className="text-xs text-default-500 self-center">Steam update monitoring paused</span>}
        </div>
        {draft.store === VariantStore.UNKNOWN && draft.storeProductId.trim() && <p className="text-xs text-warning">Choose a store for this product ID, or clear the ID explicitly. It will not be cleared automatically.</p>}
        <Checkbox isSelected={draft.manualOverride} isDisabled={!!busy} onValueChange={manualOverride => update({manualOverride})}>Protect manual details from detected suggestions</Checkbox>
        <div className="flex flex-wrap gap-2">
            <Button size="sm" color="primary" isLoading={busy === "save"} isDisabled={!!busy || !dirty} onPress={save}>Save details</Button>
            <Button size="sm" variant="flat" isLoading={busy === "preview"} isDisabled={!!busy || dirty} onPress={detect}>Preview local evidence</Button>
            <Button size="sm" variant="light" isDisabled={!!busy} onPress={reloadSaved}>Reload saved details</Button>
        </div>
        <p className="text-xs text-default-500">Preview reads local metadata only. Nothing is detected automatically. To replace protected manual values, first uncheck protection and explicitly save.</p>
        {dirty && <p className="text-xs">Save store/product changes before previewing local evidence, or explicitly reload to discard your edits.</p>}
        {message && <p role="status" className="text-sm">{message}</p>}
        {preview && <div className="flex flex-col gap-2">
            {preview.truncated && <p className="text-sm text-warning">Preview is limited; uninspected files remain unknown.</p>}
            {preview.suggestions.length === 0 && <p className="text-sm">Unknown: no supported local version/build evidence found.</p>}
            {preview.suggestions.length > 1 && <p className="text-sm text-warning">Multiple candidates: review the evidence; no candidate was selected automatically.</p>}
            {preview.suggestions.map((item, index) => <div key={`${item.source}:${index}`} className="rounded-md bg-default-100 p-2 text-sm">
                <p>{item.kind} · version {item.version || "Unknown"} · build {item.build || "Unknown"}</p>
                <p className="text-xs break-all">{item.source}</p><p className="text-xs text-default-500">{item.message}</p>
                <Button size="sm" variant="flat" isLoading={busy === "apply"} isDisabled={!!busy || dirty || manualProtected || (!item.version && !item.build)}
                        onPress={() => apply(index)}>Apply this detected suggestion</Button>
            </div>)}
            {dirty && <p className="text-xs">Save your manual edits before applying a suggestion. Preview never overwrites the editor.</p>}
        </div>}
    </section>;
}
