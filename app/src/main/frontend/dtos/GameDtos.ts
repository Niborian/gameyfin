import GameDto from "Frontend/generated/org/gameyfin/app/games/dto/GameDto";

export interface GameAdminDto extends GameDto {
    metadata: GameMetadataAdminDto;
}

export interface GameVariantDto {
    id: number;
    name: string;
    version: string;
    path?: string | null;
    fileSize: number;
    tags: string[];
    steamAppId?: string | null;
    launchArgs?: string | null;
    patchInfo?: string | null;
    isDefault: boolean;
    isLatestForVariant: boolean;
    linkStatus: VariantLinkStatus;
    linkFallbackReason?: string | null;
    contents: VariantContentDto[];
}

export interface VariantContentDto {
    id: number;
    type: VariantContentType;
    name: string;
    path?: string | null;
    fileSize: number;
    required: boolean;
    defaultSelected: boolean;
    tags: string[];
}

export enum VariantContentType {
    BASE = 'BASE',
    DLC = 'DLC',
    DEDICATED_SERVER = 'DEDICATED_SERVER',
    PATCH = 'PATCH',
    MOD = 'MOD',
    EXTRA = 'EXTRA'
}

export enum VariantLinkStatus {
    DIRECT = 'DIRECT',
    HARDLINKED = 'HARDLINKED',
    COPIED_FALLBACK = 'COPIED_FALLBACK'
}

export interface GameMetadataAdminDto {
    path?: string | null;
    fileSize: number;
    fields?: { [key: string]: GameFieldMetadataDto } | null;
    originalIds?: { [key: string]: string } | null;
    downloadCount: number;
    matchConfirmed: boolean;
}

export interface GameFieldMetadataDto {
    type: GameFieldMetadataType;
    source: string;
    updatedAt: string;
}

export enum GameFieldMetadataType {
    PLUGIN = 'PLUGIN',
    USER = 'USER',
    UNKNOWN = 'UNKNOWN'
}
