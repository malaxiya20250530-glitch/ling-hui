# 灵绘 配置指南

## 签名密钥

在 GitHub Secrets 中设置:

| Secret | 说明 |
|--------|------|
| KEYSTORE_BASE64 | keystore 文件的 Base64 |
| KEY_ALIAS | 密钥别名 |
| KEYSTORE_PASSWORD | keystore 密码 |
| KEY_PASSWORD | 密钥密码 |

## Unity 导出

1. 打开 `Unity/` 工程
2. File → Build Settings → Android
3. 勾选 Export Project
4. 导出到 `unity_client/`
