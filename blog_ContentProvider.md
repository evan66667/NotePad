# 深入理解 Android ContentProvider：从 Binder 到 Cursor 的完整链路

> ContentProvider 是 Android 四大组件中最被低估的一个。Activity 讲的是 UI，Service 讲的是后台，BroadcastReceiver 讲的是通信，而 ContentProvider 讲的是**跨进程数据共享**。它背后牵涉 Binder IPC、SQLite、Cursor 窗口、观察者模式等多个 Android 核心机制。本文将从源码层面完整拆解一次 `query()` 调用的全链路。

---

## 一、为什么需要 ContentProvider？

Android 的沙箱机制决定了每个 App 进程独立、互不干扰。如果没有 ContentProvider：

- App A 想读 App B 的通讯录 → 必须 root
- App A 想写 App B 的日程 → 必须 root
- 系统设置、媒体库等公共数据 → 没有统一访问入口

ContentProvider 就是 Google 给出的"**受限的数据共享标准**"：

```
┌─────────────┐   query()    ┌─────────────────┐   SQL    ┌──────────┐
│  Client App │ ──────────▶ │ ContentProvider │ ───────▶│ SQLite DB │
│ (Any Proc)  │ ◀────────── │    (Provider)   │ ◀─────── │          │
└─────────────┘   Cursor     └─────────────────┘          └──────────┘
       ▲                                                           ▲
       │              Cross-Process IPC (Binder)                   │
       └───────────────────────────────────────────────────────────┘
```

### ContentProvider 框架包含三层：

| 层 | 职责 |
|---|---|
| **Client 层** | 通过 `ContentResolver` 发起请求（query / insert / update / delete） |
| **Bridge 层** | Binder IPC 代理，把调用跨进程转发 |
| **Provider 层** | 真正的业务实现，通常操作 SQLite |

---

## 二、Binder 通信：为什么一次 query() 不只是函数调用？

### 2.1 跨进程调用的本质

ContentProvider 通常运行在**另一个进程**（系统进程或 Provider App 的进程）。Client 进程调用 `ContentResolver.query()` 时，不能直接拿到 Provider 对象的引用——它们在不同的内存空间。

这时候就需要 **Binder**：Android 基于 Linux mmap + 内核驱动实现的高性能 IPC 机制。

```
Client Process                 System Process             Provider Process
┌──────────┐                 ┌──────────┐               ┌──────────┐
│Content   │   Binder Driver │ Activity  │  Binder Driver│Content   │
│Resolver  │ ──────────────▶ │  Manager │ ──────────────▶ │Provider  │
│          │ ◀────────────── │          │ ◀────────────── │          │
└──────────┘                 └──────────┘               └──────────┘
     │                             │                          │
     │ ContentService (Proxy)       │                          │ Provider Instance
     │                             │                          │
     └───────── IContentProvider.Stub ────────────────────────┘
```

### 2.2 Binder 代理链源码分析

当你调用 `ContentResolver.query()` 时，实际经历了三层 Binder 代理：

```java
// ContentResolver.java (简化)
public final Cursor query(Uri uri, ...) {
    // 1. 获取 ContentService 代理
    IContentProvider provider = acquireProvider(uri);
    // 2. 通过代理调用 Provider.query()
    Cursor cursor = provider.query(uri, projection, selection, sortOrder);
    return cursor;
}
```

`acquireProvider()` 内部：

```java
private IContentProvider acquireProvider(Uri uri) {
    // 先查本地缓存
    IContentProvider provider = mProviderMap.get(uri.getAuthority());
    if (provider != null) return provider;

    // 没有缓存 → 走 AMS（ActivityManagerService）
    return acquireProviderInternal(uri);
}
```

`acquireProviderInternal()` 是第一次关键 Binder 调用：

```java
private IContentProvider acquireProviderInternal(Uri uri) {
    // 通过 ActivityManagerService 查找 Provider
    ProviderInfo info = ActivityManager.getService()
        .getContentProviderInfo(mContext.getPackageName(), uri, false);

    // 通过 installProvider 安装到本地
    IContentProvider provider = installProvider(info);
    return provider;
}
```

### 2.3 AMS 如何找到目标 Provider？

```java
// ActivityManagerService.java
public ContentProviderHolder getContentProviderInfo(String name, ...) {
    // 1. 从已注册 Provider 列表查找
    ContentProviderRecord record = mProvidersByName.get(authority);
    if (record == null) {
        // 2. 还没启动 → 启动 Provider 所在进程
        record = startProviderLocked(...);
    }
    // 3. 返回 ContentProviderHolder（包含 IContentProvider 代理）
    return new ContentProviderHolder(record.provider);
}
```

