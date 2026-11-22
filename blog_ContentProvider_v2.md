# 从 ContentProvider 到桌面小组件：我的 Android 便签项目改造实录

这学期做 Android 课程项目，我选了经典的 NotePad 示例作为基础，目标是把它改造成一个"能用的彩色便签 App"：支持搜索、置顶、换色，最后还想把便签贴到桌面上。

做完之后我最大的感受是：**ContentProvider 这个词在课本上只有半页，但真动手改起来，坑全在它身上**。这篇不是源码分析文，而是我这一路踩坑的记录，附上我的代码和排查过程，希望能帮到同样在改 NotePad 的同学。

<!-- 截图1：把模拟器「便签列表页」的截图拖到这里替换本行 -->

---

## 一、项目背景：为什么我没有换 Room

现在新项目基本都用 Room + Flow，但这次我刻意保留原始的 ContentProvider + SQLite 方案，原因有两个：

- 课程要求理解四大组件，ContentProvider 是绕不开的；
- 官方 NotePad 的 Provider 结构很干净，适合在它上面加功能，而不是推倒重来。

项目结构没什么特别的：

```
com.example.android.notepad
├── NotePad.java            // 契约类：表名、字段、URI、排序
├── NotePadProvider.java    // 继承 ContentProvider，负责增删改查
├── NotesList.java          // 列表页
├── NoteEditor.java         // 编辑页
├── NotePadWidgetProvider.java  // 桌面小组件
└── NotePadWidgetService.java   // 小组件数据适配
```

契约类里我改了一行很关键的排序：

```java
public static final String DEFAULT_SORT_ORDER = "pinned DESC, modified DESC";
```

置顶的排前面，其余按修改时间倒序。就这一行，列表和小组件都跟着生效了，不用在两个地方各写一遍排序。

---

## 二、坑一：加字段之后，旧笔记全没了

第一个功能是"置顶"，需要给表加一个 `pinned` 字段。

一开始我图省事，直接在 `onCreate()` 里改了建表语句，然后把 `DATABASE_VERSION` 从 1 改成 2。结果重装运行，**之前写的笔记全消失了**。

原因很直白：`onCreate()` 只在数据库文件不存在时执行，版本号变了走的是 `onUpgrade()`，而我没处理，旧表结构对不上新代码，查询直接崩。

最后的写法是：

```java
@Override
public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2) {
        db.execSQL("ALTER TABLE notes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");
    }
    if (oldVersion < 3) {
        db.execSQL("ALTER TABLE notes ADD COLUMN color INTEGER NOT NULL DEFAULT " + DEFAULT_COLOR);
    }
}
```

这里有两个细节是我调试时才意识到的：

1. `ALTER TABLE` 加的非空字段**必须给默认值**，否则旧行没法填；
2. 升级逻辑要按版本号逐级判断，不能只写 `if (oldVersion == 1)`，不然用户跨版本升级（v1 → v4）就漏了中间步骤。

改完我把 App 卸载重装、再覆盖安装各测了一遍，确认数据不会丢。

---

## 三、坑二：数据改了，列表就是不刷新

这是花时间最久的一个问题。

我在编辑页保存后返回列表，发现列表还是旧内容，必须杀掉 App 重进才更新。一开始我以为是 Adapter 没调 `notifyDataSetChanged()`，加了之后还是不行。

后来在 `NotePadProvider` 的 `query()` 里找到了缺失的一行：

```java
Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy);
// 关键：把 Cursor 和这个 URI 绑定起来
c.setNotificationUri(getContext().getContentResolver(), uri);
return c;
```

同时在 insert / update / delete 之后：

```java
getContext().getContentResolver().notifyChange(uri, null);
```

这两步缺一不可：

- `setNotificationUri()` 是告诉系统"这个 Cursor 关心这个 URI"；
- `notifyChange()` 是告诉系统"这个 URI 的数据变了，通知所有观察者"。

我一开始只写了 `notifyChange()`，没有 `setNotificationUri()`，所以系统根本不知道该通知谁，改动自然石沉大海。

列表页那边用的是 `CursorLoader`，它内部会自动注册 `ContentObserver`，所以 `onLoadFinished()` 会被重新回调，我只需要 `swapCursor()` 就行：

```java
@Override
public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
    mAdapter.swapCursor(data);
}
```

**经验**：Provider 的读写方法里，`notifyChange` 和 `setNotificationUri` 要成对出现，这是最容易漏的。

---

## 四、坑三：搜索把置顶顺序弄丢了

搜索功能本身不难，我用 `LIKE` 同时匹配标题和正文：

