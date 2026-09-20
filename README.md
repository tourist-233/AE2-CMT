# AE2 CMT — 元件管理终端

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62b47a.svg)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.230%2B-e8a33d.svg)](https://neoforged.net/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**AE2 CMT** 是一个 Applied Energistics 2 附属模组：不用跑到驱动器面前，就能远程查看并整理整张 ME 网络里所有 ME 驱动器中的存储元件。

终端有两种形态，功能一致：

| 形态 | 物品 ID | 说明 |
| --- | --- | --- |
| **无线元件管理终端** | `cmt:wireless_component_management_terminal` | 手持式，需绑定无线访问点，消耗 AE 能量（可插能源卡扩容，4 个升级槽） |
| **ME元件管理终端** | `cmt:component_management_terminal` | 线缆部件，占用一个频道，权限跟随 AE2 网络安全机制 |

---

## 功能

* **远程管理**：列出所连 ME 网络内所有 ME 驱动器，以及每个槽位中的元件与占用率进度条
* **直接操作**：插入、取出、拖动交换元件，操作直接作用于真实驱动器，即时被网络识别或断开
* **分页与分组**：按内容行滚动，窗口高度随内容伸缩；驱动器按「名称行 + 元件行」分组显示
* **网络总览**：物品栏两侧的竖向凹槽显示全网存储类型与存储容量的占用情况
* **无限存储识别**：容量真无限（无限容量元件、NeoECO 无限模式）让进度条拉满变紫；只是资源无限而容量正常的元件只染自己的容量条，不污染全网统计
* **通用终端集成**：安装 AE2WTlib 后可出现在其通用终端列表中，并带一个打开快捷键

---

## 支持的驱动器

* **AE2 本体**：ME 驱动器（10 槽）—— 完整支持
* **NeoECO AE Extension**：**LD 存储矩阵驱动器**（多方块，按集群合并为一条记录）—— 支持
  * 可选模组，未安装时无任何影响；通过反射桥接，无编译期依赖
  * CD 晶阵驱动器（计算用）不显示
* 存储总线、元件工作台等其它元件宿主不在管理范围内

---

## 依赖

必需：Minecraft **1.21.1**、NeoForge **21.1.230+**（AE2 本身要求 21.1.169 以上）、Applied Energistics 2 **19.2.17+**

可选：[AE2WTlib](https://github.com/Mari023/AE2WirelessTerminalLibrary) **19.5.0+** —— 提供通用终端集成（其 API 已通过 jarJar 内嵌，不装也能正常使用）

---

## 安装与使用

放入 `mods/` 目录即可，两个终端物品都出现在 AE2 的创造模式物品页。

* **无线版**：先绑定无线访问点（与 AE2 无线终端方式相同），再手持右键打开；未绑定、超范围或没电时界面会提示原因
* **部件版**：贴在 ME 线缆上（占一个频道），右键打开

---

## 合成

均为竖排配方（自上而下）：

| 产物 | 材料 |
| --- | --- |
| 元件管理终端 | 照明面板 / 工程处理器 / ME 驱动器 |
| 无线元件管理终端 | 无线接收器 / 元件管理终端 / 致密能源元件 |

---

## 构建

```bash
./gradlew build     # 产物在 build/libs/cmt-<version>.jar
```

需要 JDK 21。

---

## 已知限制

* **排序 / 搜索暂无界面入口**：服务端逻辑已具备，客户端控件尚未提供
* **通用终端集成不完整**：从 AE2WTlib 通用终端切入时缺少终端选择面板与热键支持，看不到奇点槽（独立使用物品不受影响）
* **扫描开销恒定**：终端打开期间会周期性重扫网络并发送快照，即使内容没有变化

---

## 许可

MIT，见 [LICENSE](LICENSE)。本项目是 [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) 的附属模组，与 AE2 官方团队无关。
