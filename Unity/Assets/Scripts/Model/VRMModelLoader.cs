using UnityEngine;
using System.IO;

/// <summary>
/// 运行时 VRM 模型加载器
/// 从 StreamingAssets 加载 .vrm 文件，挂载到场景角色位置
/// 参考: UniVRM 运行时加载流程
/// </summary>
public class VRMModelLoader : MonoBehaviour
{
    [Header("模型配置")]
    public string modelFileName = "linghui.vrm";   // StreamingAssets 下的文件名
    public Transform modelParent;                    // 模型挂载点（默认自身）

    [Header("加载选项")]
    public bool loadOnStart = true;
    public bool useMToonShader = true;               // 尝试使用 MToon 材质

    private GameObject loadedModel;
    private Animator modelAnimator;

    void Start()
    {
        if (loadOnStart) LoadModel();
    }

    /// <summary>加载 VRM 模型</summary>
    public void LoadModel()
    {
        string path = Path.Combine(Application.streamingAssetsPath, modelFileName);

        if (!File.Exists(path))
        {
            Debug.LogWarning($"[VRMLoader] 模型文件不存在: {path}");
            Debug.LogWarning("[VRMLoader] 将使用内置球体作为 fallback");
            return;
        }

        StartCoroutine(LoadVRMFromFile(path));
    }

    System.Collections.IEnumerator LoadVRMFromFile(string path)
    {
        // 读取文件字节
        byte[] vrmData;
#if UNITY_ANDROID && !UNITY_EDITOR
        // Android 上 StreamingAssets 在 APK 内，需用 UnityWebRequest
        using (var www = UnityEngine.Networking.UnityWebRequest.Get(path))
        {
            yield return www.SendWebRequest();
            if (www.result != UnityEngine.Networking.UnityWebRequest.Result.Success)
            {
                Debug.LogError($"[VRMLoader] 读取失败: {www.error}");
                yield break;
            }
            vrmData = www.downloadHandler.data;
        }
#else
        vrmData = File.ReadAllBytes(path);
        yield return null;
#endif

        // 解析 VRM（简化版：实际项目需集成 UniVRM 库）
        // UniVRM: VRMImporterContext.LoadVrm(vrmData, ...)
        CreateModelFromBytes(vrmData);
    }

    /// <summary>从字节数据创建模型（简化实现）</summary>
    private void CreateModelFromBytes(byte[] vrmData)
    {
        // 清除旧模型
        if (loadedModel != null) Destroy(loadedModel);

        // 此处为简化实现 —— 实际应使用 UniVRM 的 VRMImporterContext
        // 参考: https://github.com/vrm-c/UniVRM
        //
        // using VRM;
        // var context = new VRMImporterContext();
        // context.ParseGlb(vrmData);
        // var meta = context.ReadMeta();
        // context.Load();
        // context.ShowMeshes();
        // loadedModel = context.Root;

        // ── Fallback: 创建一个占位角色 ──
        loadedModel = GameObject.CreatePrimitive(PrimitiveType.Capsule);
        loadedModel.name = "LingHui_Placeholder";

        Transform parent = modelParent != null ? modelParent : transform;
        loadedModel.transform.SetParent(parent);
        loadedModel.transform.localPosition = Vector3.zero;
        loadedModel.transform.localScale = Vector3.one * 0.6f;

        // 挂载角色控制器
        var character = loadedModel.AddComponent<LingHuiCharacter>();
        modelAnimator = loadedModel.AddComponent<Animator>();

        // 尝试应用卡通材质
        if (useMToonShader)
        {
            Shader toon = Shader.Find("LingHui/ToonCharacter");
            if (toon != null)
            {
                var renderer = loadedModel.GetComponent<Renderer>();
                if (renderer != null)
                {
                    renderer.material.shader = toon;
                    renderer.material.SetColor("_RimColor", new Color(0.8f, 0.9f, 1f));
                }
            }
        }

        Debug.Log($"[VRMLoader] 模型已就位: {loadedModel.name}");
    }

    /// <summary>切换模型</summary>
    public void SwitchModel(string fileName)
    {
        modelFileName = fileName;
        LoadModel();
    }

    /// <summary>获取当前模型的 Animator</summary>
    public Animator GetAnimator() => modelAnimator;

    /// <summary>模型是否已加载</summary>
    public bool IsLoaded => loadedModel != null;
}
