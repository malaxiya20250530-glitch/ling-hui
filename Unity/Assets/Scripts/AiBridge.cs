using UnityEngine;

/// <summary>
/// Unity ↔ Android AI 引擎桥接层
/// Android 通过 UnityPlayer.UnitySendMessage() 调用本脚本方法
/// </summary>
public class AiBridge : MonoBehaviour
{
    private LingHuiCharacter character;

    void Awake()
    {
        character = FindObjectOfType<LingHuiCharacter>();
    }

    // ── 以下方法由 Android 端通过 UnitySendMessage 调用 ──

    /// <summary>设置角色心情</summary>
    public void OnMoodChanged(string mood)
    {
        if (character != null) character.SetMood(mood);
        Debug.Log($"[AiBridge] 心情切换 → {mood}");
    }

    /// <summary>点击互动</summary>
    public void OnUserInteract(string _)
    {
        if (character != null) character.OnInteract();
        Debug.Log("[AiBridge] 用户互动触发");
    }

    /// <summary>AI 回复到达</summary>
    public void OnAiReply(string reply)
    {
        if (character != null) character.OnReplyReceived();
        Debug.Log($"[AiBridge] AI 回复: {reply.Substring(0, Mathf.Min(reply.Length, 50))}...");
    }

    /// <summary>空闲状态</summary>
    public void OnIdle(string _)
    {
        if (character != null) character.SetMood("idle");
    }
}
