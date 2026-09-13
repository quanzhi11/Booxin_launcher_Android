/** Marketplace target platforms for a plugin listing. */
export const PLUGIN_PLATFORMS = [
  { id: 'android', label: 'Android（手机）', aliases: ['phone', 'mobile'] },
  { id: 'desktop', label: '桌面（Windows）', aliases: ['windows', 'pc', 'win'] },
];

const ALIAS_TO_ID = new Map();
for (const p of PLUGIN_PLATFORMS) {
  ALIAS_TO_ID.set(p.id, p.id);
  for (const a of p.aliases) ALIAS_TO_ID.set(a, p.id);
}

/** Legacy / missing → both platforms (backward compatible). */
export const DEFAULT_PLATFORMS = PLUGIN_PLATFORMS.map((p) => p.id);

export function listPluginPlatforms() {
  return PLUGIN_PLATFORMS.map(({ id, label }) => ({ id, label }));
}

/**
 * Normalize author-selected platforms.
 * Accepts: string[] | comma/space-separated string | JSON array string.
 * Empty input → DEFAULT_PLATFORMS when allowEmptyAsDefault=true.
 */
export function normalizePlatforms(raw, { allowEmptyAsDefault = true } = {}) {
  let list = [];
  if (Array.isArray(raw)) {
    list = raw;
  } else if (typeof raw === 'string') {
    const s = raw.trim();
    if (s.startsWith('[')) {
      try {
        const parsed = JSON.parse(s);
        if (Array.isArray(parsed)) list = parsed;
        else list = s.split(/[,|\s]+/);
      } catch {
        list = s.split(/[,|\s]+/);
      }
    } else {
      list = s.split(/[,|\s]+/);
    }
  }

  const out = [];
  const seen = new Set();
  for (const item of list) {
    const key = String(item || '')
      .trim()
      .toLowerCase();
    if (!key) continue;
    const id = ALIAS_TO_ID.get(key);
    if (!id || seen.has(id)) continue;
    seen.add(id);
    out.push(id);
  }

  if (out.length === 0 && allowEmptyAsDefault) return [...DEFAULT_PLATFORMS];
  return out;
}

export function pluginSupportsPlatform(pluginOrPlatforms, platformId) {
  const want = ALIAS_TO_ID.get(String(platformId || '').trim().toLowerCase());
  if (!want) return true;
  const platforms = Array.isArray(pluginOrPlatforms)
    ? pluginOrPlatforms
    : normalizePlatforms(pluginOrPlatforms?.platforms, { allowEmptyAsDefault: true });
  return platforms.includes(want);
}
