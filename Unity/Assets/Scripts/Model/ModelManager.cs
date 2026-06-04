using UnityEngine;

/// <summary>
/// 模型管理器 —— 运行时切换角色模型
/// 优先加载 VRM，降级到内置球体
/// </summary>
public class ModelManager : MonoBehaviour
{
    [Header("模型列表（从 StreamingAssets 加载 .vrm）")]
    public string[] availableModels = { "linghui.vrm", "ne_zha.vrm" };

    private VRMModelLoader vrmLoader;
    private LingHuiCharacter character;
    private int currentModelIndex;

    void Awake()
    {
        vrmLoader = GetComponent<VRMModelLoader>();
        if (vrmLoader == null) vrmLoader = gameObject.AddComponent<VRMModelLoader>();

        character = FindObjectOfType<LingHuiCharacter>();
    }

    void Start()
    {
        if (availableModels.Length > 0)
        {
            vrmLoader.modelFileName = availableModels[0];
            vrmLoader.LoadModel();
        }
    }

    /// <summary>切换到下一个模型</summary>
    public void NextModel()
    {
        if (availableModels.Length == 0) return;

        currentModelIndex = (currentModelIndex + 1) % availableModels.Length;
        vrmLoader.SwitchModel(availableModels[currentModelIndex]);

        // 等待一帧后重新获取角色控制器引用
        StartCoroutine(RefreshCharacterRef());
    }

    System.Collections.IEnumerator RefreshCharacterRef()
    {
        yield return null;
        character = FindObjectOfType<LingHuiCharacter>();
    }

    /// <summary>设置心情（转发给角色控制器）</summary>
    public void SetMood(string mood)
    {
        if (character != null) character.SetMood(mood);
    }

    /// <summary>互动触发</summary>
    public void OnInteract()
    {
        if (character != null) character.OnInteract();
    }
}
