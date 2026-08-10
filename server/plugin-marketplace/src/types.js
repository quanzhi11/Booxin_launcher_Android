/** Marketplace plugin categories (download catalog + applications). */
export const PLUGIN_TYPES = [
  { id: 'renderer', label: '渲染器', desc: 'OpenGL / Vulkan / ANGLE 等 GL 桥接 .so' },
  { id: 'driver', label: '驱动', desc: 'GPU/驱动相关原生库' },
  { id: 'ui', label: 'UI 插件', desc: '启动器界面扩展、主题皮肤、控件皮肤包' },
  { id: 'control', label: '操控 / 虚拟按键', desc: '触控布局、手柄映射、虚拟摇杆配置' },
  { id: 'input', label: '输入法 / 键鼠', desc: '输入相关辅助插件' },
  { id: 'overlay', label: '悬浮窗 / HUD', desc: '游戏内叠加层、准星、信息条' },
  { id: 'utility', label: '工具', desc: '日志、诊断、辅助脚本等工具类' },
  { id: 'pack', label: '整合包附属', desc: '与整合包配套的资源或补丁包' },
  { id: 'other', label: '其他', desc: '未分类插件' },
];

const ALLOWED = new Set(PLUGIN_TYPES.map((t) => t.id));

export function normalizePluginType(raw, fallback = 'other') {
  const id = String(raw || '')
    .trim()
    .toLowerCase();
  if (ALLOWED.has(id)) return id;
  return fallback;
}

export function listPluginTypes() {
  return PLUGIN_TYPES;
}
