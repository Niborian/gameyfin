import React, {useEffect, useMemo, useState} from "react";
import {
    addToast,
    Button,
    Checkbox,
    Chip,
    Input,
    Select,
    SelectItem,
    Table,
    TableBody,
    TableCell,
    TableColumn,
    TableHeader,
    TableRow
} from "@heroui/react";
import {GameEndpoint} from "Frontend/generated/endpoints";
import GameGroupingSuggestionDto
    from "Frontend/generated/org/gameyfin/app/games/dto/GameGroupingSuggestionDto";
import GroupGameAsVariantRequestDto
    from "Frontend/generated/org/gameyfin/app/games/dto/GroupGameAsVariantRequestDto";
import VariantContentType from "Frontend/generated/org/gameyfin/app/games/entities/VariantContentType";
import {useSnapshot} from "valtio/react";
import {libraryState} from "Frontend/state/LibraryState";

type Draft = {
    variantName: string;
    version: string;
    contentName: string;
    contentType: VariantContentType;
    tags: string;
    required: boolean;
    defaultSelected: boolean;
    swapped: boolean;
};

const contentTypes = [
    VariantContentType.BASE,
    VariantContentType.DLC,
    VariantContentType.DEDICATED_SERVER,
    VariantContentType.PATCH,
    VariantContentType.MOD,
    VariantContentType.EXTRA
];

