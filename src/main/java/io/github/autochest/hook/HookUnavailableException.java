package io.github.autochest.hook;

/**
 * Hook 初始化失败时抛出的异常，用于向上层通知整个任务应被拒绝
 */
public class HookUnavailableException extends RuntimeException {

    /** 对应的 Hook 名称 */
    private final String hookName;

    /**
     * 创建异常
     *
     * @param hookName 不可用的 Hook 名称
     */
    public HookUnavailableException(String hookName) {
        super("Hook " + hookName + " is installed but unavailable");
        this.hookName = hookName;
    }

    /**
     * 获取 Hook 名称
     *
     * @return Hook 名称
     */
    public String getHookName() {
        return hookName;
    }
}
