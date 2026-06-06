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
import {FolderOpenIcon, PlusIcon, XIcon} from "@phosphor-icons/react";

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
    const defaultVariant = variants.find((variant) => variant.default) ?? variants[0];

    const [selectedVariantId, setSelectedVariantId] = useState<number>();
    const [sourceGameId, setSourceGameId] = useState<number>();
    const [sourceRootPath, setSourceRootPath] = useState("");
    const [variantName, setVariantName] = useState("Normal");
    const [version, setVersion] = useState("0");
    const [splitChildren, setSplitChildren] = useState(false);
    const [entries, setEntries] = useState<DraftEntry[]>([]);
    const [isSaving, setIsSaving] = useState(false);
    const [isLoadingChildren, setIsLoadingChildren] = useState(false);

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
        clearDraft();
    }, [game?.id, defaultVariant?.id]);

    function clearDraft() {
        setSourceGameId(undefined);
        setSourceRootPath("");
        setSplitChildren(false);
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
            contentName: name,
            contentType: type,
            required: type === VariantContentType.BASE,
            defaultSelected: type === VariantContentType.BASE,
            tags: ""
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
        setEntries([createEntry(path, path.split(/[\\/]/).filter(Boolean).pop() ?? "DLC", VariantContentType.DLC)]);
    }

    async function toggleSplitChildren(selected: boolean) {
        setSplitChildren(selected);
        if (!selected || !sourceRootPath) {
            if (sourceRootPath && entries.length === 0) {
                setEntries([createEntry(sourceRootPath, sourceRootPath.split(/[\\/]/).filter(Boolean).pop() ?? "DLC", VariantContentType.DLC)]);
            }
            return;
        }

        setIsLoadingChildren(true);
        try {
            const children = await FilesystemEndpoint.listContents(sourceRootPath) as FileDto[];
            setEntries(children.map((child) =>
                createEntry(child.path ?? joinPath(sourceRootPath, child.name), child.name, VariantContentType.DLC)
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

    async function attachContent() {
        if (!game) return;
        const selectedEntries = entries.filter((entry) => entry.selected);
        if (!sourceRootPath || selectedEntries.length === 0) return;

        const isSingleBaseVariant = selectedEntries.length === 1 && selectedEntries[0].contentType === VariantContentType.BASE;
        setIsSaving(true);
        try {
            const updated = await GameEndpoint.attachVariantContent(game.id, {
                sourceGameId,
                sourceRootPath,
                targetVariantId: isSingleBaseVariant ? undefined : selectedVariant?.id,
                variantName: isSingleBaseVariant ? variantName : selectedVariant?.name ?? variantName,
                version: isSingleBaseVariant ? version : selectedVariant?.version ?? version,
                entries: selectedEntries.map((entry) => ({
                    path: entry.path,
                    contentName: entry.contentName,
                    contentType: entry.contentType,
                    required: entry.required,
                    defaultSelected: entry.required || entry.defaultSelected,
                    tags: entry.tags.split(",").map((tag) => tag.trim()).filter(Boolean)
                }))
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
                                                                {variant.default && <Chip size="sm" color="success" variant="flat">default</Chip>}
                                                                {variant.latestForVariant && <Chip size="sm" variant="flat">latest</Chip>}
                                                                <Button size="sm" variant="light" onPress={() => {
                                                                    setSelectedVariantId(variant.id);
                                                                    setVariantName(variant.name);
                                                                    setVersion(variant.version);
                                                                }}>
                                                                    Use as target
                                                                </Button>
                                                            </div>
                                                            {variant.path && <p className="text-xs text-default-500 break-all">{variant.path}</p>}
                                                            <div className="flex flex-col gap-1">
                                                                {(variant.contents ?? []).map((content: VariantContentDto) => (
                                                                    <div key={content.id} className="grid grid-cols-[8rem_1fr_auto] gap-2 text-sm">
                                                                        <span className="text-default-500">{contentTypeLabel(content.type)}</span>
                                                                        <span>
                                                                            {content.name}
                                                                            {content.required && <span className="text-default-500"> · required</span>}
                                                                            {content.defaultSelected && !content.required && <span className="text-default-500"> · default selected</span>}
                                                                        </span>
                                                                        <span className="text-default-500">{humanFileSize(content.fileSize)}</span>
                                                                        {content.path && <span className="col-span-3 text-xs text-default-500 break-all">{content.path}</span>}
                                                                    </div>
                                                                ))}
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
                                                                <Button size="sm" color="primary" variant="flat" onPress={() => useSuggestion(suggestion)}>
                                                                    Use as source
                                                                </Button>
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
                                                        setEntries([createEntry(value, value.split(/[\\/]/).filter(Boolean).pop() ?? "Content", VariantContentType.DLC)]);
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
                                            <div className="flex flex-row gap-4">
                                                <Checkbox
                                                    isSelected={splitChildren}
                                                    isDisabled={!sourceRootPath}
                                                    onValueChange={toggleSplitChildren}
                                                >
                                                    Split direct children
                                                </Checkbox>
                                                {sourceGameId && (
                                                    <Button size="sm" variant="light" startContent={<XIcon/>} onPress={clearDraft}>
                                                        Clear suggestion
                                                    </Button>
                                                )}
                                            </div>
                                            <Divider/>
                                            {isLoadingChildren && <p className="text-sm text-default-500">Loading child paths…</p>}
                                            <div className="flex flex-col gap-3">
                                                {entries.map((entry, index) => (
                                                    <Card key={`${entry.path}-${index}`} shadow="none" className="bg-default-100">
                                                        <CardBody className="flex flex-col gap-2">
                                                            <Checkbox isSelected={entry.selected} onValueChange={(selected) => updateEntry(index, {selected})}>
                                                                Include this path
                                                            </Checkbox>
                                                            <Input size="sm" label="Path" value={entry.path}
                                                                   onValueChange={(path) => updateEntry(index, {path})}/>
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
