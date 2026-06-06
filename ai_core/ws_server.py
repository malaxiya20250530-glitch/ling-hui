"""灵绘 WebSocket 服务器 — 连接 Android WebView 前端与 Python 后端

实现 RFC 6455 WebSocket 协议（纯标准库，零外部依赖）：
- 握手：Sec-WebSocket-Key → Sec-WebSocket-Accept
- 帧解析：文本帧发送/接收
- 消息路由：对话 / 音乐 / 语音
"""

import asyncio
import hashlib
import json
import logging
import os
import struct
import time
from pathlib import Path

logger = logging.getLogger("linghui.ws")

# ── 导入本地模块（可选，未安装时降级） ──
try:
    from .music_downloader import (search_music, download_song,
                                    get_download_status, list_downloaded,
                                    get_music_file_url, get_lyrics)
except ImportError:
    from music_downloader import (search_music, download_song,
                                   get_download_status, list_downloaded,
                                   get_music_file_url, get_lyrics)

try:
    from demo.mock_engine import mock_chat
except ImportError:
    def mock_chat(text):
        return "灵绘离线中，请启动完整后端～"


# ── WebSocket 帧常量 ─────────────────────────────────

OP_TEXT = 0x1
OP_CLOSE = 0x8
OP_PING = 0x9
OP_PONG = 0xA

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def _make_accept(key: str) -> str:
    """计算 WebSocket Accept 密钥"""
    sha1 = hashlib.sha1((key + WS_GUID).encode()).digest()
    import base64
    return base64.b64encode(sha1).decode()


def _encode_frame(payload: str) -> bytes:
    """编码文本帧（服务器→客户端，无掩码）"""
    data = payload.encode("utf-8")
    length = len(data)
    frame = bytearray()
    frame.append(0x81)  # FIN + TEXT opcode
    if length < 126:
        frame.append(length)
    elif length < 65536:
        frame.append(126)
        frame.extend(struct.pack(">H", length))
    else:
        frame.append(127)
        frame.extend(struct.pack(">Q", length))
    frame.extend(data)
    return bytes(frame)


def _decode_frame(data: bytes) -> tuple[int, bytes]:
    """解码帧（客户端→服务器，有掩码），返回 (opcode, payload_bytes)"""
    if len(data) < 2:
        return 0, b""
    opcode = data[0] & 0x0F
    masked = bool(data[1] & 0x80)
    length = data[1] & 0x7F
    pos = 2
    if length == 126:
        if len(data) < 4:
            return 0, b""
        length = struct.unpack(">H", data[2:4])[0]
        pos = 4
    elif length == 127:
        if len(data) < 10:
            return 0, b""
        length = struct.unpack(">Q", data[2:10])[0]
        pos = 10
    if masked:
        if len(data) < pos + 4 + length:
            return 0, b""
        mask = data[pos:pos + 4]
        pos += 4
        payload = bytearray(data[pos:pos + length])
        for i in range(length):
            payload[i] ^= mask[i % 4]
        return opcode, bytes(payload)
    return opcode, data[pos:pos + length]


# ── 消息路由 ─────────────────────────────────────────


def handle_message(msg: dict) -> dict | list[dict]:
    """处理单条 WebSocket 消息，返回响应（单条或多条）

    支持的消息类型：
        user_input / voice_chat → 对话 → pet_response
        music_list → 列出歌曲 → music_list
        music_download → 下载歌曲 → music_downloaded
        music_search → 搜索歌曲 → music_search_result
        ping → pong
    """
    msg_type = msg.get("type", "")

    # ── 对话 ──
    if msg_type in ("user_input", "voice_chat"):
        text = msg.get("text", "").strip()
        if not text:
            return {"type": "error", "message": "空消息"}
        reply = mock_chat(text)
        # 模拟打字延迟
        return {"type": "pet_response", "text": reply}

    # ── 音乐列表 ──
    elif msg_type == "music_list":
        songs = list_downloaded()
        files = []
        for s in songs:
            files.append({
                "name": s["title"] or s["file"],
                "url": f"/music/file/{s['file']}",
                "size": s.get("size", 0),
                "has_lyrics": s.get("has_lyrics", False),
            })
        return {"type": "music_list", "files": files}

    # ── 音乐下载 ──
    elif msg_type == "music_download":
        url = msg.get("url", "")
        if not url:
            return {"type": "error", "message": "缺少下载链接"}
        result = download_song(url)
        if result.get("status") == "downloading":
            return {"type": "music_download_started",
                    "task_id": result.get("task_id", ""),
                    "url": url}
        return {"type": "error", "message": "下载启动失败"}

    # ── 音乐搜索 ──
    elif msg_type == "music_search":
        query = msg.get("query", "")
        if not query:
            return {"type": "error", "message": "缺少搜索词"}
        songs = search_music(query)
        return {"type": "music_search_result", "songs": songs, "query": query}

    # ── 音乐上传 ──
    elif msg_type == "music_upload":
        filename = msg.get("filename", "unknown.mp3")
        data_b64 = msg.get("data", "")
        if data_b64:
            import base64
            try:
                raw = base64.b64decode(data_b64)
                from .music_downloader import MUSIC_DIR
                filepath = MUSIC_DIR / filename
                filepath.write_bytes(raw)
                return {"type": "music_downloaded",
                        "name": filename,
                        "size": len(raw)}
            except Exception as e:
                return {"type": "error", "message": f"保存失败: {e}"}
        return {"type": "error", "message": "缺少文件数据"}

    # ── 下载状态查询 ──
    elif msg_type == "music_status":
        task_id = msg.get("task_id", "")
        status = get_download_status(task_id)
        if status.get("status") == "ok":
            return {"type": "music_downloaded",
                    "name": status.get("title", ""),
                    "size": os.path.getsize(status["file"])
                    if status.get("file") and os.path.exists(status["file"]) else 0}
        elif status.get("status") == "error":
            return {"type": "error", "message": status.get("error", "下载失败")}
        return {"type": "music_status_update",
                "task_id": task_id,
                "progress": status.get("progress", 0)}

    # ── 心跳 ──
    elif msg_type == "ping":
        return {"type": "pong", "time": int(time.time())}

    return {"type": "error", "message": f"未知消息类型: {msg_type}"}


