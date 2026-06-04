using UnityEngine;

/// <summary>
/// 灵绘角色控制器：旋转、呼吸动画、心情响应
/// </summary>
public class LingHuiCharacter : MonoBehaviour
{
    [Header("旋转")]
    public float idleRotationSpeed = 15f;
    public float activeRotationSpeed = 45f;

    [Header("呼吸动画")]
    public float breatheAmplitude = 0.02f;
    public float breatheFrequency = 1.5f;

    [Header("心情")]
    public Material[] moodMaterials;  // 0=默认, 1=兴奋, 2=开心
    private Renderer charRenderer;

    private float currentRotationSpeed;
    private Vector3 originalScale;
    private int currentMood;

    void Start()
    {
        charRenderer = GetComponentInChildren<Renderer>();
        originalScale = transform.localScale;
        currentRotationSpeed = idleRotationSpeed;
        currentMood = 0;
    }

    void Update()
    {
        // 自转
        transform.Rotate(Vector3.up, currentRotationSpeed * Time.deltaTime);

        // 呼吸缩放
        float breathe = 1f + Mathf.Sin(Time.time * breatheFrequency) * breatheAmplitude;
        transform.localScale = originalScale * breathe;
    }

    /// <summary>Android 端调用：设置心情</summary>
    public void SetMood(string mood)
    {
        switch (mood)
        {
            case "excited":
                currentRotationSpeed = activeRotationSpeed;
                currentMood = 1;
                break;
            case "happy":
                currentRotationSpeed = activeRotationSpeed * 0.7f;
                currentMood = 2;
                break;
            default:
                currentRotationSpeed = idleRotationSpeed;
                currentMood = 0;
                break;
        }
        if (charRenderer != null && moodMaterials != null && currentMood < moodMaterials.Length)
        {
            charRenderer.material = moodMaterials[currentMood];
        }
    }

    /// <summary>Android 端调用：被点击互动</summary>
    public void OnInteract()
    {
        // 跳一跳
        StopAllCoroutines();
        StartCoroutine(JumpRoutine());
    }

    System.Collections.IEnumerator JumpRoutine()
    {
        Vector3 peak = originalScale * 1.15f;
        float duration = 0.3f;
        for (float t = 0; t < duration; t += Time.deltaTime)
        {
            float p = t / duration;
            transform.localScale = Vector3.Lerp(originalScale, peak, Mathf.Sin(p * Mathf.PI));
            yield return null;
        }
        transform.localScale = originalScale;
    }

    /// <summary>Android 端调用：收到 AI 回复</summary>
    public void OnReplyReceived()
    {
        SetMood("happy");
    }
}
