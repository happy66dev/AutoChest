package io.github.autochest;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * AutoChest 插件主类，负责插件的启动和关闭
 * 类似泰拉瑞亚的附近容器双向补货功能
 */
public class AutoChestPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // 保存默认配置文件（若不存在则从 JAR 内复制）
        saveDefaultConfig();

        // 打印启动信息，确认插件加载成功
        getLogger().info("AutoChest 已启动喵~");
    }

    @Override
    public void onDisable() {
        // 打印关闭信息
        getLogger().info("AutoChest 已关闭喵~");
    }
}