```java
String like = "%" + keyword + "%";
String selection = NotePad.Notes.TITLE + " LIKE ? OR " + NotePad.Notes.NOTE + " LIKE ?";
Cursor c = resolver.query(
        NotePad.Notes.CONTENT_URI,
        PROJECTION,
        selection,
        new String[]{like, like},
        NotePad.Notes.DEFAULT_SORT_ORDER);
```

但第一版我手贱把 `sortOrder` 传了 `null`，结果搜索结果里的置顶便签跑到了中间。

原因是 NotePad 示例的 `query()` 里对 `null` 做了兜底：

```java
if (TextUtils.isEmpty(orderBy)) {
    orderBy = NotePad.Notes.DEFAULT_SORT_ORDER;
}
```

所以传 `null` 其实不会崩，只是走了默认排序——而这个"默认"是我在契约类里定义的。对于想要固定顺序的场景，**显式传排序字段**更稳，不要依赖兜底逻辑。

---

## 五、坑四：桌面小组件读不到最新数据

把便签贴到桌面，用的是 `AppWidgetProvider` + `RemoteViewsService`。

小组件本身不跑在 App 进程里，它通过 `RemoteViews` 把界面交给桌面进程渲染，所以**不能直接 `findViewById` 或者放 ListView**，只能用 `RemoteViewsService` 提供 `RemoteViewsFactory`。

我的 `RemoteViewsFactory.onDataSetChanged()` 里重新查询一次数据，然后：

```java
@Override
public void onDataSetChanged() {
    if (mCursor != null) mCursor.close();
    mCursor = mContext.getContentResolver().query(
            NotePad.Notes.CONTENT_URI, PROJECTION, null, null,
            NotePad.Notes.DEFAULT_SORT_ORDER);
}
```

数据刷新靠两件事：

1. 在 App 里增删改之后调用 `AppWidgetManager.notifyAppWidgetViewDataChanged()`；
2. 重写 `onUpdate()`，在桌面触发更新时把数据重新绑一次。

我踩的坑是：只做了第 1 步，结果手机重启或者桌面重绘之后，小组件又变回旧数据。补上 `onUpdate()` 里的绑定之后才彻底稳定。

<!-- 截图2：把模拟器「桌面小组件」的截图拖到这里替换本行 -->

---

## 六、顺手复习：一次 query 到底走了什么

改到这里，我才真正把上课讲的 Binder 那套对上了号。简单说：

1. App 调 `ContentResolver.query()`，先通过 AMS 拿到 Provider 的 `IContentProvider` 代理；
2. 真正跨进程的一次 Binder 调用发生在 `IContentProvider.query()`；
3. Provider 进程里执行 `SQLiteDatabase.query()`，结果塞进 `CursorWindow`；
4. `CursorWindow` 用的是匿名共享内存，Client 进程直接读，所以数据量大也不会卡在序列化上。

这解释了为什么我查几百条便签也不卡：**数据不是"拷"过来的，是共享同一块内存**。

`notifyChange` 也同理：Provider 进程调用后，AMS 里的 `ContentService` 遍历注册表，再 Binder 回调到 Client 的 `ContentObserver`。我上一节遇到的"列表不刷新"，本质就是这条链路的前半段断了。

---

## 七、其他踩过的坑（环境相关）

| 问题 | 现象 | 解决 |
|---|---|---|
| `jcenter()` 失效 | Gradle 同步报 `Could not resolve` | 换成 `mavenCentral()` |
| `buildToolsVersion 23.0.1` | 新版 AGP 直接报错 | 删掉该行，用 AGP 默认 |
| Gradle JDK 不匹配 | Sync 失败，提示 JDK 版本 | Settings 里改成 jbr-21 |
| 模拟器没有返回键 | 编辑页不知道怎么退回列表 | 手势导航从左边缘右滑，或按 Esc |

这些都是环境问题，搜一下就有答案，但第一次遇到确实会卡住半天。

---

## 八、总结

这次改造下来，我对 ContentProvider 的理解从"背概念"变成了"知道它什么时候会坑我"：

- 数据库升级一定要写 `onUpgrade()`，而且按版本逐级判断；
- `notifyChange` 和 `setNotificationUri` 是配套的，缺一个列表就不刷新；
- 排序统一放在契约类的 `DEFAULT_SORT_ORDER`，列表、搜索、小组件都复用；
- 小组件的 `onDataSetChanged()` + `onUpdate()` 都要处理，否则重启后数据会回退。

项目代码我放在 GitHub 上了，感兴趣可以对照看：https://github.com/evan66667/NotePad

如果这篇文章对你改 NotePad 有一点帮助，欢迎点赞收藏。有问题也可以在评论区问我，我看到会回。
