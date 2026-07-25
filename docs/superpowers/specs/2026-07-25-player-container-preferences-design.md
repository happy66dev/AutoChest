# 玩家容器偏好设计

## 目标

为 AutoChest 增加玩家级、持久化的容器偏好配置。每位玩家分别维护 `deposit` 与 `restock` 两套完全独立的容器黑名单、排序模式和容器种类优先级。配置只影响新创建的任务，运行中的扫描、异步规划和分 tick 提交必须使用命令开始时固化的快照。

支持的容器种类固定为：普通箱子、陷阱箱、木桶、潜影盒和末影箱。未染色潜影盒与全部 16 种染色潜影盒统一作为一个 `SHULKER_BOX` 种类。

## 偏好模型

每个操作维护一个独立的 `OperationPreferences`：

- `blacklistedContainerTypes`：被排除的容器种类集合。
- `orderMode`：`DISTANCE` 或 `CONTAINER_PRIORITY`。
- `containerTypePriority`：可调整的容器种类有序列表。

每个玩家的 `PlayerPreferences` 由 `deposit` 与 `restock` 两个 `OperationPreferences` 组成。默认配置为：空黑名单、`DISTANCE` 模式、包含全部当前容器种类的默认优先级列表。

黑名单始终优先于排序规则。黑名单中的容器种类不会进入扫描候选，即使它位于优先级列表第一位。

## 排序规则

`DISTANCE` 模式保持现有行为：所有未黑名单容器按距离平方升序、规范身份键升序处理。

`CONTAINER_PRIORITY` 模式按玩家配置的种类列表分组：优先级列表靠前的种类整体先处理；同一种类中的多个容器仍按距离平方、规范身份键稳定排序。优先级列表缺少的已知种类排在最后，并按插件默认种类顺序和距离稳定排序。未知的未来容器种类同样安全地排在末尾。

对于 `restock`，玩家背包目标槽位仍优先于容器顺序。也就是每个低编号目标槽位都会先尝试优先级最高的可用容器，稀缺来源仍分配给较低编号槽位。

末影箱的既有去重语义保持不变：排序后只保留第一个末影箱入口，确保同一玩家私有末影箱库存不会重复遍历。

## 命令与权限

新增 `autochest.config` 权限，默认值为 `true`。配置命令只允许玩家管理自己的偏好，不提供修改其他玩家数据的入口。

命令树如下：

```text
/autochest config
/autochest config <deposit|restock> mode <distance|priority>
/autochest config <deposit|restock> blacklist <add|remove|list> <container-type>
/autochest config <deposit|restock> priority <move> <container-type> <up|down>
/autochest config <deposit|restock> priority <reset|list>
```

容器种类参数为：`chest`、`trapped_chest`、`barrel`、`shulker_box`、`ender_chest`。无效操作、模式、种类或移动方向仅显示用法或错误提示，不修改内存偏好或持久化文件。

`move` 只改变相邻顺序，到达首位或末位时保持原列表并返回明确反馈。`reset` 将对应操作的优先级列表恢复为默认顺序，但不改变模式或黑名单。

配置命令不受 `deposit`、`restock` 冷却和任务锁限制。运行中的任务继续使用自己的偏好快照，下一次命令才使用修改后的设置。

## 任务快照与执行流程

在创建 `PlayerTask` 时，将针对当前操作的不可变 `OperationPreferencesSnapshot` 与现有配置快照一并保存。

扫描阶段先使用任务快照的黑名单过滤容器种类，再执行现有 WorldGuard、Towny、ChestShop 与 Slimefun Hook 检查。这样被排除的种类不会产生 Hook 调用、库存快照或后续提交工作。

异步 `CandidatePlanner` 只接收不可变容器 DTO 和偏好快照，不访问 Bukkit API、持久化服务或可变玩家对象。它在现有末影箱入口去重后，按当前操作的排序模式输出稳定容器列表。deposit 的物品快照候选资格索引必须只记录最终保留的容器。

提交阶段继续使用现有容器结构校验、Hook 重验和事务恢复。玩家偏好不绕过任何保护插件或容器实时安全验证。

## 持久化

偏好文件存放在：

```text
plugins/AutoChest/data/players/<uuid>.json
```

每个文件包含格式版本、`deposit` 偏好与 `restock` 偏好。例如：

```json
{
  "version": 1,
  "deposit": {
    "orderMode": "CONTAINER_PRIORITY",
    "blacklistedContainerTypes": ["BARREL"],
    "containerTypePriority": ["SHULKER_BOX", "CHEST", "TRAPPED_CHEST", "ENDER_CHEST", "BARREL"]
  },
  "restock": {
    "orderMode": "DISTANCE",
    "blacklistedContainerTypes": [],
    "containerTypePriority": ["ENDER_CHEST", "SHULKER_BOX", "CHEST", "TRAPPED_CHEST", "BARREL"]
  }
}
```

偏好按玩家 UUID 懒加载。首次使用、文件不存在、字段缺失、非法模式、未知容器类型或无效优先级条目均回退到对应安全默认值。损坏 JSON 记录警告后使用默认偏好，不能阻断玩家的存入、补货或配置命令。

配置更新先修改内存中的偏好对象，再提交单线程异步 JSON 写入。写入通过同目录临时文件和原子替换完成，避免服务端中断产生半截文件。插件停用时停止接受新写入并有界等待队列完成；超时写入警告日志。

持久化 IO 使用独立的单线程 executor，不复用容器异步规划线程池。异步 IO 不得访问 Bukkit 的玩家、世界、库存、方块或任务对象。

## 错误处理与边界

- 存储目录创建失败、临时文件写入失败、原子替换不支持或关闭等待超时都必须记录明确日志；内存偏好保持可用，下一次修改可再次尝试持久化。
- 每份配置在加载、更新和生成任务快照时都进行防御性复制，禁止外部集合修改影响运行中的任务。
- 黑名单与优先级只依据 `ContainerIdentity.ContainerType` 判断，不根据坐标、玩家视线或容器内物品判断。
- 17 种潜影盒均映射为 `SHULKER_BOX`，黑名单与优先级不区分颜色。
- 玩家退出、死亡、换世界、任务 token 失效与 Hook 不可用时，维持既有取消或拒绝策略。

## 测试与验收

单元测试应覆盖：

1. deposit 与 restock 偏好完全独立。
2. `DISTANCE` 模式保持当前容器顺序。
3. `CONTAINER_PRIORITY` 按种类分组排序，同种内按距离和规范键排序。
4. 黑名单在扫描候选和规划输出中均被排除。
5. 所有潜影盒颜色统一受到 `SHULKER_BOX` 偏好影响。
6. 末影箱入口去重在两种排序模式下均只保留最终排序第一的入口。
7. JSON 往返、缺失字段、非法枚举、未知种类、重复优先级条目与损坏 JSON 的安全回退。
8. 任务偏好快照在运行中配置变化后保持不变。
9. 写入临时文件与替换失败时不损坏上一份有效 JSON。

Paper `1.21.4` 测试服至少验证：存入优先潜影盒、补货优先末影箱；两个操作各自黑名单不互相影响；距离模式保持旧行为；重启服务器后 JSON 仍生效；同种容器按距离处理；以及 WorldGuard、Towny、ChestShop、Slimefun 保护仍优先于玩家偏好。
