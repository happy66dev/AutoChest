# [AutoChest] 类泰拉瑞亚附近容器整理 | 一键存入、自动补货、个人偏好 | Paper 1.21.4

> AutoChest 是一个面向 Minecraft 服务器的附近容器整理插件，为生存玩法提供类似 Terraria 的“快速堆叠至附近箱子”与“自动补货”体验。

## 插件简介

挖矿、探索或建造结束后，不必逐个打开箱子整理背包。

- 使用存入命令，将主背包中的物品快速归入附近容器。
- 使用补货命令，从附近容器补满背包内已有但未满的物品堆叠。
- 每名玩家可独立设置存入与补货时允许使用的容器种类、黑名单、排序模式和优先级。

插件的目标是减少重复整理操作，同时让玩家仍能控制物品应当进入哪些容器。

## 核心功能

### 一键存入附近容器

执行 `/autochest deposit` 后，插件会处理主背包槽位 `9..35` 中的物品。

1. 优先填充附近合格容器中已有的同类物品堆叠。
2. 再使用合格容器的空槽位存入剩余物品。
3. 不会直接处理快捷栏，避免影响玩家正在使用的常用物品。

### 自动补货

执行 `/autochest restock` 后，插件会扫描玩家槽位 `0..35` 内已有但未满的物品堆叠。

1. 从附近合格容器取出相同物品进行补充。
2. 仅补充命令开始时背包中已经存在的物品种类。
3. 不会把新物品塞入玩家的空槽位。

适合携带方块、火把、食物、箭矢或常用耗材进行长时间建造和探索。

### 容器支持

支持以下容器类型：

- 普通箱子与双箱。
- 陷阱箱。
- 木桶。
- 全部 17 种潜影盒。
- 末影箱入口。

附近末影箱会作为入口使用执行命令玩家自己的私有末影箱库存。范围内存在多个末影箱时，插件只使用最近的一个入口。

### 个人容器偏好

使用 `/ac config` 打开图形化设置主菜单，也可以通过文本命令直接调整设置。

- 存入与补货各自拥有独立配置。
- 可为 `chest`、`trapped_chest`、`barrel`、`shulker_box`、`ender_chest` 设置黑名单。
- 可选择按距离优先，或按自定义容器种类优先级排序。
- 玩家设置持久化保存在 `plugins/AutoChest/data/players/<UUID>.json`。

示例：

```text
/ac config deposit mode priority
/ac config restock blacklist add ender_chest
/ac config deposit priority move shulker_box up
/ac config restock priority list
```

## 使用方式

| 命令 | 作用 | 默认权限 |
| --- | --- | --- |
| `/autochest deposit` | 将主背包物品存入附近容器 | `autochest.deposit`，所有玩家 |
| `/autochest restock` | 从附近容器补足已有物品堆叠 | `autochest.restock`，所有玩家 |
| `/autochest config` | 打开个人容器偏好设置 | `autochest.config`，所有玩家 |
| `/autochest reload` | 重载插件配置 | `autochest.reload`，仅 OP |
| `/ac` | `/autochest` 的简写别名 | 与对应子命令相同 |

默认扫描范围以命令执行位置为中心，X、Y、Z 三个方向各 `±8` 格。

- 默认存入冷却为 5 秒。
- 默认补货冷却为 3 秒。
- 玩家已有整理任务运行时，再次执行任一整理命令会被拒绝。
- 扫描和提交会按 tick 分段执行，降低大范围容器整理对服务器 tick 的影响。

## 安装方法

1. 准备使用 Java `21` 运行的 Paper `1.21.4` 服务端。
2. 下载发布包，取得 `AutoChest-1.0.0.jar`。
3. 将 JAR 文件放入服务端的 `plugins/` 目录。
4. 重启服务端，让插件首次生成 `plugins/AutoChest/config.yml`。
5. 按服务器规则调整扫描范围、冷却、权限与消息配置。

自行构建时，在项目根目录执行：

```bash
mvn clean package
```

构建产物位于 `target/AutoChest-1.0.0.jar`。

## 版本支持

### 已声明支持

| 项目 | 支持版本 | 依据 |
| --- | --- | --- |
| 服务端核心 | Paper `1.21.4` | 项目 README 与 Maven `paper-api` 依赖声明 |
| Minecraft API 版本 | `1.21` | `plugin.yml` 的 `api-version` 声明 |
| Java | `21` | Maven 编译目标声明 |
| 插件版本 | `1.0.0` | `plugin.yml` 与构建版本声明 |

### 可选保护插件

以下插件为可选依赖，未安装时对应 Hook 不启用：

- WorldGuard 7。
- Towny。
- ChestShop。
- Slimefun。

对应行为如下：

- WorldGuard：非 `__global__` 区域内的容器会被排除。
- Towny：根据 Towny 的 `SWITCH` 权限判断容器访问。
- ChestShop：商店箱会被排除。
- Slimefun：带有 Slimefun 方块数据的容器会被排除；双箱任意一半命中时整箱跳过。
- 已安装保护插件但 Hook 初始化失败时，插件会拒绝存入和补货，避免意外绕过保护规则。

### 未承诺兼容范围

当前项目仅明确声明支持 Paper `1.21.4`，不保证以下环境可用：

- Spigot。
- Bukkit。
- Purpur。
- 其他服务端核心。
- 除 Paper `1.21.4` 外的 Minecraft 版本。

如需部署到以上环境，请先在独立测试服验证基础整理、双箱、末影箱，以及所有已安装保护插件的实际行为。

## 部署前建议测试

- 普通箱子、陷阱箱、木桶、双箱与全部潜影盒。
- 末影箱仅访问命令执行者自己的私有库存。
- 存入时优先填充已有堆叠，再使用空槽位。
- 补货时不向空背包槽位添加新的物品种类。
- 玩家在任务运行中点击、拖拽、丢弃或拾取物品时的安全跳过行为。
- 容器被破坏、替换、双箱拆分或重新配对时的处理。
- WorldGuard、Towny、ChestShop、Slimefun 的保护规则与服务器实际配置是否一致。

## 开源协议

本项目采用 [GNU Affero General Public License v3.0](../../LICENSE) 开源。

## 反馈与问题报告

提交问题时，建议附上以下信息：

- Paper、Java 与 AutoChest 的完整版本号。
- 已安装的保护类插件及其版本。
- 复现步骤、相关配置和控制台报错。
- 涉及的容器类型、玩家背包状态与预期结果。
