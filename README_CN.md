# Sparrow Sync 🐦

[English](README.md) | **简体中文**

![Code Size](https://img.shields.io/github/languages/code-size/NanamoServer/sparrow-sync)
![bStats Servers](https://img.shields.io/bstats/servers/33952)
![bStats Players](https://img.shields.io/bstats/players/33952)
[![License](https://img.shields.io/badge/license-GPLv3-blue)](LICENSE)

## 📌 项目简介

Sparrow Sync 是一款用于**跨服务器同步玩家数据**的 **Paper / Folia 插件**。它通过 Redis 协调玩家会话，并将玩家数据保存为持久化快照，让玩家在不同服务器之间延续游戏进度，也方便管理员恢复历史存档。🐦

### 🔥 核心功能

- **🔄 玩家数据同步**：支持背包、末影箱、经验、生命值、饥饿值、药水效果、进度、统计、属性、位置等数据，可在配置中选择需要同步的数据类型。
- **⚡ 异步保存**：在对应的服务器线程上采集玩家状态，再异步编码并保存快照。同一玩家的任务按顺序处理。
- **🗄️ 多种存储后端**：支持使用 **MongoDB**、**MySQL** 或 **PostgreSQL** 持久化数据，通过 **Redis** 协调跨服操作。
- **📦 快照压缩**：使用 Sparrow NBT 保存玩家数据，支持 **Zstd**、Deflate 和不压缩三种格式。
- **🖥️ 快照管理菜单**：在游戏内浏览快照、查看内容、恢复历史数据、固定记录和导出快照。
- **🗺️ 地图同步**：在服务器之间共享地图数据，支持配置同步模式和交互限制。
- **🔧 数据恢复工具**：查看本地异常档案，通过快照导入、导出和批量归档命令管理数据。

---

## 🔧 构建项目

### 💻 命令行

1. 安装 **JDK 21**。项目的 Gradle 工具链指定使用 **JetBrains** 发行版。
2. 打开终端，进入项目目录。
3. 执行：

   ```sh
   ./gradlew build
   ```

   Windows PowerShell 用户执行：

   ```powershell
   .\gradlew.bat build
   ```

4. 构建完成后，插件位于 **`target/sparrow-sync-<version>.jar`**。

### 🛠️ 使用 IDE

1. 将项目作为 **Gradle 项目**导入 IDE。
2. 配置 Java 21 工具链，执行 **Gradle build** 任务。
3. 在 **`/target`** 目录中找到插件 JAR。

---

## 🚀 开始使用

在每台参与同步的 **Paper 或 Folia** 服务器上安装插件，并为需要同步玩家数据的服务器配置共用的 **Redis** 服务和**数据库**。

插件会在 `plugins/SparrowSync/` 中生成以下配置文件：

| 文件 | 用途 |
| --- | --- |
| `config.yml` | Redis、数据库、数据同步和快照设置 |
| `server.yml` | 当前服务器在同步集群中的唯一标识 |
| `commands.yml` | 命令权限、用法和启用状态 |

请在每台服务器的 `server.yml` 中设置**各不相同且非空的 `server-id`**。该值为空时，插件会关闭服务器，首次启动且尚未配置时也会如此。请完成生成文件中的配置后再重新启动。

每份配置文件独立维护自己的版本号。修改 `commands.yml` 后需要重启服务器才能生效。

### 🎮 常用命令

| 命令 | 用途 |
| --- | --- |
| `/sparrow-sync status` | 查看同步系统状态 |
| `/sparrow-sync gui <player>` | 打开指定玩家的快照管理菜单 |
| `/sparrow-sync snapshot capture <player>` | 为指定玩家创建新快照 |
| `/sparrow-sync snapshot list <player>` | 列出指定玩家的已保存快照 |
| `/sparrow-sync snapshot restore <player> <id>` | 恢复指定快照 |
| `/sparrow-sync reload` | 重新加载配置和翻译 |

命令权限和用法可以在 **`commands.yml`** 中自定义。

---

## 🤝 参与贡献

### 🌍 翻译

1. 克隆仓库。
2. 参考现有语言文件，在以下目录中添加你的翻译：

   ```text
   common-files/src/main/resources/translations/
   ```

3. 保留翻译键、参数占位符和 MiniMessage 格式。
4. 提交 **Pull Request** 供我们审核。欢迎参与贡献！💖

---

## 📜 许可证

Sparrow Sync 采用 **GNU General Public License v3.0** 许可证。详情请参阅 [LICENSE](LICENSE)。