这里的 `record.provider` 就是**第二次 Binder 调用**的结果——Provider 进程启动后，会通过 Binder 把自己的 `IContentProvider.Stub` 注册到 AMS。

---

## 三、query() 全链路源码拆解

现在我们可以完整追踪一次 query 的调用链了：

### 3.1 调用流程图

```
ContentResolver.query(uri, projection, selection, sortOrder)
  │
  ├── [第一次] acquireProvider() → ContentService.call → AMS.getContentProviderInfo()
  │         └── 可能触发 Provider 进程启动
  │
  ├── installProvider()
  │         └── 拿到 IContentProvider.Stub.Proxy 对象（Binder 代理）
  │
  ├── [第二次] IContentProvider.query(uri, ...) → Binder 驱动 → Provider.onTransact()
  │         └── 跨进程边界！
  │
  └── ContentProvider.query(uri, ...) → 开发者实现的 query()
            └── SQLiteDatabase.query() / rawQuery()
            └── 返回 CursorWindow
            └── CursorWindow 通过 Binder 传回 Client
```

### 3.2 Binder 数据传输：CursorWindow

Provider 进程不能直接把一个 `Cursor` 对象引用交给 Client——它们在不同内存。Android 的解决方案是 **CursorWindow**：

```java
// ContentProvider.java
public Cursor query(Uri uri, String[] projection, String selection, ...) {
    Cursor cursor = // ... 开发者自己的实现，通常是 SQLiteCursor
    // 返回前会被包装成 CursorWindow
    return cursor;
}
```

Client 进程收到的不是原始 Cursor，而是 `CursorToBulkCursorAdaptor`：

```java
// ContentResolver.java
public final Cursor query(Uri uri, ...) {
    IContentProvider provider = acquireProvider(uri);
    // 这里 provider.query() 返回的是 CursorWindow（通过 Binder 传递）
    Cursor cursor = provider.query(uri, projection, ...);
    // 包装成 BulkCursorToCursorAdaptor，让外部像用普通 Cursor 一样用
    return new BulkCursorToCursorAdaptor(cursor);
}
```

### 3.3 CursorWindow 原理

CursorWindow 使用 **共享内存（ashmem）** 存储查询结果：

```
┌────────────┐                    ┌────────────┐
│ Provider   │                    │ Client     │
│  Process   │                    │  Process   │
│            │  Binder Driver     │            │
│ Write Data │ ──────────────────▶ │ Read Data  │
│  to Window │   Shared Memory    │  to Window │
└────────────┘                    └────────────┘
       │                                │
       └────────  Same Physical RAM ────┘
```

```java
// CursorWindow.java
public class CursorWindow {
    private final long mWindowPtr;  // native 层的 ashmem 指针

    public boolean moveToFirst() {
        // 直接操作共享内存中的数据，没有跨进程拷贝！
        return native_moveToFirst(mWindowPtr);
    }
}
```

**为什么 CursorWindow 重要？**

> 一次 query 可能返回几千条数据。如果用普通 Binder 传输，会经历：
> 
> `Provider 进程 → Binder 驱动拷贝 → Client 进程拷贝 → 反序列化`
> 
> 使用 ashmem 后：
> 
> `Provider 进程写 → Client 进程直接读（同一块物理页）`
> 
> 这就是为什么 ContentProvider 可以高效传输大量数据的原因。

### 3.4 Cursor 的懒加载机制

Cursor 并不是 query 时就把所有数据都加载进来。它有一个窗口大小限制（默认 2MB），超出时会触发 `fillWindow()`：

```java
// SQLiteCursor.java
public boolean onMove(int oldPos, int newPos) {
    // 移动到窗口外 → 重新 fill
    if (newPos < mWindow.mStartPos || newPos >= mWindow.mStartPos + mWindow.getNumRows()) {
        fillWindow(newPos);
    }
    return true;
}
```

---

## 四、notifyChange：数据库变了，UI 怎么自动刷新？

### 4.1 观察者模式的经典应用

```java
// Provider 进程：数据写入后
getContentResolver().notifyChange(NotePad.Notes.CONTENT_URI, null);

// Client 进程：监听 URI 变化
getApplicationContext().getContentResolver().registerContentObserver(
    NotePad.Notes.CONTENT_URI,
    true,  // notifyForDescendants
    new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            // 收到通知，刷新 Cursor
            mAdapter.notifyDataSetChanged();
        }
    }
);
```

