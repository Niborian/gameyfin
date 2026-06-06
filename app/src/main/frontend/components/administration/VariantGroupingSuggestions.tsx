import React, {useEffect, useMemo, useState} from "react";
import {
    addToast,
    Button,
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
                    tags: ""
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

        const request: GroupGameAsVariantRequestDto = {
            sourceGameId: suggestion.sourceGameId,
            variantName: draft.variantName,
            version: draft.version,
            contentName: draft.contentName,
            contentType: draft.contentType,
            required: true,
            defaultSelected: true,
            tags: draft.tags.split(",").map((tag) => tag.trim()).filter(Boolean)
        };

        setGroupingSourceId(suggestion.sourceGameId);
        try {
            await GameEndpoint.groupGameAsVariant(suggestion.targetGameId, request);
            addToast({
                title: "Grouped variant",
                description: `${suggestion.sourceTitle ?? suggestion.sourcePath} is now a ${draft.variantName} variant.`,
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
                        return (
                            <TableRow key={`${suggestion.targetGameId}-${suggestion.sourceGameId}`}>
                                <TableCell>
                                    <div className="flex flex-col">
                                        <span>{suggestion.sourceTitle ?? "Unknown title"}</span>
                                        <span className="text-xs text-default-500 break-all">{suggestion.sourcePath}</span>
                                    </div>
                                </TableCell>
                                <TableCell>
                                    <div className="flex flex-col">
                                        <span>{suggestion.targetTitle ?? "Unknown title"}</span>
                                        <span className="text-xs text-default-500 break-all">{suggestion.targetPath}</span>
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
                                                        if (key) updateDraft(suggestion.sourceGameId, {contentType: key});
                                                    }}>
                                                {contentTypes.map((type) => (
                                                    <SelectItem key={type}>{type.replaceAll("_", " ")}</SelectItem>
                                                ))}
                                            </Select>
                                            <Input size="sm" label="Tags" placeholder="multiplayer, modpack"
                                                   value={draft.tags}
                                                   onValueChange={(value) => updateDraft(suggestion.sourceGameId, {tags: value})}
                                                   className="xl:col-span-2"/>
                                        </div>
                                    )}
                                </TableCell>
                                <TableCell>
                                    <Button
                                        color="primary"
                                        isLoading={groupingSourceId === suggestion.sourceGameId}
                                        isDisabled={!draft?.variantName || !draft?.version || !draft?.contentName}
                                        onPress={() => groupSuggestion(suggestion)}
                                    >
                                        Group
                                    </Button>
                                </TableCell>
                            </TableRow>
                        );
                    }}
                </TableBody>
            </Table>
        </div>
    );
}
