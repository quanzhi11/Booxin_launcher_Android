#!/usr/bin/env bash
# 在服务器上执行（需 root）：
#   bash deploy/install-systemd.sh
set -euo pipefail

APP_DIR="${APP_DIR:-/www/wwwroot/booxin/abc}"
UNIT_SRC="${APP_DIR}/deploy/booxin-plugin-marketplace.service"
UNIT_DST="/etc/systemd/system/booxin-plugin-marketplace.service"
NODE_BIN="$(command -v node || true)"

if [[ -z "$NODE_BIN" ]]; then
  echo "找不到 node，请先安装 Node.js 18+"
  exit 1
fi
if [[ ! -f "$UNIT_SRC" ]]; then
  echo "缺少单元文件: $UNIT_SRC"
  exit 1
fi
if [[ ! -f "$APP_DIR/.env" ]]; then
  echo "警告: $APP_DIR/.env 不存在，请先配置"
fi

# 停掉旧的 nohup 进程，避免抢端口
pkill -f "node src/server.js" 2>/dev/null || true

# 把单元里的 node 路径写成实际路径
sed "s|^ExecStart=.*|ExecStart=${NODE_BIN} src/server.js|" "$UNIT_SRC" > "$UNIT_DST"

systemctl daemon-reload
systemctl enable booxin-plugin-marketplace
systemctl restart booxin-plugin-marketplace
systemctl --no-pager --full status booxin-plugin-marketplace || true

echo
echo "检查健康接口："
curl -sS "https://boonix.art/plugin-api/api/health" || true
echo
echo "常用命令："
echo "  systemctl status booxin-plugin-marketplace"
echo "  systemctl restart booxin-plugin-marketplace"
echo "  journalctl -u booxin-plugin-marketplace -n 80 --no-pager"