### 4.2 notifyChange 全链路

```
Provider Process                          AMS Process                Client Process
┌──────────┐                             ┌──────────┐               ┌──────────┐
│notifyChg │   Binder                    │ Content   │   Binder        │ onChange  │
│          │ ──────────────────────────▶ │ Observer  │ ──────────────▶ │          │
│          │                             │ Registry  │                 │          │
└──────────┘                             └──────────┘               └──────────┘
     │                                         │                          │
     │  1. IContentService.notifyChange()     │                          │
     │                                         │  3. Iterate registry      │
     │                                         │    find all observers     │
     │                                         │    of this URI / parent   │
     │                                         │                          │
     │                                         │  4. Call IContentObserver │
     │                                         │    .onChange() for each   │
```

源码追踪：

```java
// ContentResolver.java
public void notifyChange(Uri uri, ContentObserver observer) {
    // 调用 IContentService.notifyChange (Binder 跨进程)
    getContentService().notifyChange(
        uri, observer == null ? null : observer.getContentObserver(),
        observer != null && observer.deliverSelfNotifications());
}
```

```java
// ContentService.java (AMS 进程内)
public void notifyChange(Uri uri, IContentObserver observer, ...) {
    // 查找注册了该 URI 的所有 ContentObserver
    ArrayList<ContentObserverRecord> records = mContentObservers.get(uri);
    for (ContentObserverRecord record : records) {
        // 对每个 Observer 发送 onChange (再一次 Binder 跨进程)
        record.mObserver.onChange(uri);
    }
}
```

### 4.3 实战：让 CursorAdapter 自动刷新

```java
// NotesList.java
private void setupList() {
    // LoaderManager 自动帮你做了 Observer 注册
    // onLoadFinished 会在数据变化时自动回调
    getLoaderManager().initLoader(0, null, new LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            return new CursorLoader(NotesList.this,
                NotePad.Notes.CONTENT_URI,
                PROJECTION, null, null, NotePad.Notes.DEFAULT_SORT_ORDER);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            // 数据变了自动调这里
            mAdapter.swapCursor(data);
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            mAdapter.swapCursor(null);
        }
    });
}
```

**Loader 内部帮你做了：**
1. 启动查询线程
2. 注册 ContentObserver
3. 收到 onChange 后在后台重新查询
4. 在主线程回调 onLoadFinished

---

## 五、一次完整 query 的时序图

把所有步骤串起来：

```
Client Proc (UI Thread)   Binder Driver            AMS Proc                 Provider Proc
        │                     │                       │                          │
        │ ContentResolver.query()                     │                          │
        │ ─────────────────▶ │  IContentService.query │                          │
        │                     │ ────────────────────▶ │                          │
        │                     │                       │ ActivityManagerService    │
        │                     │                       │ .getContentProviderInfo() │
        │                     │                       │                          │
        │                     │                       │  (If provider not started)│
        │                     │                       │  startProviderLocked()   │
        │                     │                       │ ──────────────────────▶  │
        │                     │                       │                          │ ContentProvider
        │                     │                       │                          │ .attachInfo()
        │                     │                       │                          │ .onCreate()
        │                     │                       │  ◀────────────────────── │
        │                     │                       │  Return IContentProvider   │
        │                     │  ◀──────────────────── │                          │
        │ IContentProvider.query()                    │                          │
        │ ─────────────────▶ │  IContentProvider.Stub│                          │
        │                     │ ─────────────────────────────────────────────────▶│
        │                     │                       │                          │ Provider.query()
        │                     │                       │                          │ SQLiteDatabase.query()
        │                     │                       │                          │ Build CursorWindow(ashmem)
        │                     │                       │                          │
        │                     │ ◀───────────────────────────────────────────────────│
        │                     │  Return CursorWindow  │                          │
        │  Receive CursorWindow                      │                          │
        │  Wrap to BulkCursor                        │                          │
        │  Expose as Cursor                          │                          │
        │                     │                       │                          │
        ▼                     ▼                       ▼                          ▼
```

---

## 六、实战：自己实现一个最小 ContentProvider

