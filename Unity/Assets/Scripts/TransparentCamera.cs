using UnityEngine;

/// <summary>
/// 透明背景相机 —— 让 Unity 场景叠加到 Android 悬浮窗上
/// 要求：Unity Player Settings 中开启 "Transparent Background"
/// </summary>
[RequireComponent(typeof(Camera))]
public class TransparentCamera : MonoBehaviour
{
    void Start()
    {
        Camera cam = GetComponent<Camera>();
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0, 0, 0, 0);  // 全透明
        cam.orthographic = false;
        cam.fieldOfView = 30f;
        cam.nearClipPlane = 0.1f;
        cam.farClipPlane = 50f;

        // 相机位置：正面看向角色
        transform.position = new Vector3(0, 0.5f, -2.5f);
        transform.LookAt(Vector3.zero);
    }
}
