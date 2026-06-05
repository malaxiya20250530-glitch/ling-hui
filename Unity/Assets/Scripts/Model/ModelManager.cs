using UnityEngine;

/// <summary>
/// 模型管理器 — 支持 GLB 预制体和 VRM 运行时加载
/// GLB: 直接拖入 Unity Assets → 挂载到 modelPrefab 槽位
/// VRM: 放入 StreamingAssets → VRMModelLoader 动态加载
/// </summary>
public class ModelManager : MonoBehaviour
{
    [Header("GLB 预制体（优先使用）")]
    [Tooltip("从 Assets 拖入 nezha.glb 生成的预制体")]
    public GameObject modelPrefab;

    [Header("VRM 回退（从 StreamingAssets 加载）")]
    public string[] vrmFallbacks = { "linghui.vrm" };

    [Header("运行时")]
    [SerializeField] private GameObject currentModel;

    private VRMModelLoader vrmLoader;
    private LingHuiCharacter character;

    void Awake()
    {
        vrmLoader = GetComponent<VRMModelLoader>();
        if (vrmLoader == null) vrmLoader = gameObject.AddComponent<VRMModelLoader>();
    }

    void Start()
    {
        // 优先用 GLB 预制体
        if (modelPrefab != null)
        {
            currentModel = Instantiate(modelPrefab, transform);
            currentModel.name = modelPrefab.name;
            Debug.Log($"[ModelManager] GLB 预制体已加载: {modelPrefab.name}");
        }
        // 回退到 VRM
        else if (vrmFallbacks.Length > 0)
        {
            vrmLoader.modelFileName = vrmFallbacks[0];
            vrmLoader.LoadModel();
            Debug.Log($"[ModelManager] VRM 回退: {vrmFallbacks[0]}");
        }
        else
        {
            Debug.LogWarning("[ModelManager] 无可用模型");
        }

        // 获取或添加角色控制器
        if (currentModel != null)
        {
            character = currentModel.GetComponent<LingHuiCharacter>();
            if (character == null)
                character = currentModel.AddComponent<LingHuiCharacter>();
        }
    }

    /// <summary>切换到指定名称的 VRM 模型</summary>
    public void SwitchToVRM(string fileName)
    {
        if (currentModel != null)
            Destroy(currentModel);
        vrmLoader.SwitchModel(fileName);
    }

    /// <summary>设置心情</summary>
    public void SetMood(string mood)
    {
        if (character != null) character.SetMood(mood);
    }

    /// <summary>互动触发</summary>
    public void OnInteract()
    {
        if (character != null) character.OnInteract();
    }

    /// <summary>获取当前模型</summary>
    public GameObject CurrentModel => currentModel;
}
