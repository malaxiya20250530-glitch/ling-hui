package com.linghui.app;

/** 角色渲染视图统一接口 —— OpenGL 和 Unity 都实现此接口 */
public interface ICharacterView {
    void onInteract();
    void onReplyReceived();
    void onIdle();
}
