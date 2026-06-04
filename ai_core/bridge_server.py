"""灵绘 HTTP 桥接服务器 — Python ↔ Android 双向通信中枢

端点：
  GET  /health          — 健康检查
  GET  /messages        — Android 拉取待消费消息（轮询）
  POST /push            — Python 管线推送消息到 Android
  POST /action_result   — Android 回传操作结果给 Python
  POST /mood_update     — Android 回传情绪更新
"""

import json
import logging
import threading
from collections import deque
from http.server import HTTPServer, BaseHTTPRequestHandler

logger = logging.getLogger("linghui.bridge")

# ── 全局消息队列 ──────────────────────────────────────
# Python 管线 push → 队列 → Android GET /messages 拉取
_message_queue: deque = deque()
_queue_lock = threading.Lock()
MAX_QUEUE_SIZE = 100


def push_message(msg_type: str, **kwargs) -> None:
    """向消息队列推送一条消息，供 Android 端轮询消费

    参数:
        msg_type: 消息类型 (mood/talking/reply/action)
        **kwargs: 消息字段，取决于类型
    """
    msg = {"type": msg_type}
    msg.update(kwargs)
    with _queue_lock:
        _message_queue.append(msg)
        while len(_message_queue) > MAX_QUEUE_SIZE:
            _message_queue.popleft()
    logger.debug("推送消息: type=%s", msg_type)


def drain_messages() -> list:
    """取出并清空队列中的所有消息"""
    with _queue_lock:
        msgs = list(_message_queue)
        _message_queue.clear()
    return msgs


# ── HTTP 处理器 ───────────────────────────────────────

class BridgeHandler(BaseHTTPRequestHandler):
    """处理来自 Android 端和本地管线的 HTTP 请求"""

    # 类级回调，由使用者注入
    on_action_result = None  # callable(action, success, message)

    def do_GET(self):
        if self.path == "/health":
            self._respond(200, {"status": "ok", "service": "linghui-bridge"})
        elif self.path == "/messages":
            msgs = drain_messages()
            self._respond(200, {"messages": msgs, "count": len(msgs)})
        else:
            self._respond(404, {"error": "未知端点"})

    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length) if content_length > 0 else b"{}"
        try:
            data = json.loads(body.decode("utf-8"))
        except json.JSONDecodeError:
            self._respond(400, {"error": "JSON 解析失败"})
            return

        if self.path == "/push":
            self._handle_push(data)
        elif self.path == "/action_result":
            self._handle_action_result(data)
        elif self.path == "/mood_update":
            self._handle_mood_update(data)
        else:
            self._respond(404, {"error": "未知端点"})

    def _handle_push(self, data: dict):
        """本地管线推送消息到队列"""
        msg_type = data.get("type", "")
        push_message(msg_type, **{k: v for k, v in data.items() if k != "type"})
        self._respond(200, {"queued": True, "type": msg_type})

    def _handle_action_result(self, data: dict):
        action = data.get("action", "")
        success = data.get("success", False)
        message = data.get("message", "")
        logger.info("收到操作结果: action=%s success=%s message=%s",
                     action, success, message)
        if BridgeHandler.on_action_result:
            try:
                BridgeHandler.on_action_result(action, success, message)
            except Exception:
                logger.exception("on_action_result 回调异常")
        self._respond(200, {"received": True, "action": action})

    def _handle_mood_update(self, data: dict):
        mood = data.get("mood", "neutral")
        logger.info("收到情绪更新: mood=%s", mood)
        self._respond(200, {"received": True, "mood": mood})

    def _respond(self, code: int, data: dict):
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def log_message(self, fmt, *args):
        logger.debug("HTTP %s", fmt % args)


# ── 服务器启动 ────────────────────────────────────────

def start_bridge(host: str = "0.0.0.0", port: int = 8801) -> HTTPServer:
    """启动桥接 HTTP 服务器

    参数:
        host: 监听地址，默认 0.0.0.0
        port: 监听端口，默认 8801（独立于网关 8800）

    返回:
        HTTPServer 实例，调用 .shutdown() 停止
    """
    server = HTTPServer((host, port), BridgeHandler)
    logger.info("灵绘桥接服务器启动: %s:%d", host, port)
    return server


# ── CLI 入口 ──────────────────────────────────────────

def main():
    import argparse
    import signal
    import sys

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [bridge] %(message)s",
        datefmt="%H:%M:%S",
    )

    parser = argparse.ArgumentParser(description="灵绘 AI 桥接服务器")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址")
    parser.add_argument("--port", type=int, default=8801, help="监听端口")
    args = parser.parse_args()

    server = start_bridge(args.host, args.port)

    def shutdown(sig, frame):
        logger.info("正在关闭桥接服务器...")
        server.shutdown()
        sys.exit(0)

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    logger.info("按 Ctrl+C 停止")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        shutdown(None, None)


if __name__ == "__main__":
    main()
