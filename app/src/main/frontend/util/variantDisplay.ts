export type VariantDisplay = {variantId: number; libraryVersion?: string; edition?: string};
type VariantLabel = {name: string; version: string; latestForVariant: boolean};

/** Display metadata must never change canonical IDs, comparison or download selection. */
export function variantDisplayLabel(variant: VariantLabel, details?: VariantDisplay): string {
    const saved = details?.libraryVersion?.trim();
    const legacy = variant.version.trim();
    const version = saved || (legacy && legacy !== "0" ? legacy : "");
    const meaningfulName = variant.name.trim().toLowerCase() !== "normal" ? variant.name.trim() : "";
    const label = version ? (/^\d/.test(version) ? `v${version}` : version) : "";
    return [meaningfulName, label, variant.latestForVariant ? "Latest" : ""].filter(Boolean).join(" · ") || "Version not specified";
}