export default function VariantGroupingSuggestions() {
    const libraries = useSnapshot(libraryState);
    const [selectedLibraryId, setSelectedLibraryId] = useState<number>();
    const [suggestions, setSuggestions] = useState<GameGroupingSuggestionDto[]>([]);
    const [drafts, setDrafts] = useState<Record<number, Draft>>({});
    const [isLoading, setIsLoading] = useState(false);
    const [groupingSourceId, setGroupingSourceId] = useState<number>();

    const selectedLibrary = useMemo(
        () => libraries.sorted.find((library) => library.id === selectedLibraryId),
        [libraries.sorted, selectedLibraryId]
    );

    useEffect(() => {
        if (!selectedLibraryId && libraries.sorted.length > 0) {
            setSelectedLibraryId(libraries.sorted[0].id);
        }
    }, [libraries.sorted, selectedLibraryId]);

    useEffect(() => {
        if (selectedLibraryId) {
            void refreshSuggestions(selectedLibraryId);
        }
    }, [selectedLibraryId]);

    async function refreshSuggestions(libraryId = selectedLibraryId) {
        if (!libraryId) return;

        setIsLoading(true);
        try {
            const nextSuggestions = await GameEndpoint.getGroupingSuggestions(libraryId);
            setSuggestions(nextSuggestions);
            setDrafts(Object.fromEntries(nextSuggestions.map((suggestion) => [
                suggestion.sourceGameId,
                {
                    variantName: suggestion.suggestedVariantName,
                    version: suggestion.suggestedVariantVersion,
                    contentName: "Base game",
                    contentType: VariantContentType.BASE,
                    tags: "",
                    required: true,
                    defaultSelected: true,
                    swapped: false
                }
            ])));
        } catch (error) {
            addToast({
                title: "Could not load grouping suggestions",
                description: error instanceof Error ? error.message : String(error),
                color: "danger"
            });
        } finally {
            setIsLoading(false);
        }
    }

    function updateDraft(sourceGameId: number, changed: Partial<Draft>) {
        setDrafts((current) => ({
            ...current,
            [sourceGameId]: {
                ...current[sourceGameId],
                ...changed
            }
        }));
    }

    async function groupSuggestion(suggestion: GameGroupingSuggestionDto) {
        const draft = drafts[suggestion.sourceGameId];
        if (!draft) return;
        const targetGameId = draft.swapped ? suggestion.sourceGameId : suggestion.targetGameId;
        const sourceGameId = draft.swapped ? suggestion.targetGameId : suggestion.sourceGameId;
        const sourceLabel = draft.swapped
            ? suggestion.targetTitle ?? suggestion.targetPath
            : suggestion.sourceTitle ?? suggestion.sourcePath;

        const request: GroupGameAsVariantRequestDto = {
            sourceGameId: sourceGameId,
            variantName: draft.variantName,
            version: draft.version,
            contentName: draft.contentName,
            contentType: draft.contentType,
            required: draft.required,
            defaultSelected: draft.required || draft.defaultSelected,
            tags: draft.tags.split(",").map((tag) => tag.trim()).filter(Boolean)
        };

        setGroupingSourceId(sourceGameId);
        try {
            await GameEndpoint.groupGameAsVariant(targetGameId, request);
            addToast({
                title: "Grouped variant",
                description: `${sourceLabel} is now grouped under ${draft.variantName} ${draft.version}.`,
                color: "success"
            });
            await refreshSuggestions();
        } catch (error) {
            addToast({
                title: "Could not group variant",
                description: error instanceof Error ? error.message : String(error),
                color: "danger"
            });
        } finally {
            setGroupingSourceId(undefined);
        }
    }

    if (libraries.sorted.length === 0) {
        return null;
    }

    return (
        <div className="flex flex-col gap-4 mt-4">
            <div className="flex flex-col md:flex-row gap-3 md:items-end md:justify-between">
                <div>
                    <p className="font-semibold">Variant grouping suggestions</p>
                    <p className="text-sm text-default-500">
                        Group duplicate game entries as variants without moving or copying their files.
                        Use swap when the base archive should be the target and the folder should be added as patch/DLC/mod content.
                    </p>
                </div>
                <div className="flex flex-row gap-2 items-end">
                    <Select
                        label="Library"
                        className="min-w-56"
                        selectedKeys={selectedLibraryId ? [selectedLibraryId.toString()] : []}
                        onSelectionChange={(keys) => {
                            const key = Array.from(keys)[0]?.toString();
                            setSelectedLibraryId(key ? Number(key) : undefined);
                        }}
                    >
                        {libraries.sorted.map((library) => (
                            <SelectItem key={library.id.toString()}>{library.name}</SelectItem>
                        ))}
                    </Select>
                    <Button variant="flat" isLoading={isLoading} onPress={() => refreshSuggestions()}>
                        Refresh
                    </Button>
                </div>
            </div>

            <Table removeWrapper aria-label={`Variant grouping suggestions for ${selectedLibrary?.name ?? "library"}`}>
                <TableHeader>
                    <TableColumn>Source</TableColumn>
                    <TableColumn>Target</TableColumn>
                    <TableColumn>Confidence</TableColumn>
                    <TableColumn>Variant metadata</TableColumn>
                    <TableColumn width={1}>Action</TableColumn>
                </TableHeader>
                <TableBody
                    isLoading={isLoading}
                    emptyContent="No strong grouping suggestions found."
                    items={suggestions}
                >
                    {(suggestion) => {
                        const draft = drafts[suggestion.sourceGameId];
                        const sourceTitle = draft?.swapped ? suggestion.targetTitle : suggestion.sourceTitle;
                        const sourcePath = draft?.swapped ? suggestion.targetPath : suggestion.sourcePath;
                        const targetTitle = draft?.swapped ? suggestion.sourceTitle : suggestion.targetTitle;
                        const targetPath = draft?.swapped ? suggestion.sourcePath : suggestion.targetPath;
                        const effectiveSourceId = draft?.swapped ? suggestion.targetGameId : suggestion.sourceGameId;
                        return (
                            <TableRow key={`${suggestion.targetGameId}-${suggestion.sourceGameId}`}>
                                <TableCell>
                                    <div className="flex flex-col">
                                        <span>{sourceTitle ?? "Unknown title"}</span>
                                        <span className="text-xs text-default-500 break-all">{sourcePath}</span>
                                    </div>
                                </TableCell>
                                <TableCell>
                                    <div className="flex flex-col">
                                        <span>{targetTitle ?? "Unknown title"}</span>
                                        <span className="text-xs text-default-500 break-all">{targetPath}</span>
                                    </div>
                                </TableCell>
                                <TableCell>
                                    <div className="flex flex-col gap-1">
                                        <Chip size="sm" color={suggestion.autoGroup ? "success" : "warning"} variant="flat">
                                            {suggestion.confidence}%
                                        </Chip>
                                        <span className="text-xs text-default-500">{suggestion.reason}</span>
                                    </div>
                                </TableCell>
                                <TableCell>
                                    {draft && (
                                        <div className="grid grid-cols-1 xl:grid-cols-2 gap-2 min-w-96">
                                            <Input size="sm" label="Variant" value={draft.variantName}
                                                   onValueChange={(value) => updateDraft(suggestion.sourceGameId, {variantName: value})}/>
                                            <Input size="sm" label="Version" value={draft.version}
                                                   onValueChange={(value) => updateDraft(suggestion.sourceGameId, {version: value})}/>
                                            <Input size="sm" label="Content name" value={draft.contentName}
                                                   onValueChange={(value) => updateDraft(suggestion.sourceGameId, {contentName: value})}/>
                                            <Select size="sm" label="Content type"
                                                    selectedKeys={[draft.contentType]}
                                                    onSelectionChange={(keys) => {
                                                        const key = Array.from(keys)[0]?.toString() as VariantContentType | undefined;
                                                        if (key) {
                                                            updateDraft(suggestion.sourceGameId, {
                                                                contentType: key,
                                                                required: key === VariantContentType.BASE,
                                                                defaultSelected: key === VariantContentType.BASE
                                                            });
                                                        }
                                                    }}>
                                                {contentTypes.map((type) => (
                                                    <SelectItem key={type}>{type.replaceAll("_", " ")}</SelectItem>
                                                ))}
                                            </Select>
                                            <Input size="sm" label="Tags" placeholder="multiplayer, modpack"
                                                   value={draft.tags}
                                                   onValueChange={(value) => updateDraft(suggestion.sourceGameId, {tags: value})}
                                                   className="xl:col-span-2"/>
                                            <div className="flex flex-row gap-4 xl:col-span-2">
                                                <Checkbox
                                                    isSelected={draft.required}
                                                    onValueChange={(selected) => updateDraft(suggestion.sourceGameId, {
                                                        required: selected,
                                                        defaultSelected: selected || draft.defaultSelected
                                                    })}
                                                >
                                                    Required
                                                </Checkbox>
                                                <Checkbox
                                                    isSelected={draft.required || draft.defaultSelected}
                                                    isDisabled={draft.required}
                                                    onValueChange={(selected) => updateDraft(suggestion.sourceGameId, {defaultSelected: selected})}
                                                >
                                                    Default selected
                                                </Checkbox>
                                            </div>
                                        </div>
                                    )}
                                </TableCell>
                                <TableCell>
                                    <div className="flex flex-col gap-2">
                                        <Button
                                            size="sm"
                                            variant="flat"
                                            onPress={() => updateDraft(suggestion.sourceGameId, {swapped: !draft?.swapped})}
                                        >
                                            Swap source/target
                                        </Button>
                                        <Button
                                            color="primary"
                                            isLoading={groupingSourceId === effectiveSourceId}
                                            isDisabled={!draft?.variantName || !draft?.version || !draft?.contentName}
                                            onPress={() => groupSuggestion(suggestion)}
                                        >
                                            Group
                                        </Button>
                                    </div>
                                </TableCell>
                            </TableRow>
                        );
                    }}
                </TableBody>
            </Table>
        </div>
    );
}
