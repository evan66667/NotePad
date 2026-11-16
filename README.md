# NotePad 彩色便签记事本 📝

一个基于 Android 原生 ContentProvider + SQLite 构建的彩色便签记事本应用，基于 Android 官方 NotePad 示例扩展开发。

## ✨ 功能特性

### 基本功能
- **笔记列表** — 两列彩色便签网格，每条显示标题、正文预览、最后修改时间
- **实时搜索** — 顶部搜索框支持按 **标题** 或 **正文内容** 模糊过滤，无结果时自动切换提示
- **便签时间戳** — 每条笔记展示 `yyyy-MM-dd HH:mm` 格式修改时间

### 扩展功能
- **彩色便签** — 8 种马卡龙色可选（柠檬黄 / 薄荷绿 / 天空蓝 / 樱花粉 / 薰衣草紫 / 蜜桃橙 / 纯白 / 深灰），长按便签可换色
- **置顶收藏** — 长按便签置顶/取消置顶，置顶便签显示金色星标并自动排到最前
- **桌面小组件** — 可添加到桌面的便签网格，点击便签打开编辑，右上角 **＋** 新建便签，App 内修改后小组件自动刷新
- **编辑器纸张同步** — 打开便签时编辑器背景自动匹配便签颜色，深灰色便签自动切换白色文字
- **分享导出** — 单条笔记可直接分享，支持"导出全部笔记"为纯文本调用系统分享
- **空状态** — 列表/搜索无结果时显示专属提示文案

## 🛠 技术架构

| 模块 | 技术 |
|------|------|
| 数据存储 | SQLite + ContentProvider |
| 列表展示 | GridView + CursorAdapter |
| 桌面小组件 | AppWidgetProvider + RemoteViewsService |
| UI 主题 | Holo Light + 自定义 drawable 背景 |

### 数据库字段

```
notes 表
├── _id            INTEGER PRIMARY KEY
├── title          TEXT              标题
├── note           TEXT              正文
├── created        INTEGER           创建时间
├── modified       INTEGER           修改时间
├── pinned         INTEGER (0/1)     是否置顶
└── color          INTEGER (0-7)     便签颜色索引
```

### 排序规则
```sql
ORDER BY pinned DESC, modified DESC
```

## 📂 项目结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/android/notepad/
│   ├── NotePad.java                # 数据契约（字段、URI、排序）
│   ├── NotePadProvider.java        # ContentProvider 实现 + 数据库升级
│   ├── NoteColors.java             # 便签色板 + 文字对比度计算
│   ├── NotesList.java              # 便签网格列表 + 搜索 + 长按菜单
│   ├── NoteEditor.java             # 稿纸风格编辑器
│   ├── NotePadWidgetProvider.java  # 桌面小组件入口
│   ├── NotePadWidgetService.java   # 小组件便签数据服务
│   └── NotesLiveFolder.java
└── res/
    ├── drawable/                   # 彩色便签卡片、搜索框、渐变背景
    ├── layout/                     # 列表、编辑器、小组件布局
    ├── menu/                       # 右键上下文菜单、选项菜单
    ├── xml/note_widget_info.xml    # 小组件配置
    └── values/                     # 颜色、样式、字符串
```

## 🚀 构建运行

### 环境要求
- Android Studio Hedgehog / Koala 或更新版本
- JDK 21（Gradle JDK）
- minSdk 14 / targetSdk 23

### 构建步骤
1. 用 Android Studio 打开项目根目录
2. 等待 Gradle 同步完成
3. 连接 Android 设备或启动模拟器（推荐 Pixel 7）
4. 点 Run 按钮，或命令行：
   ```bash
   ./gradlew installDebug
   ```

### 添加桌面小组件
1. 长按桌面空白处 → 选择 **Widgets**
2. 找到 **记事本 / 我的便签**
3. 长按预览拖到桌面任意位置

## 🖥 截图

| 便签网格 | 搜索 | 颜色选择 |
|:---:|:---:|:---:|
| 彩色两列便签 | 实时标题/正文过滤 | 8 色马卡龙色板 |

| 稿纸编辑器 | 置顶便签 | 桌面小组件 |
|:---:|:---:|:---:|
| 跟随便签颜色的稿纸背景 | 金色星标 + 自动置顶 | 点击打开编辑 / ＋ 新建 |

## 📝 使用说明

### 新建笔记
- 列表页：右上角 **＋** 按钮
- 桌面小组件：右上角 **＋**
- 快捷键：`Ctrl+N`

### 搜索笔记
- 在顶部搜索框输入关键词，自动过滤标题和正文

### 置顶 / 换色 / 删除
- 长按任意便签 → 在弹出菜单中选择对应操作

### 保存
- 编辑器在 `onPause()` 时自动保存，或点顶部 💾 按钮手动保存

## 🔧 数据库迁移

| 版本 | 变更 |
|------|------|
| v1 | 原始示例（title, note, created, modified） |
| v2 | Live Folder 扩展 |
| v3 | + `pinned` 字段（置顶） |
| v4 | + `color` 字段（便签颜色） |

升级使用 `ALTER TABLE` 方式，保留旧数据不丢失。

## 📜 License

基于 Android Open Source Project NotePad 示例改造，遵循 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)。
