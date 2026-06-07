import React, {useEffect, useMemo, useState} from "react";
import {
    addToast,
    Button,
    Card,
    CardBody,
    Checkbox,
    Chip,
    Divider,
    Input,
    Modal,
    ModalBody,
    ModalContent,
    ModalFooter,
    ModalHeader,
    Select,
    SelectItem,
    Textarea,
    Tooltip,
    useDisclosure
} from "@heroui/react";
import {FilesystemEndpoint, GameEndpoint} from "Frontend/generated/endpoints";
import {GameAdminDto} from "Frontend/dtos/GameDtos";
import GameGroupingSuggestionDto
    from "Frontend/generated/org/gameyfin/app/games/dto/GameGroupingSuggestionDto";
import VariantContentType from "Frontend/generated/org/gameyfin/app/games/entities/VariantContentType";
import GameVariantDto from "Frontend/generated/org/gameyfin/app/games/dto/GameVariantDto";
import VariantContentDto from "Frontend/generated/org/gameyfin/app/games/dto/VariantContentDto";
import FileDto from "Frontend/generated/org/gameyfin/app/core/filesystem/FileDto";
import {humanFileSize} from "Frontend/util/utils";
import ContentPathPickerModal from "Frontend/components/general/modals/ContentPathPickerModal";
import {gameState} from "Frontend/state/GameState";
import {CheckIcon, FolderOpenIcon, PlusIcon, TrashIcon, XIcon} from "@phosphor-icons/react";

interface VariantManagerModalProps {
    game?: GameAdminDto;
    libraryGames: GameAdminDto[];
    suggestions: GameGroupingSuggestionDto[];
    isOpen: boolean;
    onOpenChange: () => void;
    onChanged?: () => void;
}

