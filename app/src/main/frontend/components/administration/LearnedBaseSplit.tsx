import React, {useEffect, useRef, useState} from "react";
import {addToast, Button, Checkbox, Chip} from "@heroui/react";
import {LearnedBaseSplitEndpoint} from "Frontend/generated/endpoints";
import GameAdminDto from "Frontend/generated/org/gameyfin/app/games/dto/GameAdminDto";
import LearnedBaseSplitReviewDto from "Frontend/generated/org/gameyfin/app/games/dto/LearnedBaseSplitReviewDto";
import LearnedBaseSplitRuleDto from "Frontend/generated/org/gameyfin/app/games/dto/LearnedBaseSplitRuleDto";
import VariantContentType from "Frontend/generated/org/gameyfin/app/games/entities/VariantContentType";

function AutomationOffer({rule, onChanged}: {
    rule: LearnedBaseSplitRuleDto;
    onChanged: (rule: LearnedBaseSplitRuleDto) => void;
}) {
    const [mute, setMute] = useState(false);
    const [saving, setSaving] = useState(false);

    async function choose(choice: string) {
        setSaving(true);
        try {
            onChanged(await LearnedBaseSplitEndpoint.setPreference(rule.id, choice));
        } catch {
            addToast({title: "Could not save the layout preference", color: "danger"});
        } finally {
            setSaving(false);
        }
    }

    return <section className="flex flex-col gap-3 rounded-lg border border-secondary/40 bg-secondary/10 p-4"
                    aria-label="Automatic layout offer">
        <div className="flex flex-wrap items-center gap-2">
            <Chip size="sm" color="secondary" variant="flat">Admin only</Chip>
            <span className="text-small text-default-500">{rule.confirmations} confirmations in a row</span>
        </div>
        <div>
            <h3 className="font-semibold">Apply this layout automatically?</h3>
            <p className="text-small">Future scans can save {rule.layout} for matching new games in {rule.libraryName}.</p>
        </div>
        <Checkbox size="sm" isSelected={mute} onValueChange={setMute} isDisabled={saving}>Don’t ask again</Checkbox>
        <div className="flex flex-wrap gap-2">
            <Button size="sm" color="secondary" isDisabled={saving} onPress={() => void choose("enable")}>
                Yes, apply automatically
            </Button>
            <Button size="sm" variant="flat" isDisabled={saving} onPress={() => void choose(mute ? "mute" : "later")}>
                No, keep reviewing
            </Button>
        </div>
    </section>;
}

