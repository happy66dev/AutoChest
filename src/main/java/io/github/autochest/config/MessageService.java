package io.github.autochest.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

/**
 * 消息服务，负责向玩家发送聊天提示并播放对应音效
 * 所有方法必须在主线程调用
 */
public class MessageService {

    /** 配置快照，实例不可变，reload 后通过新实例替换 */
    private final AutoChestConfig cfg;

    /**
     * 创建消息服务
     *
     * @param cfg 当前配置快照
     */
    public MessageService(AutoChestConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * 发送权限不足提示，并播放拒绝音效
     *
     * @param player 接收消息的玩家
     */
    public void sendNoPermission(Player player) {
        send(player, "&c你没有执行此操作的权限喵~");
        playSound(player, cfg.getSoundDenied());
    }

    /**
     * 发送"正在扫描附近容器"提示，并播放扫描开始音效
     *
     * @param player 接收消息的玩家
     */
    public void sendScanStarted(Player player) {
        send(player, cfg.getMsgScanStarted());
        playSound(player, cfg.getSoundScanStarted());
    }

    /**
     * 发送存入操作完成的统计消息，并播放成功音效
     *
     * @param player         接收消息的玩家
     * @param itemsMoved     成功移动的物品总数
     * @param containersUsed 实际参与的容器数
     * @param skipped        跳过的容器数
     */
    public void sendDepositDone(Player player, int itemsMoved, int containersUsed, int skipped) {
        // 替换消息模板中的占位符
        String msg = cfg.getMsgDepositDone()
                .replace("{items}", String.valueOf(itemsMoved))
                .replace("{containers}", String.valueOf(containersUsed))
                .replace("{skipped}", String.valueOf(skipped));
        send(player, msg);
        playSound(player, cfg.getSoundSuccess());
    }

    /**
     * 发送补货操作完成的统计消息，并播放成功音效
     *
     * @param player         接收消息的玩家
     * @param itemsMoved     成功补充的物品总数
     * @param containersUsed 实际参与的容器数
     * @param skipped        跳过的容器数
     */
    public void sendRestockDone(Player player, int itemsMoved, int containersUsed, int skipped) {
        String msg = cfg.getMsgRestockDone()
                .replace("{items}", String.valueOf(itemsMoved))
                .replace("{containers}", String.valueOf(containersUsed))
                .replace("{skipped}", String.valueOf(skipped));
        send(player, msg);
        playSound(player, cfg.getSoundSuccess());
    }

    /**
     * 发送无匹配物品提示，并播放对应音效
     *
     * @param player 接收消息的玩家
     */
    public void sendNoMatch(Player player) {
        send(player, cfg.getMsgNoMatch());
        playSound(player, cfg.getSoundNoMatch());
    }

    /**
     * 发送冷却中提示，并播放拒绝音效
     *
     * @param player      接收消息的玩家
     * @param remainingMs 剩余冷却毫秒数
     */
    public void sendCooldown(Player player, long remainingMs) {
        // 向上取整到秒，至少显示 1 秒
        long seconds = Math.max(1L, (remainingMs + 999L) / 1000L);
        String msg = cfg.getMsgCooldown().replace("{seconds}", String.valueOf(seconds));
        send(player, msg);
        playSound(player, cfg.getSoundDenied());
    }

    /**
     * 发送任务冲突提示（当前已有任务运行中），并播放拒绝音效
     *
     * @param player 接收消息的玩家
     */
    public void sendTaskConflict(Player player) {
        send(player, cfg.getMsgTaskConflict());
        playSound(player, cfg.getSoundDenied());
    }

    /**
     * 发送服务器繁忙提示（线程池队列满），并播放拒绝音效
     *
     * @param player 接收消息的玩家
     */
    public void sendServerBusy(Player player) {
        send(player, cfg.getMsgServerBusy());
        playSound(player, cfg.getSoundDenied());
    }

    /**
     * 发送 Hook 不可用提示（插件已安装但初始化失败），并播放错误音效
     *
     * @param player   接收消息的玩家
     * @param hookName 不可用的 Hook 名称，如 "WorldGuard"
     */
    public void sendHookUnavailable(Player player, String hookName) {
        String msg = cfg.getMsgHookUnavailable().replace("{hook}", hookName);
        send(player, msg);
        playSound(player, cfg.getSoundError());
    }

    /**
     * 发送操作取消提示，并播放拒绝音效
     *
     * @param player 接收消息的玩家
     */
    public void sendCancelled(Player player) {
        send(player, cfg.getMsgCancelled());
        playSound(player, cfg.getSoundDenied());
    }

    /**
     * 发送内部错误提示，并播放错误音效
     *
     * @param player 接收消息的玩家
     */
    public void sendInternalError(Player player) {
        send(player, cfg.getMsgInternalError());
        playSound(player, cfg.getSoundError());
    }

    /**
     * 将带有颜色代码的字符串翻译后发送给玩家
     * 使用 Adventure API 的 LegacyComponentSerializer 处理 & 颜色代码
     *
     * @param player 接收消息的玩家
     * @param raw    原始消息字符串，支持 & 颜色代码
     */
    private void send(Player player, String raw) {
        // 将 & 颜色代码翻译为 Adventure Component 后发送
        String prefixed = cfg.getMsgPrefix() + raw;
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(prefixed);
        player.sendMessage(component);
    }

    /**
     * 在玩家位置播放音效，名称为空或无效时静默跳过
     *
     * @param player    播放对象
     * @param soundName 音效枚举名称，空或无效则不播放
     */
    private void playSound(Player player, String soundName) {
        if (soundName == null || soundName.isBlank()) {
            return;
        }
        try {
            org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundName.trim().toUpperCase());
            // 在玩家所在位置以正常音量和音调播放
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException e) {
            // 喵~防御：无效音效名静默跳过，不影响消息发送
        }
    }
}
