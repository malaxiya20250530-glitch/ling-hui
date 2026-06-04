using UnityEngine;
using System;

/// <summary>
/// Unity ↔ Android 通信桥
/// 通过 AndroidJavaClass 调用宿主 Activity 的方法
/// </summary>
public class LingHuiBridge : MonoBehaviour
{
    [Header("角色引用")]
    public LingHuiCharacter character;

    private AndroidJavaObject _unityPlayer;
    private AndroidJavaObject _currentActivity;

    /// <summary>
    /// 消息格式：{ "type": "mood"|"talking"|"tap"|"reply", "data": {...} }
    /// </summary>
    [Serializable]
    public class AiMessage
    {
        public string type;
        public string mood;
        public bool talking;
        public float talkIntensity;
        public string replyText;
        public string actionType;
        public string actionResult;
    }

    void Start()
    {
        if (character == null)
            character = FindObjectOfType<LingHuiCharacter>();

        try
        {
            _unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
            _currentActivity = _unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
        }
        catch (Exception e)
        {
            Debug.LogWarning($"[灵绘] Android 桥初始化失败: {e.Message}");
        }
    }

    /// <summary>
    /// 从 Android 宿主接收 AI 管线消息
    /// 由 Android 端通过 UnityPlayer.UnitySendMessage() 调用
    /// </summary>
    public void OnAiMessage(string json)
    {
        try
        {
            AiMessage msg = JsonUtility.FromJson<AiMessage>(json);
            if (msg == null) return;

            switch (msg.type)
            {
                case "mood":
                    if (character != null) character.SetMood(msg.mood);
                    break;
                case "talking":
                    if (character != null)
                    {
                        if (msg.talking) character.StartTalking();
                        else character.StopTalking();
                        character.SetTalkIntensity(msg.talkIntensity);
                    }
                    break;
                case "reply":
                    if (character != null)
                    {
                        character.StartTalking();
                        character.SetTalkIntensity(1f);
                        // 延迟停止说话（模拟说完）
                        Invoke(nameof(StopTalkingDelayed), 1.5f);
                    }
                    NotifyAndroid("reply_received", msg.replyText);
                    break;
                case "tap":
                    if (character != null) character.OnInteractionTap();
                    break;
            }
        }
        catch (Exception e)
        {
            Debug.LogError($"[灵绘] 消息解析失败: {e.Message}");
        }
    }

    private void StopTalkingDelayed()
    {
        if (character != null) character.StopTalking();
    }

    /// <summary>
    /// 向 Android 宿主发送消息
    /// </summary>
    public void NotifyAndroid(string eventName, string data)
    {
        if (_currentActivity != null)
        {
            _currentActivity.Call("onLingHuiEvent", eventName, data);
        }
    }
}
