package net.momirealms.sparrow.sync.session.gate;

public interface LoginGate {

    /**
     * 绑定会话服务并安装登录阶段的数据加载门.
     */
    void onDelayedEnable();
}
