/**
 * Studio model catalog — unlock rules align with mobile AiModelProfiles.
 * Tiers: None=0, Pro/Shelter=1, ProMax/Hearth=2, Ultra/Atelier=3
 *
 * Studio channel historically works with provider "DeepSeek" + model "deepseek-chat".
 * Prefer that as free default; map others to upstream headers used by the launcher.
 */

export const STUDIO_MODELS = [
  {
    id: 'deepseek',
    name: 'DeepSeek',
    modelId: 'deepseek-chat',
    providerHeader: 'DeepSeek',
    maxTokens: 4096,
    minTier: 0,
    lockHint: '',
  },
  {
    id: 'glm',
    name: 'GLM',
    modelId: 'glm-4-flash',
    providerHeader: 'Zhipu',
    maxTokens: 1200,
    minTier: 0,
    lockHint: '',
  },
  {
    id: 'deepseek-v4',
    name: 'DeepSeek V4 Flash',
    modelId: 'deepseek-v4-flash',
    providerHeader: 'DeepSeekV4Pro',
    maxTokens: 1200,
    minTier: 0,
    lockHint: '',
  },
  {
    id: 'kimi',
    name: 'KIMI',
    modelId: 'moonshot-v1-8k',
    providerHeader: 'Kimi',
    maxTokens: 600,
    minTier: 1,
    lockHint: '需 Shelter 及以上',
  },
  {
    id: 'grok',
    name: 'Grok 4.3',
    modelId: 'grok-4.3',
    providerHeader: 'Grok43',
    maxTokens: 2048,
    minTier: 2,
    lockHint: '需 Hearth 及以上',
  },
  {
    id: 'gpt',
    name: 'ChatGPT 5.5',
    modelId: 'gpt-5.5',
    providerHeader: 'ChatGpt55',
    maxTokens: 2048,
    minTier: 3,
    lockHint: '需 Atelier',
  },
];

export function tierRank(memberTier) {
  switch (String(memberTier || '')) {
    case 'Ultra':
      return 3;
    case 'ProMax':
      return 2;
    case 'Pro':
      return 1;
    default:
      return 0;
  }
}

export function listModelsForTier(memberTier) {
  const rank = tierRank(memberTier);
  return STUDIO_MODELS.map((m) => ({
    id: m.id,
    name: m.name,
    modelId: m.modelId,
    locked: rank < m.minTier,
    lockHint: rank < m.minTier ? m.lockHint : '',
  }));
}

export function resolveModel(selectedId, memberTier) {
  const rank = tierRank(memberTier);
  let id = String(selectedId || 'deepseek').trim();
  // legacy / mobile "auto" → free DeepSeek (stable on studio channel)
  if (!id || id === 'auto') id = 'deepseek';
  const wanted = STUDIO_MODELS.find((m) => m.id === id);
  if (wanted && rank >= wanted.minTier) return wanted;
  return STUDIO_MODELS.find((m) => rank >= m.minTier) || STUDIO_MODELS[0];
}
