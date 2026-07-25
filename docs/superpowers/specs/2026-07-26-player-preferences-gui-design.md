# 玩家容器偏好 GUI 设计

## 目标

在保留现有文本配置命令的前提下，为玩家容器偏好增加可视化 GUI 配置入口。GUI 与命令必须使用同一 `PlayerPreferencesService`、同一份内存状态和同一个 JSON 原子保存队列。

GUI 用于管理 deposit 与 restock 各自独立的排序模式、容器种类黑名单和容器种类优先级。现有文本命令 `mode`、`blacklist`、`priority move/reset/list` 的语法与行为不得改变。

## 入口与页面结构

`/ac config` 无参数时打开主菜单，提供两个入口：

- 存入设置（deposit）
- 补货设置（restock）

`/ac config deposit` 与 `/ac config restock` 直接打开对应操作子页面。带有后续配置参数的既有命令继续按文本命令解析。

每个操作子页面使用 `6×9` 容器 GUI，并在单页内直接完成全部调整：

- 当前排序模式与点击切换按钮。
- 5 个容器种类黑名单开关：普通箱子、陷阱箱、木桶、潜影盒、末影箱。
- 容器种类优先级列表，每项提供上移与下移按钮。
- 重置优先级按钮与返回主菜单按钮。

潜影盒始终只显示并配置为一个 `SHULKER_BOX` 种类，覆盖未染色和全部 16 种染色潜影盒。

黑名单种类使用红色状态提示“已排除”，允许种类使用绿色状态提示“已启用”。点击黑名单按钮立即调用现有偏好服务更新内存并排队 JSON 保存。优先级无论当前模式都可编辑和显示，但仅在容器优先模式下影响新任务的容器处理顺序。

## GUI 会话与安全

每种页面使用专属 `InventoryHolder`，不通过库存标题识别界面。Holder 至少保存页面类型、玩家 UUID、操作类型与不可预测会话 token。

新增主线程 GUI 会话表，记录每位玩家当前 token。每次打开主菜单或操作页面都生成新 token。任何点击动作都必须同时验证：

1. 顶部库存 Holder 是本插件 GUI 页面。
2. 点击玩家 UUID 与 Holder 中 UUID 一致。
3. 该玩家当前会话 token 与 Holder token 一致。
4. 玩家仍打开该 Holder 对应的顶部库存。

验证失败时不更新偏好。`InventoryCloseEvent` 仅当 token 与当前记录一致时删除会话，避免旧页面延迟关闭误删新页面会话。

当本插件 GUI 位于顶部库存时，统一取消全部 `InventoryClickEvent` 路径，包括顶部与下方玩家背包点击、shift-click、数字键热键和双击收集。`InventoryDragEvent` 只要涉及顶部 GUI raw slot 也必须取消，保证展示物品不能被取走或放入。

玩家退出、死亡、切换世界、关闭 GUI 与插件停用时清理会话。死亡和切换世界主动关闭本 GUI，避免与其他插件库存界面交错。插件停用时会话全部失效。

每个操作按钮执行后从 `PlayerPreferencesService.snapshot(...)` 重新读取数据并重绘页面；绝不通过 ItemMeta、lore 或显示名称反向解析偏好状态。

## 服务与持久化边界

GUI 复用现有 `PlayerPreferencesService`：

- `snapshot(...)`
- `setOrderMode(...)`
- `setBlacklisted(...)`
- `movePriority(...)`
- `resetPriority(...)`

GUI 不接触 JSON 文件、可变内部 profile 或持久化 executor。所有 Bukkit GUI 和玩家操作仅在主线程完成；偏好服务继续将深拷贝 JSON 文本提交给独立单线程持久化队列。

命令和 GUI 的任意修改都会在另一个入口立即体现，并在 `plugins/AutoChest/data/players/<UUID>.json` 中持久化。

## 装配与命令兼容

新增 `io.github.autochest.gui` 包，包含 GUI 页面 Holder、会话注册表和监听器/渲染器。`AutoChestPlugin` 创建 GUI 管理器并注册其事件监听器，再将 GUI opener 注入 `AutoChestCommand`。

`autochest.config` 保持为 GUI 与命令共用权限，不新增额外权限。帮助信息和 README 补充 GUI 打开方式。

## 测试与验收

自动化测试至少覆盖：

1. `/ac config` 打开主菜单；无权限不打开。
2. 主菜单分别进入正确的 deposit 与 restock 子页面。
3. 模式切换、黑名单开关、优先级上移/下移、重置正确更新服务快照，且两个操作互不影响。
4. 顶部点击、下方背包点击、shift-click、数字键热键与拖拽均不能移动展示物品。
5. 相同标题但非本插件 Holder 的库存事件不被拦截。
6. UUID 不匹配、过期 token、旧页面关闭后重开等事件不修改当前配置。
7. GUI 修改与文本命令共享同一内存状态与 JSON 结果。
8. 退出、死亡、换世界、关闭和插件停用清理 GUI 会话。

Paper `1.21.4` 人工验收：通过 GUI 设置 deposit 优先潜影盒、restock 优先末影箱，并确认新任务实际排序改变；重启后 JSON 恢复；死亡或切世界关闭 GUI；WorldGuard、Towny、ChestShop 与 Slimefun 的保护逻辑仍无法被 GUI 绕过。