export function LearnedBaseSplitSuggestion({gameId, revision, onApplied}: {
    gameId: number;
    revision: number;
    onApplied: (game: GameAdminDto) => void;
}) {
    const [review, setReview] = useState<LearnedBaseSplitReviewDto>();
    const [refresh, setRefresh] = useState(0);
    const [saving, setSaving] = useState(false);
    const owner = useRef<AbortController | undefined>(undefined);

    useEffect(() => {
        const controller = new AbortController();
        owner.current = controller;
        setReview(undefined);
        LearnedBaseSplitEndpoint.review(gameId, {signal: controller.signal})
            .then(value => { if (!controller.signal.aborted) setReview(value); })
            .catch(() => { /* Optional suggestions should not delay the saved version picker. */ });
        return () => controller.abort();
    }, [gameId, revision, refresh]);

    const preview = review?.preview;
    async function decide(approve: boolean) {
        const scope = owner.current;
        if (!preview || saving || !scope || scope.signal.aborted) return;
        setSaving(true);
        try {
            const updated = await LearnedBaseSplitEndpoint.decide(gameId, preview.revision, approve, {signal: scope.signal});
            if (scope.signal.aborted) return;
            if (updated) onApplied(updated);
            setReview(undefined);
            if (!updated) setRefresh(value => value + 1);
            addToast({title: approve ? "Suggested split saved" : "Suggestion declined. Confirmation streak reset."});
        } catch {
            if (scope.signal.aborted) return;
            addToast({title: "Could not apply the decision. The suggestion will reload.", color: "warning"});
            setRefresh(value => value + 1);
        } finally {
            setSaving(false);
        }
    }

    return <>
        {preview && <section aria-label="Pending version suggestion"
            className="flex flex-col gap-3 rounded-lg border border-dashed border-secondary/40 bg-secondary/5 p-4">
            <div className="flex flex-col gap-3 opacity-55">
                <div className="flex flex-wrap items-center gap-2">
                    <Chip size="sm" color="secondary" variant="flat">Admin only</Chip>
                    <strong>Suggested · v{preview.version}</strong>
                    <span className="text-small">{preview.confirmations} confirmations</span>
                </div>
                <div className="grid gap-2 sm:grid-cols-2">
                    {preview.items.map(item => <div key={item.sourceName} className="min-w-0 break-words">
                        <div className="text-small">
                            {item.type === VariantContentType.BASE ? "Base game" : item.name}
                            {item.required ? " · required" : item.defaultSelected ? " · selected by default" : ""}
                        </div>
                        <div>{item.sourceName}</div>
                    </div>)}
                </div>
            </div>
            <div className="flex flex-wrap gap-2">
                <Button size="sm" color="secondary" isDisabled={saving} onPress={() => void decide(true)}>✓ Accept</Button>
                <Button size="sm" variant="flat" isDisabled={saving} onPress={() => void decide(false)}>✕ Decline</Button>
            </div>
        </section>}
        {review?.rule?.offer && <AutomationOffer key={review.rule.id} rule={review.rule}
            onChanged={rule => setReview(value => value && {...value, rule})}/>}
    </>;
}

export function LearnedBaseSplitSettings() {
    const [rules, setRules] = useState<LearnedBaseSplitRuleDto[]>([]);
    const [busy, setBusy] = useState<string>();
    const [failed, setFailed] = useState(false);
    useEffect(() => {
        const controller = new AbortController();
        LearnedBaseSplitEndpoint.settings({signal: controller.signal})
            .then(value => { if (!controller.signal.aborted) setRules(value); })
            .catch(() => { if (!controller.signal.aborted) setFailed(true); });
        return () => controller.abort();
    }, []);

    const update = (rule: LearnedBaseSplitRuleDto) =>
        setRules(current => current.map(item => item.id === rule.id ? rule : item));

    async function change(rule: LearnedBaseSplitRuleDto, choice: string) {
        setBusy(rule.id);
        try {
            update(await LearnedBaseSplitEndpoint.setPreference(rule.id, choice));
        } catch {
            addToast({title: "Could not save the layout preference", color: "danger"});
        } finally {
            setBusy(undefined);
        }
    }

    return <section className="mt-6 flex flex-col gap-3" aria-label="Learned layouts">
        <h2 className="text-xl font-bold">Learned layouts</h2>
        {failed && <p role="alert">Could not load learned layouts.</p>}
        {!failed && rules.length === 0 && <p className="text-small text-default-500">Layouts appear here after you review a suggestion.</p>}
        {rules.map(rule => <div key={rule.id} className="flex flex-col gap-2 rounded-lg bg-content2 p-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                    <strong>{rule.libraryName} · {rule.layout}</strong>
                    <p className="text-small">
                        {rule.automatic ? "Apply automatically" : rule.muted ? "Review each match · automation prompt off" : "Review each match"}
                    </p>
                </div>
                {rule.muted && <Button size="sm" variant="flat" isDisabled={busy === rule.id}
                    onPress={() => void change(rule, "ask")}>Ask again</Button>}
                {rule.automatic && <Button size="sm" variant="flat" isDisabled={busy === rule.id}
                    onPress={() => void change(rule, "disable")}>Review future matches</Button>}
            </div>
            {rule.offer && <AutomationOffer rule={rule} onChanged={update}/>}
        </div>)}
    </section>;
}