type DraftEntry = {
    selected: boolean;
    path: string;
    paths: string;
    contentName: string;
    contentType: VariantContentType;
    required: boolean;
    defaultSelected: boolean;
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

export default function VariantManagerModal({
                                                game,
                                                libraryGames,
                                                suggestions,
                                                isOpen,
                                                onOpenChange,
                                                onChanged
                                            }: VariantManagerModalProps) {
    const pathPicker = useDisclosure();
    const variants = (game?.variants ?? []) as GameVariantDto[];
    const defaultVariant = preferredVariant(variants);

    const [selectedVariantId, setSelectedVariantId] = useState<number>();
    const [sourceGameId, setSourceGameId] = useState<number>();
    const [sourceRootPath, setSourceRootPath] = useState("");
    const [variantName, setVariantName] = useState("Normal");
    const [version, setVersion] = useState("0");
    const [splitChildren, setSplitChildren] = useState(false);
    const [newEntriesType, setNewEntriesType] = useState<VariantContentType>(VariantContentType.DLC);
    const [entries, setEntries] = useState<DraftEntry[]>([]);
    const [groupSelectedPaths, setGroupSelectedPaths] = useState(false);
    const [groupContentName, setGroupContentName] = useState("Grouped content");
    const [groupTags, setGroupTags] = useState("");
    const [groupRequired, setGroupRequired] = useState(false);
    const [groupDefaultSelected, setGroupDefaultSelected] = useState(true);
    const [shareWithAllVariants, setShareWithAllVariants] = useState(false);
    const [contentDrafts, setContentDrafts] = useState<Record<number, DraftEntry>>({});
    const [isSaving, setIsSaving] = useState(false);
    const [isLoadingChildren, setIsLoadingChildren] = useState(false);
    const [savingContentId, setSavingContentId] = useState<number>();
    const [deletingContentId, setDeletingContentId] = useState<number>();
    const [removingSourceId, setRemovingSourceId] = useState<number>();
    const [settingDefaultVariantId, setSettingDefaultVariantId] = useState<number>();

    const selectedVariant = variants.find((variant) => variant.id === selectedVariantId) ?? defaultVariant;
    const relevantSuggestions = useMemo(() => {
        if (!game) return [];
        return suggestions.filter((suggestion) =>
            suggestion.targetGameId === game.id || suggestion.sourceGameId === game.id
        );
    }, [game?.id, suggestions]);

    useEffect(() => {
        setSelectedVariantId(defaultVariant?.id);
        setVariantName(defaultVariant?.name ?? "Normal");
        setVersion(defaultVariant?.version ?? "0");
        setContentDrafts(Object.fromEntries(variants.flatMap((variant) =>
            (variant.contents ?? []).map((content: VariantContentDto) => [content.id, createDraftFromContent(content)])
        )));
        clearDraft();
    }, [game?.id, defaultVariant?.id, variants.length]);

    function preferredVariant(variants: GameVariantDto[]) {
        return variants.find((variant) => variant.defaultLocked)
            ?? variants.find((variant) => variant.name.toLowerCase() === "normal" && variant.latestForVariant)
            ?? variants.find((variant) => variant.default)
            ?? variants.find((variant) => variant.latestForVariant)
            ?? variants[0];
    }

    function clearDraft() {
        setSourceGameId(undefined);
        setSourceRootPath("");
        setSplitChildren(false);
        setNewEntriesType(VariantContentType.DLC);
        setGroupSelectedPaths(false);
        setGroupContentName("Grouped content");
        setGroupTags("");
        setGroupRequired(false);
        setGroupDefaultSelected(true);
        setShareWithAllVariants(false);
        setEntries([]);
    }

    function gameLabel(gameId: number, fallbackTitle?: string | null, fallbackPath?: string | null) {
        const match = libraryGames.find((candidate) => candidate.id === gameId);
        return {
            title: match?.title ?? fallbackTitle ?? "Unknown game",
            path: match?.metadata.path ?? fallbackPath ?? ""
        };
    }

    function useSuggestion(suggestion: GameGroupingSuggestionDto) {
        if (!game) return;

        const source = suggestion.targetGameId === game.id
            ? gameLabel(suggestion.sourceGameId, suggestion.sourceTitle, suggestion.sourcePath)
            : gameLabel(suggestion.targetGameId, suggestion.targetTitle, suggestion.targetPath);
        const nextSourceGameId = suggestion.targetGameId === game.id
            ? suggestion.sourceGameId
            : suggestion.targetGameId;

        setSourceGameId(nextSourceGameId);
        setSourceRootPath(source.path);
        setSplitChildren(false);
        setEntries([createEntry(source.path, "Patch", VariantContentType.PATCH)]);
    }

    function createEntry(path: string, name: string, type: VariantContentType): DraftEntry {
        return {
            selected: true,
            path,
            paths: path,
            contentName: name,
            contentType: type,
            required: type === VariantContentType.BASE,
            defaultSelected: type === VariantContentType.BASE,
            tags: ""
        };
    }

    function createDraftFromContent(content: VariantContentDto): DraftEntry {
        return {
            selected: true,
            path: content.path ?? "",
            paths: ((content.paths?.length ? content.paths : [content.path]).filter(Boolean) as string[]).join("\n"),
            contentName: content.name,
            contentType: content.type,
            required: content.required,
            defaultSelected: content.defaultSelected,
            tags: (content.tags ?? []).join(", ")
        };
    }

    function updateEntry(index: number, changed: Partial<DraftEntry>) {
        setEntries((current) => current.map((entry, entryIndex) => {
            if (entryIndex !== index) return entry;
            const next = {...entry, ...changed};
            if (changed.contentType === VariantContentType.BASE) {
                next.required = true;
                next.defaultSelected = true;
            }
            if (changed.required === true) {
                next.defaultSelected = true;
            }
            return next;
        }));
    }

    function onPathSelected(path: string) {
        setSourceGameId(undefined);
        setSourceRootPath(path);
        setSplitChildren(false);
        setEntries([createEntry(path, path.split(/[\\/]/).filter(Boolean).pop() ?? "DLC", newEntriesType)]);
    }

    async function toggleSplitChildren(selected: boolean) {
        setSplitChildren(selected);
        if (!selected) {
            setGroupSelectedPaths(false);
        }
        if (!selected || !sourceRootPath) {
            if (sourceRootPath && entries.length === 0) {
                setEntries([createEntry(sourceRootPath, sourceRootPath.split(/[\\/]/).filter(Boolean).pop() ?? "Content", newEntriesType)]);
            }
            return;
        }

        setIsLoadingChildren(true);
        try {
            const children = await FilesystemEndpoint.listContents(sourceRootPath) as FileDto[];
            setEntries(children.map((child) =>
                createEntry(child.path ?? joinPath(sourceRootPath, child.name), child.name, newEntriesType)
            ));
        } catch (error) {
            addToast({
                title: "Could not list content",
                description: error instanceof Error ? error.message : String(error),
                color: "danger"
            });
        } finally {
            setIsLoadingChildren(false);
        }
    }

    function joinPath(root: string, name: string) {
        return `${root.replace(/[\\/]$/, "")}/${name}`;
    }

    function parsePaths(value: string) {
        return value.split(/\r?\n/)
            .map((path) => path.trim())
            .filter(Boolean)
            .filter((path, index, paths) => paths.indexOf(path) === index);
    }

    function updateContentDraft(contentId: number, changed: Partial<DraftEntry>) {
        setContentDrafts((current) => {
            const currentDraft = current[contentId];
            if (!currentDraft) return current;
            const next = {...currentDraft, ...changed};
            if (changed.contentType === VariantContentType.BASE) {
                next.required = true;
                next.defaultSelected = true;
            }
            if (changed.required === true) {
                next.defaultSelected = true;
            }
            return {
                ...current,
                [contentId]: next
            };
        });
    }

    function applyTypeToNewEntries(type: VariantContentType) {
        setNewEntriesType(type);
        if (type === VariantContentType.BASE) {
            setGroupContentName("Base game");
            setGroupRequired(true);
            setGroupDefaultSelected(true);
            setShareWithAllVariants(false);
        } else if (groupContentName === "Base game") {
            setGroupContentName("Grouped content");
        }
        setEntries((current) => current.map((entry) => ({
            ...entry,
            contentType: type,
            required: type === VariantContentType.BASE || entry.required,
            defaultSelected: type === VariantContentType.BASE || entry.defaultSelected
        })));
    }

    async function attachContent() {
        if (!game) return;
        const selectedEntries = entries.filter((entry) => entry.selected);
        if (!sourceRootPath || selectedEntries.length === 0) return;

        const requestEntries = groupSelectedPaths && splitChildren && selectedEntries.length > 1
            ? [{
                path: selectedEntries[0].path,
                paths: selectedEntries.map((entry) => entry.path),
                contentName: groupContentName,
                contentType: newEntriesType,
                required: groupRequired || newEntriesType === VariantContentType.BASE,
                defaultSelected: groupRequired || groupDefaultSelected || newEntriesType === VariantContentType.BASE,
                tags: groupTags.split(",").map((tag) => tag.trim()).filter(Boolean)
            }]
            : selectedEntries.map((entry) => ({
                path: entry.path,
                paths: parsePaths(entry.paths || entry.path),
                contentName: entry.contentName,
                contentType: entry.contentType,
                required: entry.required,
                defaultSelected: entry.required || entry.defaultSelected,
                tags: entry.tags.split(",").map((tag) => tag.trim()).filter(Boolean)
            }));
        const isSingleBaseVariant = requestEntries.length === 1 &&
            requestEntries[0].contentType === VariantContentType.BASE &&
            requestEntries[0].paths.length === 1;
        const shouldShareWithAllVariants = shareWithAllVariants &&
            !isSingleBaseVariant &&
            variants.length > 1 &&
            requestEntries.some((entry) => entry.contentType !== VariantContentType.BASE);
        setIsSaving(true);
        try {
            const updated = await GameEndpoint.attachVariantContent(game.id, {
                sourceGameId,
                sourceRootPath,
                targetVariantId: shouldShareWithAllVariants || isSingleBaseVariant ? undefined : selectedVariant?.id,
                targetVariantIds: shouldShareWithAllVariants ? variants.map((variant) => variant.id) : undefined,
                variantName: isSingleBaseVariant ? variantName : selectedVariant?.name ?? variantName,
                version: isSingleBaseVariant ? version : selectedVariant?.version ?? version,
                entries: requestEntries
            });

            // @ts-ignore Hilla returns the admin shape for admins.
            gameState.state[updated.id] = updated;
            if (sourceGameId) {
                delete gameState.state[sourceGameId];
            }
            clearDraft();
            onChanged?.();
            addToast({
                title: "Variant content saved",
                description: "Gameyfin metadata was updated without moving files.",
                color: "success"
            });
        } catch (error) {
            addToast({
                title: "Could not save variant content",
                description: error instanceof Error ? error.message : String(error),
                color: "danger"
            });
        } finally {
            setIsSaving(false);
        }
    }

    async function setDefaultVariant(variant: GameVariantDto) {
        if (!game) return;

        setSettingDefaultVariantId(variant.id);
        try {
            const updated = await GameEndpoint.setDefaultVariant(game.id, variant.id);
            // @ts-ignore Hilla returns the admin shape for admins.
            gameState.state[updated.id] = updated;
            onChanged?.();
            addToast({
                title: "Default version updated",
                description: `${variant.name} ${variant.version} is now the default download.`,
                color: "success"
            });
        } catch (error) {
            addToast({
                title: "Could not update default version",
                description: error instanceof Error ? error.message : String(error),
                color: "danger"
            });
        } finally {
            setSettingDefaultVariantId(undefined);
        }
    }

    async function shareContentToAllVariants(content: VariantContentDto) {
        if (!game || variants.length < 2) return;
        const draft = contentDrafts[content.id];
        if (!draft) return;
        if (draft.contentType === VariantContentType.BASE) return;
        const paths = parsePaths(draft.paths || draft.path);
        if (paths.length === 0) return;

        setSavingContentId(content.id);
        try {
            const updated = await GameEndpoint.attachVariantContent(game.id, {
                sourceGameId: undefined,
                sourceRootPath: paths[0],
                targetVariantId: undefined,
                targetVariantIds: variants.map((variant) => variant.id),
                variantName: selectedVariant?.name ?? variantName,
                version: selectedVariant?.version ?? version,
                entries: [{
                    path: paths[0],
                    paths,
                    contentName: draft.contentName,
                    contentType: draft.contentType,
                    required: draft.required,
                    defaultSelected: draft.required || draft.defaultSelected,
                    tags: draft.tags.split(",").map((tag) => tag.trim()).filter(Boolean)
                }]
            });
            // @ts-ignore Hilla returns the admin shape for admins.
            gameState.state[updated.id] = updated;
            onChanged?.();
            addToast({
                title: "Content shared",
                description: "This content is now available on all versions.",
                color: "success"
            });
        } catch (error) {
            addToast({
                title: "Could not share content",
                description: error instanceof Error ? error.message : String(error),
                color: "danger"
            });
        } finally {
            setSavingContentId(undefined);
        }
    }

    async function saveContent(variant: GameVariantDto, content: VariantContentDto, setAsVariantPath = false) {
        if (!game) return;
        const draft = contentDrafts[content.id];
        if (!draft) return;
        const paths = parsePaths(draft.paths || draft.path);
        if (paths.length === 0) return;

        setSavingContentId(content.id);
        try {
            const updated = await GameEndpoint.updateVariantContent(game.id, variant.id, content.id, {
                path: paths[0],
                paths,
                contentName: draft.contentName,
                contentType: draft.contentType,
                required: draft.required,
                defaultSelected: draft.required || draft.defaultSelected,
                tags: draft.tags.split(",").map((tag) => tag.trim()).filter(Boolean),
                setAsVariantPath
            });
            // @ts-ignore Hilla returns the admin shape for admins.
            gameState.state[updated.id] = updated;
            onChanged?.();
            addToast({title: "Content updated", color: "success"});
        } catch (error) {
            addToast({
                title: "Could not update content",
                description: error instanceof Error ? error.message : String(error),
                color: "danger"
            });
        } finally {
            setSavingContentId(undefined);
        }
    }

    async function deleteContent(variant: GameVariantDto, content: VariantContentDto) {
        if (!game) return;

        setDeletingContentId(content.id);
        try {
            const updated = await GameEndpoint.deleteVariantContent(game.id, variant.id, content.id);
            // @ts-ignore Hilla returns the admin shape for admins.
            gameState.state[updated.id] = updated;
            onChanged?.();
            addToast({title: "Content removed", color: "success"});
        } catch (error) {
            addToast({
                title: "Could not remove content",
                description: error instanceof Error ? error.message : String(error),
                color: "danger"
            });
        } finally {
            setDeletingContentId(undefined);
        }
    }

    async function removeSuggestionSource(suggestion: GameGroupingSuggestionDto) {
        if (!game) return;
        const sourceId = suggestion.targetGameId === game.id ? suggestion.sourceGameId : suggestion.targetGameId;

        setRemovingSourceId(sourceId);
        try {
            const updated = await GameEndpoint.removeDuplicateVariantSource(game.id, sourceId);
            // @ts-ignore Hilla returns the admin shape for admins.
            gameState.state[updated.id] = updated;
            delete gameState.state[sourceId];
            onChanged?.();
            addToast({
                title: "Duplicate source removed",
                description: "The source game row was removed and its path was ignored. Files were not changed.",
                color: "success"
            });
        } catch (error) {
            addToast({
                title: "Could not remove source",
                description: error instanceof Error ? error.message : String(error),
                color: "danger"
            });
        } finally {
            setRemovingSourceId(undefined);
        }
    }

    function contentTypeLabel(type: VariantContentType) {
        return type.replaceAll("_", " ");
    }

    return (
        <>
            <Modal isOpen={isOpen} onOpenChange={onOpenChange} size="5xl" scrollBehavior="inside">
                <ModalContent>
                    {(onClose) => (
                        <>
                            <ModalHeader className="flex flex-col gap-1">
                                <span>Manage variants</span>
                                {game && <span className="text-sm font-normal text-default-500">{game.title} · {game.metadata.path}</span>}
                            </ModalHeader>
                            <ModalBody>
                                {game && (
                                    <div className="grid grid-cols-1 xl:grid-cols-[1.1fr_0.9fr] gap-4">
                                        <div className="flex flex-col gap-4">
                                            <section className="flex flex-col gap-2">
                                                <div className="flex flex-row items-center justify-between">
                                                    <p className="font-semibold">Current variants and content</p>
                                                    <Button size="sm" variant="flat" startContent={<PlusIcon/>} onPress={pathPicker.onOpen}>
                                                        Add path
                                                    </Button>
                                                </div>
                                                {variants.length === 0 && (
                                                    <p className="text-sm text-default-500">No variants exist yet. Adding content will create a Normal variant.</p>
                                                )}
                                                {variants.map((variant) => (
                                                    <Card key={variant.id} shadow="none" className="bg-default-100">
                                                        <CardBody className="flex flex-col gap-2">
                                                            <div className="flex flex-wrap gap-2 items-center">
                                                                <Chip color={variant.id === selectedVariant?.id ? "primary" : "default"} variant="flat">
                                                                    {variant.name} {variant.version}
                                                                </Chip>
                                                                {defaultVariant?.id === variant.id &&
                                                                    <Chip size="sm" color="success" variant="flat">
                                                                        {variant.defaultLocked ? "pinned default" : "default"}
                                                                    </Chip>
                                                                }
                                                                {variant.latestForVariant && <Chip size="sm" variant="flat">latest</Chip>}
                                                                <Button size="sm" variant="light" onPress={() => {
                                                                    setSelectedVariantId(variant.id);
                                                                    setVariantName(variant.name);
                                                                    setVersion(variant.version);
                                                                }}>
                                                                    Use as target
                                                                </Button>
                                                                <Button
                                                                    size="sm"
                                                                    variant={variant.defaultLocked ? "flat" : "light"}
                                                                    color={variant.defaultLocked ? "success" : "default"}
                                                                    isDisabled={variant.defaultLocked}
                                                                    isLoading={settingDefaultVariantId === variant.id}
                                                                    onPress={() => setDefaultVariant(variant)}
                                                                >
                                                                    {variant.defaultLocked ? "Default pinned" : "Set default"}
                                                                </Button>
                                                            </div>
                                                            {variant.path && <p className="text-xs text-default-500 break-all">{variant.path}</p>}
                                                            <div className="flex flex-col gap-3">
                                                                {(variant.contents ?? []).map((content: VariantContentDto) => {
                                                                    const draft = contentDrafts[content.id] ?? createDraftFromContent(content);
                                                                    const isPrimaryPath = variant.path === content.path;

                                                                    return (
                                                                        <Card key={content.id} shadow="none" className="bg-default-50">
                                                                            <CardBody className="flex flex-col gap-2">
                                                                                <div className="flex flex-wrap items-center justify-between gap-2">
                                                                                    <div className="flex flex-wrap items-center gap-2">
                                                                                        <Chip size="sm" variant="flat">{contentTypeLabel(content.type)}</Chip>
                                                                                        {isPrimaryPath && <Chip size="sm" color="primary" variant="flat">primary path</Chip>}
                                                                                        {content.pathCount > 1 && <Chip size="sm" variant="flat">{content.pathCount} files</Chip>}
                                                                                        <span className="text-sm text-default-500">{humanFileSize(content.fileSize)}</span>
                                                                                    </div>
                                                                                    <div className="flex flex-row gap-2">
                                                                                        <Button
                                                                                            size="sm"
                                                                                            variant="flat"
                                                                                            startContent={<CheckIcon/>}
                                                                                            isLoading={savingContentId === content.id}
                                                                                            onPress={() => saveContent(variant, content)}
                                                                                        >
                                                                                            Save
                                                                                        </Button>
                                                                                        <Button
                                                                                            size="sm"
                                                                                            variant="flat"
                                                                                            isDisabled={draft.contentType === VariantContentType.BASE || variants.length < 2}
                                                                                            isLoading={savingContentId === content.id}
                                                                                            onPress={() => shareContentToAllVariants(content)}
                                                                                        >
                                                                                            Share to all
                                                                                        </Button>
                                                                                        <Button
                                                                                            size="sm"
                                                                                            variant="flat"
                                                                                            isDisabled={isPrimaryPath}
                                                                                            isLoading={savingContentId === content.id}
                                                                                            onPress={() => saveContent(variant, content, true)}
                                                                                        >
                                                                                            Use as primary
                                                                                        </Button>
                                                                                        <Button
                                                                                            isIconOnly
                                                                                            size="sm"
                                                                                            color="danger"
                                                                                            variant="flat"
                                                                                            isLoading={deletingContentId === content.id}
                                                                                            onPress={() => deleteContent(variant, content)}
                                                                                        >
                                                                                            <TrashIcon/>
                                                                                        </Button>
                                                                                    </div>
                                                                                </div>
                                                                                <Textarea
                                                                                    size="sm"
                                                                                    minRows={content.pathCount > 1 ? 3 : 1}
                                                                                    label="Paths (one per line)"
                                                                                    description="Use this to group multipart archives as one selectable download."
                                                                                    value={draft.paths}
                                                                                    onValueChange={(paths) => updateContentDraft(content.id, {
                                                                                        paths,
                                                                                        path: parsePaths(paths)[0] ?? draft.path
                                                                                    })}
                                                                                />
                                                                                <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                                                                                    <Input
                                                                                        size="sm"
                                                                                        label="Content name"
                                                                                        value={draft.contentName}
                                                                                        onValueChange={(contentName) => updateContentDraft(content.id, {contentName})}
                                                                                    />
                                                                                    <Select
                                                                                        size="sm"
                                                                                        label="Content type"
                                                                                        selectedKeys={[draft.contentType]}
                                                                                        onSelectionChange={(keys) => {
                                                                                            const key = Array.from(keys)[0]?.toString() as VariantContentType | undefined;
                                                                                            if (key) updateContentDraft(content.id, {contentType: key});
                                                                                        }}
                                                                                    >
                                                                                        {contentTypes.map((type) => (
                                                                                            <SelectItem key={type}>{contentTypeLabel(type)}</SelectItem>
                                                                                        ))}
                                                                                    </Select>
                                                                                </div>
                                                                                <Input
                                                                                    size="sm"
                                                                                    label="Tags"
                                                                                    placeholder="multiplayer, dlc"
                                                                                    value={draft.tags}
                                                                                    onValueChange={(tags) => updateContentDraft(content.id, {tags})}
                                                                                />
                                                                                <div className="flex flex-row gap-4">
                                                                                    <Checkbox
                                                                                        isSelected={draft.required}
                                                                                        isDisabled={draft.contentType === VariantContentType.BASE}
                                                                                        onValueChange={(required) => updateContentDraft(content.id, {required})}
                                                                                    >
                                                                                        Required
                                                                                    </Checkbox>
                                                                                    <Checkbox
                                                                                        isSelected={draft.required || draft.defaultSelected}
                                                                                        isDisabled={draft.required}
                                                                                        onValueChange={(defaultSelected) => updateContentDraft(content.id, {defaultSelected})}
                                                                                    >
                                                                                        Default selected
                                                                                    </Checkbox>
                                                                                </div>
                                                                            </CardBody>
                                                                        </Card>
                                                                    );
                                                                })}
                                                            </div>
                                                        </CardBody>
                                                    </Card>
                                                ))}
                                            </section>

                                            <section className="flex flex-col gap-2">
                                                <p className="font-semibold">Suggestions for this game</p>
                                                {relevantSuggestions.length === 0 && (
                                                    <p className="text-sm text-default-500">No likely duplicate paths found for this game.</p>
                                                )}
                                                {relevantSuggestions.map((suggestion) => {
                                                    const source = suggestion.targetGameId === game.id
                                                        ? gameLabel(suggestion.sourceGameId, suggestion.sourceTitle, suggestion.sourcePath)
                                                        : gameLabel(suggestion.targetGameId, suggestion.targetTitle, suggestion.targetPath);
                                                    return (
                                                        <Card key={`${suggestion.targetGameId}-${suggestion.sourceGameId}`} shadow="none" className="bg-default-100">
                                                            <CardBody className="flex flex-col md:flex-row md:items-center gap-3 justify-between">
                                                                <div className="flex flex-col gap-1">
                                                                    <div className="flex flex-row gap-2 items-center">
                                                                        <Chip size="sm" color={suggestion.autoGroup ? "success" : "warning"} variant="flat">
                                                                            {suggestion.confidence}%
                                                                        </Chip>
                                                                        <span>{source.title}</span>
                                                                    </div>
                                                                    <span className="text-xs text-default-500">{suggestion.reason}</span>
                                                                    <span className="text-xs text-default-500 break-all">{source.path}</span>
                                                                </div>
                                                                <div className="flex flex-row gap-2">
                                                                    <Button size="sm" color="primary" variant="flat" onPress={() => useSuggestion(suggestion)}>
                                                                        Use as source
                                                                    </Button>
                                                                    <Button
                                                                        size="sm"
                                                                        color="danger"
                                                                        variant="flat"
                                                                        isLoading={removingSourceId === (suggestion.targetGameId === game.id ? suggestion.sourceGameId : suggestion.targetGameId)}
                                                                        onPress={() => removeSuggestionSource(suggestion)}
                                                                    >
                                                                        Remove source row
                                                                    </Button>
                                                                </div>
                                                            </CardBody>
                                                        </Card>
                                                    );
                                                })}
                                            </section>
                                        </div>

                                        <div className="flex flex-col gap-3">
                                            <p className="font-semibold">Add or classify content</p>
                                            <Select
                                                size="sm"
                                                label="Attach to variant"
                                                selectedKeys={selectedVariant ? [selectedVariant.id.toString()] : []}
                                                isDisabled={variants.length === 0}
                                                onSelectionChange={(keys) => {
                                                    const key = Array.from(keys)[0]?.toString();
                                                    const variant = variants.find((candidate) => candidate.id.toString() === key);
                                                    if (!variant) return;
                                                    setSelectedVariantId(variant.id);
                                                    setVariantName(variant.name);
                                                    setVersion(variant.version);
                                                }}
                                            >
                                                {variants.map((variant) => (
                                                    <SelectItem key={variant.id.toString()}>
                                                        {variant.name} {variant.version}
                                                    </SelectItem>
                                                ))}
                                            </Select>
                                            <div className="grid grid-cols-2 gap-2">
                                                <Input size="sm" label="New BASE variant name" value={variantName} onValueChange={setVariantName}/>
                                                <Input size="sm" label="New BASE version" value={version} onValueChange={setVersion}/>
                                            </div>
                                            <Input
                                                size="sm"
                                                label="Source root path"
                                                value={sourceRootPath}
                                                onValueChange={(value) => {
                                                    setSourceGameId(undefined);
                                                    setSourceRootPath(value);
                                                    if (value && entries.length === 0) {
                                                        setEntries([createEntry(value, value.split(/[\\/]/).filter(Boolean).pop() ?? "Content", newEntriesType)]);
                                                    }
                                                }}
                                                endContent={
                                                    <Tooltip content="Pick path">
                                                        <Button isIconOnly size="sm" variant="light" onPress={pathPicker.onOpen}>
                                                            <FolderOpenIcon/>
                                                        </Button>
                                                    </Tooltip>
                                                }
                                            />
                                            <Select
                                                size="sm"
                                                label="New entries type"
                                                selectedKeys={[newEntriesType]}
                                                onSelectionChange={(keys) => {
                                                    const key = Array.from(keys)[0]?.toString() as VariantContentType | undefined;
                                                    if (key) applyTypeToNewEntries(key);
                                                }}
                                            >
                                                {contentTypes.map((type) => (
                                                    <SelectItem key={type}>{contentTypeLabel(type)}</SelectItem>
                                                ))}
                                            </Select>
                                            <div className="flex flex-row gap-4">
                                                <Checkbox
                                                    isSelected={splitChildren}
                                                    isDisabled={!sourceRootPath}
                                                    onValueChange={toggleSplitChildren}
                                                >
                                                    Split direct children
                                                </Checkbox>
                                                {splitChildren && entries.length > 1 && (
                                                    <Checkbox
                                                        isSelected={groupSelectedPaths}
                                                        onValueChange={setGroupSelectedPaths}
                                                    >
                                                        Group selected paths into one content
                                                    </Checkbox>
                                                )}
                                                {variants.length > 1 && newEntriesType !== VariantContentType.BASE && (
                                                    <Checkbox
                                                        isSelected={shareWithAllVariants}
                                                        onValueChange={setShareWithAllVariants}
                                                    >
                                                        Share with all versions
                                                    </Checkbox>
                                                )}
                                                {sourceGameId && (
                                                    <Button size="sm" variant="light" startContent={<XIcon/>} onPress={clearDraft}>
                                                        Clear suggestion
                                                    </Button>
                                                )}
                                            </div>
                                            <Divider/>
                                            {isLoadingChildren && <p className="text-sm text-default-500">Loading child paths…</p>}
                                            {groupSelectedPaths && (
                                                <Card shadow="none" className="bg-default-100">
                                                    <CardBody className="flex flex-col gap-2">
                                                        <p className="text-sm font-semibold">
                                                            Grouped content ({entries.filter((entry) => entry.selected).length} paths)
                                                        </p>
                                                        <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                                                            <Input
                                                                size="sm"
                                                                label="Content name"
                                                                value={groupContentName}
                                                                onValueChange={setGroupContentName}
                                                            />
                                                            <Select
                                                                size="sm"
                                                                label="Content type"
                                                                selectedKeys={[newEntriesType]}
                                                                onSelectionChange={(keys) => {
                                                                    const key = Array.from(keys)[0]?.toString() as VariantContentType | undefined;
                                                                    if (key) applyTypeToNewEntries(key);
                                                                }}
                                                            >
                                                                {contentTypes.map((type) => (
                                                                    <SelectItem key={type}>{contentTypeLabel(type)}</SelectItem>
                                                                ))}
                                                            </Select>
                                                        </div>
                                                        <Input
                                                            size="sm"
                                                            label="Tags"
                                                            placeholder="multiplayer, dlc"
                                                            value={groupTags}
                                                            onValueChange={setGroupTags}
                                                        />
                                                        <div className="flex flex-row gap-4">
                                                            <Checkbox
                                                                isSelected={groupRequired || newEntriesType === VariantContentType.BASE}
                                                                isDisabled={newEntriesType === VariantContentType.BASE}
                                                                onValueChange={(required) => {
                                                                    setGroupRequired(required);
                                                                    if (required) setGroupDefaultSelected(true);
                                                                }}
                                                            >
                                                                Required
                                                            </Checkbox>
                                                            <Checkbox
                                                                isSelected={groupRequired || groupDefaultSelected || newEntriesType === VariantContentType.BASE}
                                                                isDisabled={groupRequired || newEntriesType === VariantContentType.BASE}
                                                                onValueChange={setGroupDefaultSelected}
                                                            >
                                                                Default selected
                                                            </Checkbox>
                                                        </div>
                                                    </CardBody>
                                                </Card>
                                            )}
                                            <div className="flex flex-col gap-3">
                                                {entries.map((entry, index) => (
                                                    <Card key={`${entry.path}-${index}`} shadow="none" className="bg-default-100">
                                                        <CardBody className="flex flex-col gap-2">
                                                            <Checkbox isSelected={entry.selected} onValueChange={(selected) => updateEntry(index, {selected})}>
                                                                Include this path
                                                            </Checkbox>
                                                            <Input size="sm" label="Path" value={entry.path}
                                                                   onValueChange={(path) => updateEntry(index, {path})}/>
                                                            {!groupSelectedPaths && (
                                                                <>
                                                                    <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                                                                        <Input size="sm" label="Content name" value={entry.contentName}
                                                                               onValueChange={(contentName) => updateEntry(index, {contentName})}/>
                                                                        <Select
                                                                            size="sm"
                                                                            label="Content type"
                                                                            selectedKeys={[entry.contentType]}
                                                                            onSelectionChange={(keys) => {
                                                                                const key = Array.from(keys)[0]?.toString() as VariantContentType | undefined;
                                                                                if (key) updateEntry(index, {contentType: key});
                                                                            }}
                                                                        >
                                                                            {contentTypes.map((type) => (
                                                                                <SelectItem key={type}>{contentTypeLabel(type)}</SelectItem>
                                                                            ))}
                                                                        </Select>
                                                                    </div>
                                                                    <Input size="sm" label="Tags" placeholder="multiplayer, dlc"
                                                                           value={entry.tags}
                                                                           onValueChange={(tags) => updateEntry(index, {tags})}/>
                                                                    <div className="flex flex-row gap-4">
                                                                        <Checkbox
                                                                            isSelected={entry.required}
                                                                            isDisabled={entry.contentType === VariantContentType.BASE}
                                                                            onValueChange={(required) => updateEntry(index, {required})}
                                                                        >
                                                                            Required
                                                                        </Checkbox>
                                                                        <Checkbox
                                                                            isSelected={entry.required || entry.defaultSelected}
                                                                            isDisabled={entry.required}
                                                                            onValueChange={(defaultSelected) => updateEntry(index, {defaultSelected})}
                                                                        >
                                                                            Default selected
                                                                        </Checkbox>
                                                                    </div>
                                                                </>
                                                            )}
                                                        </CardBody>
                                                    </Card>
                                                ))}
                                            </div>
                                        </div>
                                    </div>
                                )}
                            </ModalBody>
                            <ModalFooter>
                                <Button variant="light" onPress={onClose}>
                                    Close
                                </Button>
                                <Button
                                    color="primary"
                                    isLoading={isSaving}
                                    isDisabled={!sourceRootPath || entries.filter((entry) => entry.selected).length === 0}
                                    onPress={attachContent}
                                >
                                    Save content
                                </Button>
                            </ModalFooter>
                        </>
                    )}
                </ModalContent>
            </Modal>
            <ContentPathPickerModal
                isOpen={pathPicker.isOpen}
                onOpenChange={pathPicker.onOpenChange}
                returnSelectedPath={onPathSelected}
            />
        </>
    );
}
