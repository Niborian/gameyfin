/** Review hints only. These never persist a classification or change files. */
export function suggestContentType(name: string): 'BASE' | 'PATCH' | 'DLC' | 'DEDICATED_SERVER' | 'MOD' | 'EXTRA' {
    const words = name.replace(/[._-]+/g, ' ');
    if (/\b(patch|hotfix|multiplayer|online\s*fix)\b/i.test(words)) return 'PATCH';
    if (/\bdlc\b/i.test(words)) return 'DLC';
    if (/\b(dedicated\s*)?server\b/i.test(words)) return 'DEDICATED_SERVER';
    if (/\bmods?\b/i.test(words)) return 'MOD';
    return /\.(rar|zip|7z|iso|tar|gz|bz2|xz|bin|exe)$/i.test(name) ? 'BASE' : 'EXTRA';
}
