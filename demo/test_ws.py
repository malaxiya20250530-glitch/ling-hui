"""WebSocket 服务器集成测试 — 验证实际协议握手与消息收发"""
import asyncio
import json
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _encode_frame(payload: str) -> bytes:
    """编码文本帧（客户端→服务器，带掩码）"""
    data = payload.encode("utf-8")
    length = len(data)
    import random
    mask = bytes([random.randint(0, 255) for _ in range(4)])
    frame = bytearray()
    frame.append(0x81)  # FIN + TEXT
    if length < 126:
        frame.append(0x80 | length)
    elif length < 65536:
        frame.append(0x80 | 126)
        frame.extend(struct.pack(">H", length))
    else:
        frame.append(0x80 | 127)
        frame.extend(struct.pack(">Q", length))
    frame.extend(mask)
    masked = bytearray(data)
    for i in range(length):
        masked[i] ^= mask[i % 4]
    frame.extend(masked)
    return bytes(frame)


def _decode_frame(data: bytes) -> str:
    """解码文本帧（服务器→客户端，无掩码）"""
    if len(data) < 2:
        return ""
    opcode = data[0] & 0x0F
    if opcode != 0x1:
        return ""
    length = data[1] & 0x7F
    pos = 2
    if length == 126:
        length = struct.unpack(">H", data[2:4])[0]
        pos = 4
    elif length == 127:
        length = struct.unpack(">Q", data[2:10])[0]
        pos = 10
    return data[pos:pos + length].decode("utf-8")


async def test_ws():
    from ai_core.ws_server import start_ws_server

    PORT = 19527  # 测试用端口
    server_task = asyncio.create_task(start_ws_server("127.0.0.1", PORT))
    await asyncio.sleep(0.3)

    try:
        reader, writer = await asyncio.open_connection("127.0.0.1", PORT)

        # 发送 WebSocket 握手
        ws_key = "dGhlIHNhbXBsZSBub25jZQ=="
        request = (
            "GET /ws HTTP/1.1\r\n"
            "Host: 127.0.0.1\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {ws_key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "\r\n"
        )
        writer.write(request.encode())
        await writer.drain()

        # 读取握手响应
        response = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=5)
        assert b"101" in response, f"握手失败: {response[:100]}"
        print("✅ WebSocket 握手成功")

        # 发送消息
        msg = json.dumps({"type": "user_input", "text": "你好"})
        writer.write(_encode_frame(msg))
        await writer.drain()

        # 读取响应
        raw = await asyncio.wait_for(reader.read(4096), timeout=5)
        reply = json.loads(_decode_frame(raw))
        assert reply["type"] == "pet_response", f"期望 pet_response, got {reply}"
        assert len(reply["text"]) > 0
        print(f"✅ 对话响应: {reply['text'][:50]}...")

        # 发送心跳
        msg2 = json.dumps({"type": "ping"})
        writer.write(_encode_frame(msg2))
        await writer.drain()
        raw2 = await asyncio.wait_for(reader.read(4096), timeout=5)
        reply2 = json.loads(_decode_frame(raw2))
        assert reply2["type"] == "pong"
        print("✅ 心跳响应")

        writer.close()
        print("\n🎉 WebSocket 集成测试全部通过！")
        return True

    finally:
        server_task.cancel()
        try:
            await server_task
        except asyncio.CancelledError:
            pass


if __name__ == "__main__":
    asyncio.run(test_ws())
