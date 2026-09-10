# Sparrow Sync 🐦

**English** | [简体中文](README_CN.md)

![Code Size](https://img.shields.io/github/languages/code-size/NanamoServer/sparrow-sync)
![bStats Servers](https://img.shields.io/bstats/servers/33952)
![bStats Players](https://img.shields.io/bstats/players/33952)
[![License](https://img.shields.io/badge/license-GPLv3-blue)](LICENSE)

## 📌 Overview

Sparrow Sync is a **Paper and Folia plugin** for synchronizing **player data across Minecraft servers**. It combines Redis-based session coordination with persistent snapshots, helping server administrators keep player progress connected and recover earlier saves. 🐦

### 🔥 Key Features

- **🔄 Player Data Synchronization**: Synchronize inventories, ender chests, experience, health, hunger, potion effects, advancements, statistics, attributes, location, and more. Choose which data types participate through configuration.
- **⚡ Asynchronous Saving**: Capture player state on the appropriate server thread, then encode and store snapshots asynchronously. Tasks for the same player are processed in order.
- **🗄️ Flexible Storage**: Choose **MongoDB**, **MySQL**, or **PostgreSQL** for persistent storage, with **Redis** handling cross-server coordination.
- **📦 Compressed Snapshots**: Store player data using Sparrow NBT, with **Zstd**, Deflate, and uncompressed formats available.
- **🖥️ Snapshot Management GUI**: Browse saved snapshots, inspect their contents, restore earlier data, pin records, and export snapshots through an in-game menu.
- **🗺️ Map Synchronization**: Share map data between servers, with configurable synchronization modes and interaction controls.
- **🔧 Recovery Tools**: Inspect local exception archives and use snapshot import, export, and bulk archive commands for data management.

---

## 🔧 Building the Project

### 💻 Command Line

1. Install **JDK 21**. The Gradle toolchain is configured for the **JetBrains** vendor.
2. Open a terminal and navigate to the project directory.
3. Run:

   ```sh
   ./gradlew build
   ```

   On Windows PowerShell:

   ```powershell
   .\gradlew.bat build
   ```

4. The generated plugin can be found at **`target/sparrow-sync-<version>.jar`**.

### 🛠️ Using an IDE

1. Import the project as a **Gradle project**.
2. Configure the Java 21 toolchain and execute the **Gradle build** action.
3. Locate the plugin JAR in the **`/target`** folder.

---

## 🚀 Getting Started

Install the plugin on each participating **Paper or Folia** server and configure a shared **Redis** service and **database** for the servers that should synchronize player data.

The plugin generates its configuration files in `plugins/SparrowSync/`:

| File | Purpose |
| --- | --- |
| `config.yml` | Redis, database, synchronization, and snapshot settings |
| `server.yml` | The unique identity of this server within the synchronization cluster |
| `commands.yml` | Command permissions, usages, and enabled states |

Set a **different, non-empty `server-id`** in `server.yml` for every participating server. The plugin shuts down the server when this value is empty, including on an unconfigured first startup. Configure the generated files before restarting.

Each configuration file maintains its own version. Changes to `commands.yml` require a restart to apply.

### 🎮 Common Commands

| Command | Purpose |
| --- | --- |
| `/sparrow-sync status` | View synchronization system status |
| `/sparrow-sync gui <player>` | Open a player's snapshot management menu |
| `/sparrow-sync data migrate <source>` | Migrate data from a supported source plugin (`husksync` or `invsync`) |
| `/sparrow-sync snapshot list <player>` | List a player's saved snapshots |
| `/sparrow-sync snapshot view <player> <id>` | View the contents of a saved snapshot |
| `/sparrow-sync reload` | Reload configuration and translations |

Command permissions and usages can be customized in **`commands.yml`**.

---

## 🤝 Contributing

### 🌍 Translations

Two kinds of text can be translated: the messages players see, and the comments written into the generated configuration files.

#### 📄 Language files

1. Clone the repository.
2. Use an existing language file as a reference and add your translation to:

   ```text
   common-files/src/main/resources/translations/
   ```

3. Name the file after the locale, in lowercase `language_country` form, for example `zh_cn.yml` for `zh_CN`; a language-only name such as `en.yml` works when the language has no regional variant.
4. Preserve translation keys, argument placeholders, and MiniMessage formatting.

#### 📝 Configuration comments

The comments in the generated `config.yml` and `server.yml` come from `@Comment` annotations on the configuration classes in `core/src/main/java/net/momirealms/sparrow/sync/plugin/configuration/`. Every option declares an English fallback comment together with its localized variants:

```java
@Comment("Enables or disables metrics collection via BStats")
@Comment(lang = "zh-CN", value = "是否启用 BStats 统计数据收集")
boolean metrics = true;
```

To translate them, add another `@Comment` carrying your own `lang` tag:

```java
@Comment(lang = "pt-BR", value = "Ativa ou desativa a coleta de métricas via BStats")
```

- Use a BCP-47 language tag such as `zh-CN`, `pt-BR` or `en-US`.
- Multi-line comments take an array: `value = {"First line", "Second line"}`.
- The `@Comment` without a `lang` is the fallback used when nothing matches; please keep it in place.
- Variants are matched in the order full tag (`zh-CN`) → language with script (`zh-Hans`) → language (`zh`) → fallback, so a language-only tag such as `pt` covers every regional variant.
- The variant written into a file follows the server's default locale, and comments already present in a generated file are preserved as they are.

#### 📬 Submitting your changes

Submit a **pull request** with your changes for review. Contributions are welcome! 💖

---

## 📜 License

Sparrow Sync is licensed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE) for details.
