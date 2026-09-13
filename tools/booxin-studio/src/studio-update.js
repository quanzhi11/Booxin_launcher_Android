/**
 * Booxin Studio version check — fetch remote manifest, never auto-download.
 * Remote: https://www.boonix.art/server_update/studio.json
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const UPDATE_URL = 'https://www.boonix.art/server_update/studio.json';
const FALLBACK_DOWNLOAD = 'https://www.boonix.art/server_update/studio.html';
const FALLBACK_PAGE = 'https://www.boonix.art/server_update/studio.html';

function readLocalVersion() {
  if (typeof globalThis.BOOXIN_STUDIO_VERSION === 'string' && globalThis.BOOXIN_STUDIO_VERSION) {
    return globalThis.BOOXIN_STUDIO_VERSION;
  }
  try {
    if (typeof import.meta !== 'undefined' && import.meta && import.meta.url) {
      const here = path.dirname(fileURLToPath(import.meta.url));
      const pkgPath = path.join(here, '..', 'package.json');
      const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
      if (pkg?.version) return String(pkg.version);
    }
  } catch {
    /* ignore */
  }
  return '2.0.0';
}

export const APP_VERSION = readLocalVersion();

/** Compare dotted versions: 2.1.0 > 2.0.9 */
export function compareVersionNames(a, b) {
  const pa = String(a || '')
    .trim()
    .replace(/^[vV]/, '')
    .split(/[.\-_]/)
    .map((p) => parseInt(p.replace(/\D/g, ''), 10) || 0);
  const pb = String(b || '')
    .trim()
    .replace(/^[vV]/, '')
    .split(/[.\-_]/)
    .map((p) => parseInt(p.replace(/\D/g, ''), 10) || 0);
  const n = Math.max(pa.length, pb.length);
  for (let i = 0; i < n; i++) {
    const x = pa[i] || 0;
    const y = pb[i] || 0;
    if (x !== y) return x < y ? -1 : 1;
  }
  return 0;
}

function pickString(obj, ...keys) {
  for (const key of keys) {
    const v = obj?.[key];
    if (v == null) continue;
    const s = String(v).trim();
    if (s && s !== 'null') return s;
  }
  return '';
}

function pickInt(obj, ...keys) {
  for (const key of keys) {
    const v = obj?.[key];
    if (typeof v === 'number' && Number.isFinite(v)) return Math.trunc(v);
    if (typeof v === 'string') {
      const n = parseInt(v.replace(/[^\d-]/g, ''), 10);
      if (Number.isFinite(n)) return n;
    }
  }
  return 0;
}

/**
 * @returns {Promise<{
 *   currentVersion: string,
 *   latestVersion: string,
 *   hasUpdate: boolean,
 *   downloadUrl: string,
 *   pageUrl: string,
 *   notes: string,
 *   forceUpdate: boolean,
 * }>}
 */
export async function checkStudioUpdate() {
  const currentVersion = APP_VERSION;
  const url = `${UPDATE_URL}?_=${Date.now()}`;
  const res = await fetch(url, {
    method: 'GET',
    headers: {
      'User-Agent': `BooxinStudio/${currentVersion}`,
      'Cache-Control': 'no-cache',
      Pragma: 'no-cache',
    },
  });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`版本检测失败 HTTP ${res.status}`);
  }
  let json = {};
  try {
    json = JSON.parse(text.replace(/^\uFEFF/, '').trim() || '{}');
  } catch {
    throw new Error('版本清单无效');
  }

  const latestVersion = pickString(json, 'latestVersion', 'versionName', 'version') || currentVersion;
  const downloadUrl =
    pickString(json, 'downloadUrl', 'fileUrl', 'zipUrl', 'url') || FALLBACK_DOWNLOAD;
  const pageUrl = pickString(json, 'pageUrl') || FALLBACK_PAGE;
  const notes = pickString(json, 'releaseNotes', 'changelog', 'notes');
  const forceUpdate = !!(json.forceUpdate || json.force);
  const remoteCode = pickInt(json, 'latestVersionCode', 'versionCode', 'code');

  const hasUpdate =
    compareVersionNames(latestVersion, currentVersion) > 0 ||
    (remoteCode > 0 && remoteCode > versionCodeFromName(currentVersion));

  return {
    currentVersion,
    latestVersion,
    hasUpdate,
    downloadUrl,
    pageUrl,
    notes,
    forceUpdate,
  };
}

function versionCodeFromName(name) {
  const parts = String(name || '')
    .trim()
    .replace(/^[vV]/, '')
    .split(/[.\-_]/)
    .map((p) => parseInt(p.replace(/\D/g, ''), 10) || 0);
  const major = parts[0] || 0;
  const minor = parts[1] || 0;
  const patch = parts[2] || 0;
  return major * 10000 + minor * 100 + patch;
}