# ── 客户端连接处理 ──────────────────────────────────


async def handle_client(reader: asyncio.StreamReader,
                        writer: asyncio.StreamWriter):
    """处理单个 WebSocket 客户端连接"""
    addr = writer.get_extra_info("peername", ("?", 0))
    logger.info("WS 连接: %s:%d", addr[0], addr[1])

    try:
        # ── 1. 读取 HTTP 升级请求 ──
        request = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=10)
        request_text = request.decode("utf-8", errors="replace")
        headers = {}
        for line in request_text.split("\r\n")[1:]:
            if ":" in line:
                key, val = line.split(":", 1)
                headers[key.strip().lower()] = val.strip()

        ws_key = headers.get("sec-websocket-key", "")
        if not ws_key:
            logger.warning("非 WebSocket 请求")
            writer.close()
            return

        # ── 2. 发送握手响应 ──
        accept = _make_accept(ws_key)
        response = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\n"
            "\r\n"
        )
        writer.write(response.encode())
        await writer.drain()

        logger.info("WS 握手完成: %s:%d", addr[0], addr[1])

        # ── 3. 消息循环 ──
        buffer = b""
        while True:
            try:
                chunk = await asyncio.wait_for(reader.read(4096), timeout=300)
            except asyncio.TimeoutError:
                # 发送 ping
                writer.write(_encode_frame(""))
                frame = bytearray()
                frame.append(0x89)  # FIN + PING
                frame.append(0)
                writer.write(bytes(frame))
                await writer.drain()
                continue

            if not chunk:
                break

            buffer += chunk

            # 尝试解析完整帧
            while len(buffer) >= 2:
                opcode, payload = _decode_frame(buffer)
                if opcode == 0 and not payload:
                    break  # 帧不完整，等待更多数据

                # 计算帧总长度并消耗 buffer
                frame_len = _frame_total_len(buffer)
                if frame_len > len(buffer):
                    break
                buffer = buffer[frame_len:]

                if opcode == OP_CLOSE:
                    logger.info("WS 关闭: %s:%d", addr[0], addr[1])
                    close_frame = bytearray()
                    close_frame.append(0x88)
                    close_frame.append(0)
                    writer.write(bytes(close_frame))
                    await writer.drain()
                    return

                if opcode == OP_PING:
                    pong_frame = bytearray()
                    pong_frame.append(0x8A)
                    pong_frame.append(len(payload))
                    pong_frame.extend(payload)
                    writer.write(bytes(pong_frame))
                    await writer.drain()
                    continue

                if opcode == OP_PONG:
                    continue

                if opcode == OP_TEXT and payload:
                    try:
                        msg = json.loads(payload.decode("utf-8"))
                    except (json.JSONDecodeError, UnicodeDecodeError) as e:
                        logger.warning("JSON 解析失败: %s", e)
                        continue

                    logger.debug("收到: %s", msg.get("type", "?"))
                    response = handle_message(msg)

                    if isinstance(response, list):
                        for r in response:
                            writer.write(_encode_frame(json.dumps(r, ensure_ascii=False)))
                    elif response:
                        writer.write(_encode_frame(json.dumps(response, ensure_ascii=False)))
                    await writer.drain()

    except (ConnectionResetError, BrokenPipeError, asyncio.IncompleteReadError):
        pass
    except Exception:
        logger.exception("WS 异常: %s:%d", addr[0], addr[1])
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass
        logger.info("WS 断开: %s:%d", addr[0], addr[1])


def _frame_total_len(data: bytes) -> int:
    """计算当前帧总字节数（header + payload）"""
    if len(data) < 2:
        return 99999
    length = data[1] & 0x7F
    masked = bool(data[1] & 0x80)
    pos = 2
    if length == 126:
        if len(data) < 4:
            return 99999
        length = struct.unpack(">H", data[2:4])[0]
        pos = 4
    elif length == 127:
        if len(data) < 10:
            return 99999
        length = struct.unpack(">Q", data[2:10])[0]
        pos = 10
    if masked:
        pos += 4
    return pos + length


# ── 服务器启动 ────────────────────────────────────────


async def start_ws_server(host: str = "0.0.0.0", port: int = 9527):
    """启动 WebSocket 服务器

    参数:
        host: 监听地址
        port: 监听端口（默认 9527，与前端默认一致）
    """
    server = await asyncio.start_server(handle_client, host, port)
    logger.info("灵绘 WebSocket 服务器启动: %s:%d", host, port)

    async with server:
        await server.serve_forever()


def run_ws_server(host: str = "0.0.0.0", port: int = 9527):
    """同步入口 — 启动 WebSocket 服务器（阻塞）"""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [ws] %(message)s",
        datefmt="%H:%M:%S",
    )
    try:
        asyncio.run(start_ws_server(host, port))
    except KeyboardInterrupt:
        logger.info("WebSocket 服务器已停止")


# ── CLI 入口 ──────────────────────────────────────────

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="灵绘 WebSocket 服务器")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址")
    parser.add_argument("--port", type=int, default=9527, help="监听端口")
    args = parser.parse_args()
    run_ws_server(args.host, args.port)
