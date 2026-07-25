# AutoChest

AutoChest 是一个为 Minecraft 服务器设计的附近容器整理插件，提供类似泰拉瑞亚的存入与补货体验喵~

> 本插件由 AI 协助创建与维护，部署前请先在测试服务器完成验证喵~

## 服务端支持

- 服务端核心：Paper `1.21.4` 喵~
- Java：`21` 喵~
- 不保证 Spigot、Bukkit、Purpur 或其他核心的兼容性喵~
- 可选保护插件：WorldGuard 7、Towny、ChestShop、Slimefun 喵~

## 功能

- `/autochest deposit`：将主背包槽位 `9..35` 的物品存入附近容器喵~
- `/autochest restock`：从附近容器补满快捷栏与主背包槽位 `0..35` 中已有的未满堆叠喵~
- `/ac`：`/autochest` 的命令别名喵~
- 支持普通箱子、陷阱箱、木桶、全部 17 种潜影盒与双箱喵~
- 附近末影箱会作为入口使用执行命令玩家自己的私有末影箱库存；多个入口只取最近的一个喵~
- deposit 采用全局两阶段：先填充已有同类堆叠，再使用合格容器的空槽喵~
- restock 只补充命令开始时已有的物品种类，不会占用玩家空槽喵~
- 支持 WorldGuard、Towny、ChestShop、Slimefun 的保护检查喵~
- 使用分 tick 扫描与提交预算，避免大范围容器扫描造成长 tick 喵~

## 安装

1. 使用 Java 21 启动 Paper `1.21.4` 服务器喵~
2. 从发布包或本地构建产物取得 `AutoChest-1.0.0.jar` 喵~
3. 将 JAR 放入服务器的 `plugins/` 目录喵~
4. 重启服务器，让插件生成 `plugins/AutoChest/config.yml` 喵~
5. 根据服务器规则调整权限、范围、冷却和消息配置喵~

## 命令与权限

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/autochest deposit` | `autochest.deposit` | 存入主背包物品 |
| `/autochest restock` | `autochest.restock` | 补满已有物品堆叠 |
| `/autochest reload` | `autochest.reload` | 重载插件配置 |

## 配置概要

默认扫描范围以命令执行位置为中心，X、Y、Z 各 `±8` 格喵~

- deposit 默认冷却：`5` 秒喵~
- restock 默认冷却：`3` 秒喵~
- 扫描和提交均按每 tick 方块/容器数量与时间预算分段执行喵~
- 正在运行任务的玩家再次执行任一整理命令会被拒绝喵~

详细配置请查看首次启动生成的 `plugins/AutoChest/config.yml` 喵~

## 保护插件行为

- WorldGuard：任意非 `__global__` 区域内的容器会被排除喵~
- Towny：按照 Towny 的 `SWITCH` 权限判断容器访问喵~
- ChestShop：商店箱会被排除喵~
- Slimefun：带有 Slimefun 方块数据的容器会被排除；双箱任一半命中时整箱跳过；当前通过 `BlockStorage.hasBlockInfo(Block)` 兼容 API 识别，升级 Slimefun 时请复验喵~
- 已安装保护插件但 Hook 无法初始化时，插件会拒绝存入和补货操作，避免绕过保护喵~

## 构建

```bash
mvn clean package
```

构建完成后的插件包位于 `target/AutoChest-1.0.0.jar` 喵~

## 验证建议

部署前请至少在 Paper `1.21.4` 测试服验证以下场景喵~

- 普通箱子、陷阱箱、木桶、双箱与全部 17 种潜影盒喵~
- 末影箱仅操作执行命令玩家的私有库存；多个附近末影箱只使用最近入口喵~
- deposit 的先填满已有堆叠、再使用空槽逻辑喵~
- restock 期间玩家点击、拖拽、丢弃或拾取物品后的目标槽失效行为喵~
- 容器被破坏、替换、双箱拆分或重新配对时应安全跳过喵~
- WorldGuard、Towny、ChestShop、Slimefun 的实际保护规则喵~
- Slimefun 容器与双箱任一半带有 Slimefun 方块数据时的排除行为喵~

## 许可证

本项目采用 [GNU Affero General Public License v3.0](LICENSE) 开源喵~