```java
public class SimpleNoteProvider extends ContentProvider {

    private SQLiteDatabase mDb;

    @Override
    public boolean onCreate() {
        // 在 Provider 进程启动时调用
        SQLiteOpenHelper helper = new SQLiteOpenHelper(getContext(), "notes.db", null, 1) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE notes (_id INTEGER PRIMARY KEY, title TEXT, content TEXT)");
            }
            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
        };
        mDb = helper.getWritableDatabase();
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        // 1. 开发者自己的查询逻辑
        Cursor cursor = mDb.query("notes", projection, selection,
            selectionArgs, null, null, sortOrder);

        // 2. 关键！注册通知 URI
        //    这样 cursor.close() 时会自动 notifyChange
        cursor.setNotificationUri(getContext().getContentResolver(), uri);

        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        long id = mDb.insert("notes", null, values);
        Uri newUri = ContentUris.withAppendedId(uri, id);

        // 3. 关键！通知观察者数据变了
        getContext().getContentResolver().notifyChange(uri, null);

        return newUri;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        int count = mDb.update("notes", values, selection, selectionArgs);
        if (count > 0) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int count = mDb.delete("notes", selection, selectionArgs);
        if (count > 0) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.example.note";
    }
}
```

### 清单文件注册

```xml
<provider
    android:name=".SimpleNoteProvider"
    android:authorities="com.example.notes"
    android:exported="true"
    android:readPermission="android.permission.READ_EXTERNAL_STORAGE"
    android:writePermission="android.permission.WRITE_EXTERNAL_STORAGE" />
```

### Client 端使用

```java
// 查询
Cursor cursor = getContentResolver().query(
    Uri.parse("content://com.example.notes/notes"),
    new String[] {"_id", "title", "content"},
    null, null, "_id DESC"
);

// 插入
ContentValues values = new ContentValues();
values.put("title", "Hello");
values.put("content", "World");
Uri newUri = getContentResolver().insert(
    Uri.parse("content://com.example.notes/notes"), values);

// 监听变化
getApplicationContext().getContentResolver().registerContentObserver(
    Uri.parse("content://com.example.notes/notes"),
    true,
    new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            // 数据变了，刷新 UI
        }
    }
);
```

---

## 七、常见面试问题

### Q1. ContentProvider 和直接用 SQLiteDatabase 有什么区别？
| 特性 | ContentProvider | SQLiteDatabase |
|------|-----------------|----------------|
| 跨进程 | ✅ 支持 | ❌ 仅限本进程 |
| 观察者 | ✅ notifyChange | ❌ 需自己实现 |
| URI 访问 | ✅ 结构化 URI | ❌ 只有路径 |
| 权限 | ✅ AndroidManifest 声明 | ❌ 无 |
| 事务 | ✅ 支持 | ✅ 支持 |

### Q2. 为什么 CursorWindow 有大小限制？
CursorWindow 默认 2MB。如果查询结果超过窗口大小，需要分段加载（moveToNext + fillWindow）。直接把几千条数据一次性塞进 Binder 会导致 **TransactionTooLargeException**。

### Q3. ContentObserver 的 onChange 回调在哪个线程？
取决于注册时传入的 Handler。如果用 `new Handler(Looper.getMainLooper())`，就在主线程。**注意不要在主线程做耗时操作**，否则 ANR。

### Q4. Provider 的 onCreate() 什么时候调用？
**第一次有 Client 请求数据时**，不是系统启动时。如果配置了 `android:initOrder`，会在系统启动时按顺序初始化。

### Q5. multipleProcess 场景下 Client 和 Provider 可能同进程吗？
是的。如果 Client 和 Provider 声明在同一个进程，Binder 会优化为**直接方法调用**，不走 Binder 驱动。Android 通过 `LocalProvider` 类实现这个优化。

---

## 八、总结

ContentProvider 的本质是三层架构：

```
ContentResolver (Client 端 API)
       │
       │ Binder IPC (跨进程边界)
       ▼
IContentProvider.Stub (Proxy / Stub)
       │
       │ Binder 驱动
       ▼
ContentProvider (Provider 端实现)
```

要真正理解它，需要同时搞懂三个 Android 核心机制：

1. **Binder IPC** — 为什么跨进程调用不走函数栈帧？
2. **CursorWindow + ashmem** — 为什么大数据量不卡顿？
3. **notifyChange + ContentObserver** — 为什么数据库改了 UI 自动刷新？

它不是一个孤立的组件，而是 Android 体系结构的缩影。读懂了 ContentProvider，对理解整个 Android 系统架构都会有帮助。

---

## 参考链接
- [Android ContentProvider 官方文档](https://developer.android.com/guide/topics/providers/content-providers)
- [AOSP ContentResolver 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/content/ContentResolver.java)
- [AOSP ContentProvider 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/content/ContentProvider.java)
- [AOSP CursorWindow native 实现](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/jni/android_database_CursorWindow.cpp)
