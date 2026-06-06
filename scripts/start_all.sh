#!/bin/bash
# 灵绘全栈启动脚本 — 同时启动 WebSocket + HTTP 桥接服务器
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

export PYTHONPATH="$PROJECT_DIR:$PYTHONPATH"

WS_PORT="${WS_PORT:-9527}"
BRIDGE_PORT="${BRIDGE_PORT:-8801}"

echo "╔═══════════════════════════════════╗"
echo "║  🔥 灵绘 AI 全栈启动              ║"
echo "║  WS: 0.0.0.0:$WS_PORT                ║"
echo "║  HTTP: 0.0.0.0:$BRIDGE_PORT               ║"
echo "╚═══════════════════════════════════╝"

cleanup() {
    echo ""
    echo "正在停止所有服务..."
    kill $WS_PID $BRIDGE_PID 2>/dev/null
    wait $WS_PID $BRIDGE_PID 2>/dev/null
    echo "服务已停止"
}
trap cleanup EXIT INT TERM

# 启动 WebSocket 服务器（后台）
python3 -m ai_core.ws_server --host 0.0.0.0 --port "$WS_PORT" &
WS_PID=$!

# 启动 HTTP 桥接服务器（后台）
python3 -m ai_core.bridge_server --host 0.0.0.0 --port "$BRIDGE_PORT" &
BRIDGE_PID=$!

echo "✅ 服务已启动 (WS PID=$WS_PID, HTTP PID=$BRIDGE_PID)"
echo "📱 Android 端配置:"
echo "   WebSocket: ws://<手机IP>:$WS_PORT/ws"
echo "   HTTP: http://<手机IP>:$BRIDGE_PORT"
echo ""
echo "按 Ctrl+C 停止"

wait
