#!/usr/bin/env bash
# 在服务器上执行（需 root）。默认目录 /www/wwwroot/booxin/skin-api
set -euo pipefail

APP_DIR="${APP_DIR:-/www/wwwroot/booxin/booxin-skin-marketplace}"
SERVICE_NAME="booxin-skin-marketplace"
UNIT_SRC="$(cd "$(dirname "$0")" && pwd)/booxin-skin-marketplace.service"

if [[ ! -f "$APP_DIR/package.json" ]]; then
  echo "ERROR: $APP_DIR/package.json 不存在。请先解压部署包到 $APP_DIR"
  exit 1
fi

mkdir -p "$APP_DIR/data/uploads"
cd "$APP_DIR"

if [[ ! -f .env ]]; then
  if [[ -f .env.example ]]; then
    cp .env.example .env
    echo "已生成 .env（请编辑 ADMIN_PASSWORD / SMTP / BOOXIN_AUTH_*）"
  else
    echo "WARN: 没有 .env.example，请手动创建 .env"
  fi
fi

npm install --omit=dev

if [[ -f "$UNIT_SRC" ]]; then
  # 按实际 APP_DIR 改写 WorkingDirectory / EnvironmentFile
  sed -e "s|/www/wwwroot/booxin/booxin-skin-marketplace|$APP_DIR|g" "$UNIT_SRC" \
    > "/etc/systemd/system/${SERVICE_NAME}.service"
else
  echo "ERROR: 找不到 $UNIT_SRC"
  exit 1
fi

systemctl daemon-reload
systemctl enable "$SERVICE_NAME"
systemctl restart "$SERVICE_NAME"
systemctl --no-pager --full status "$SERVICE_NAME" || true

echo
echo "OK. 健康检查: curl -s http://127.0.0.1:5021/api/health"
echo "管理后台: https://boonix.art/skin-api/admin/"
echo "  systemctl status $SERVICE_NAME"
echo "  systemctl restart $SERVICE_NAME"
