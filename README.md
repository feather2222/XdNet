# XdNet

一个面向 Android 7.0（API 24）及以上的轻量 HTTP 通信 SDK。SDK 是独立的 Android Library，传输层基于系统 `HttpURLConnection`，协程和 JSON 分别使用 Kotlin 官方的 Coroutines 与 Serialization。

## 已实现

- GET、HEAD、POST、PUT、PATCH、DELETE
- 相对路径、完整 URL、Query 参数和默认 Header
- JSON 自动序列化/反序列化、文本、表单和二进制请求体
- 同步调用、异步回调、挂起函数和协程取消
- 请求/响应拦截器、并发安全的 Token 自动刷新、脱敏日志
- 内存 LRU、磁盘缓存及四种请求缓存策略
- 上传/下载进度、默认网络状态 StateFlow、全局与单 Host 并发控制
- 幂等请求指数退避重试，可配置重试状态码
- 连接/读取超时、响应体大小保护、统一异常模型
- HTTP 状态码、Header、字节或字符串响应
- 纯本地 HTTP Server 单元测试，不依赖公网

## 接入

工程内模块直接依赖：

```kotlin
dependencies {
    implementation(project(":xdnet"))
}
```

使用 JSON 模型的 App 模块还要应用与 Kotlin 相同版本的序列化插件：

```kotlin
plugins {
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}
```

如果直接复制 AAR（而不是通过 Maven 或工程模块依赖），宿主还需声明 AAR 无法携带的运行时依赖：

```kotlin
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
```

构建 AAR：

```bash
./gradlew :xdnet:assembleRelease
```

产物位于 `xdnet/build/outputs/aar/xdnet-release.aar`。

### 发布到 Maven

本机验证（不签名，产物进 `~/.m2/repository`）：

```bash
./gradlew :xdnet:publishReleasePublicationToMavenLocal
```

正式发布前，在本机 `~/.gradle/gradle.properties`（不要提交到 Git）配置签名密钥和
Central Portal 凭据：

```properties
signing.keyId=<GPG 短 ID>
signing.password=<GPG 密码>
signing.secretKeyRingFile=<~/.gnupg/secring.gpg 路径>
ossrhUsername=<Central Portal token username>
ossrhPassword=<Central Portal token password>
```

然后执行：

```bash
./gradlew :xdnet:publishReleasePublicationToSonatypeRepository
```

发布坐标与 POM 元数据在根目录 `gradle.properties` 的 `GROUP`、`VERSION_NAME`、
`POM_*` 属性中维护。

## 基本用法

建议在 Application 或依赖注入容器中创建并复用一个 Client：

```kotlin
val client = XDNetworkClient.Builder()
    .baseUrl("https://api.example.com/v1/")
    .defaultHeader("Accept", "application/json")
    .connectTimeoutMillis(10_000)
    .readTimeoutMillis(15_000)
    .writeTimeoutMillis(15_000)
    .callTimeoutMillis(60_000)
    .addInterceptor(BearerTokenInterceptor { tokenStore.accessToken })
    .build()
```

异步 GET（回调默认回到 Android 主线程）：

```kotlin
val call = client.get("users", mapOf("page" to "1"))
call.enqueue { result ->
    result.onSuccess { response ->
        if (response.isSuccessful) {
            println(response.bodyString())
        } else {
            println("HTTP ${response.code}: ${response.bodyString()}")
        }
    }.onFailure { error ->
        when (error) {
            is NetworkException.Timeout -> println("请求超时")
            is NetworkException.Cancelled -> println("请求已取消")
            else -> println(error.message)
        }
    }
}

// 页面销毁或请求不再需要时取消
call.cancel()
```

POST JSON：

```kotlin
val request = NetworkRequest.Builder("users")
    .post(RequestBody.json("""{"name":"Alice"}"""))
    .header("X-Request-Id", requestId)
    .build()

client.newCall(request).enqueue { result ->
    // 处理结果
}
```

同步调用会阻塞当前线程，只能放在后台线程：

```kotlin
val response = client.get("health").execute()
```

Client 不再使用时调用 `client.close()`，释放 SDK 创建的后台线程池。完整运行示例见 `app` 模块。

## 协程与 JSON

普通协程请求：

```kotlin
viewModelScope.launch {
    val response = client.get("health").await()
}
```

取消 `viewModelScope` 中的协程会同时取消底层 HTTP 连接。

JSON 模型需要添加 `@Serializable`：

```kotlin
@Serializable
data class User(val id: Long, val name: String)

@Serializable
data class CreateUser(val name: String)
```

自动反序列化 GET：

```kotlin
val user: User = client.getJson("users/1")
```

自动序列化 POST，并自动反序列化响应：

```kotlin
val user: User = client.postJson<CreateUser, User>(
    path = "users",
    value = CreateUser("Alice")
)
```

`awaitJson()` 遇到非 2xx 会抛出 `NetworkException.HttpStatus`；JSON 格式错误会抛出 `NetworkException.Serialization`。默认忽略服务端新增的未知字段，也可以给 `bodyJson()`、`awaitJson()` 传入自定义 `Json`。

## Token 自动刷新

Token Store 必须能够被多个网络线程安全访问：

```kotlin
class AppTokenStore : AccessTokenStore {
    private val token = AtomicReference<String?>(null)

    override fun accessToken(): String? = token.get()
    override fun updateAccessToken(token: String?) {
        this.token.set(token)
    }
}
```

建议使用一个不安装刷新拦截器的独立 Client 调用刷新接口：

```kotlin
val tokenStore = AppTokenStore()

@Serializable
data class RefreshResponse(val accessToken: String)

val refreshClient = XDNetworkClient.Builder()
    .baseUrl("https://api.example.com/")
    .build()

val client = XDNetworkClient.Builder()
    .baseUrl("https://api.example.com/")
    .addInterceptor(
        RefreshingTokenInterceptor(tokenStore) { failedToken ->
            val response = refreshClient.post(
                "auth/refresh",
                RequestBody.json(refreshTokenJson)
            ).execute()
            if (response.isSuccessful) response.bodyJson<RefreshResponse>().accessToken else null
        }
    )
    .build()
```

工作流程：请求自动携带 Bearer Token；收到 401 后刷新并重试一次。多个请求同时收到 401 时只执行一次刷新，其余请求复用新 Token。如果刷新接口必须使用同一个 Client，请在刷新请求上调用 `.skipTokenRefresh()`，防止递归刷新。

## 网络缓存

内存缓存：

```kotlin
val client = XDNetworkClient.Builder()
    .baseUrl("https://api.example.com/")
    .cache(
        value = MemoryNetworkCache(maxSizeBytes = 4L * 1024 * 1024),
        defaultPolicy = CachePolicy.CACHE_FIRST,
        defaultTtlMillis = 5 * 60 * 1_000L
    )
    .build()
```

磁盘缓存应放在 App 私有缓存目录：

```kotlin
val cache = DiskNetworkCache(
    directory = File(context.cacheDir, "xd-network"),
    maxSizeBytes = 50L * 1024 * 1024
)
```

单次请求覆盖缓存策略：

```kotlin
val request = NetworkRequest.Builder("home")
    .get()
    .cachePolicy(CachePolicy.NETWORK_FIRST)
    .cacheTtlMillis(60_000)
    .build()

val response = client.newCall(request).await()
println(response.fromCache)
```

策略说明：

- `NETWORK_ONLY`：只访问网络，不读写缓存。
- `CACHE_ONLY`：只读取缓存；未命中抛出 `NetworkException.CacheMiss`。
- `CACHE_FIRST`：优先返回未过期缓存，否则访问网络并更新缓存。
- `NETWORK_FIRST`：优先访问网络；I/O 异常或最终 5xx 时回退到缓存。

缓存只处理成功的 GET 响应，并遵循 `Cache-Control: max-age`、`no-cache` 和 `no-store`。带 `Authorization`、`Cookie` 或返回 `Set-Cookie` 的响应默认不缓存。磁盘缓存未加密，不应用于保存敏感数据。可调用 `client.clearCache()` 清空缓存。


## 大文件流式上传/下载

### 文件流式上传

`RequestBody.file()` 每次网络尝试都重新打开文件，只用固定大小缓冲区，不会调用 `readBytes()` 把整个文件放进内存：

```kotlin
val controller = TransferController()
val request = NetworkRequest.Builder("files/video")
    .put(RequestBody.file(videoFile, "video/mp4"))
    .tag("video-detail")
    .transferController(controller)
    .uploadProgress { transferred, total, done ->
        println("上传：$transferred / $total，完成：$done")
    }
    .build()

val call = client.newCall(request)
call.enqueue { result -> /* 处理结果 */ }

controller.pause()
controller.resume()
controller.cancel()
```

上传断点协议由后端决定。服务端支持偏移量或 `Content-Range` 时，可以只流式发送文件剩余部分：

```kotlin
val uploadedBytes = 8L * 1024 * 1024
val body = RequestBody.file(
    file = videoFile,
    contentType = "video/mp4",
    offset = uploadedBytes,
    length = videoFile.length() - uploadedBytes
)

val request = NetworkRequest.Builder("files/video")
    .put(body)
    .header(
        "Content-Range",
        "bytes $uploadedBytes-${videoFile.length() - 1}/${videoFile.length()}"
    )
    .build()
```

### 直接写文件和断点下载

```kotlin
val controller = TransferController()
val request = NetworkRequest.Builder("releases/app.apk")
    .get()
    .downloadTo(
        destination = apkFile,
        resume = true,
        checksum = FileChecksum(
            ChecksumAlgorithm.SHA256,
            expectedSha256
        )
    )
    .transferController(controller)
    .downloadProgress { transferred, total, done ->
        println("下载：$transferred / $total，完成：$done")
    }
    .build()

val response = client.newCall(request).await()
println(response.downloadedFile)
```

`resume = true` 且目标文件已有内容时，SDK 自动发送 `Range: bytes=<已有长度>-`。服务端返回 `206 Partial Content` 时追加写入；服务端忽略 Range 并返回 `200` 时安全地从头覆盖。传输失败、暂停或取消时保留已下载部分，下次创建新 Call 可继续下载。

支持 `MD5` 和 `SHA256` 完整性校验；不匹配时抛出 `NetworkException.ChecksumMismatch`。MD5 仅适合兼容旧服务的文件完整性检查，安全场景应使用 SHA-256。普通非文件响应仍保存在内存并受 `maxResponseBytes` 限制。

进度监听器运行在 SDK 网络线程，默认最多约每 100ms 通知一次，并保证开始和完成事件。`totalBytes = -1` 表示服务端没有返回内容长度。操作 View 时需要切回主线程。

## 自动重试和网络恢复

```kotlin
val client = XDNetworkClient.Builder()
    .baseUrl("https://api.example.com/")
    .retryPolicy(
        RetryPolicy(
            maxRetries = 3,
            initialDelayMillis = 300,
            maxDelayMillis = 5_000,
            retryStatusCodes = setOf(408, 429, 500, 502, 503, 504)
        )
    )
    .retryWhenNetworkAvailable(applicationContext, maxWaitMillis = 60_000)
    .build()
```

- 网络 I/O 异常和配置的 HTTP 状态码使用指数退避重试。
- GET、HEAD、PUT、DELETE 默认可重试；POST、PATCH 默认不重试。
- 确认服务端支持幂等键后，才能设置 `retryNonIdempotentRequests = true`。
- 配置 `retryWhenNetworkAvailable()` 后，断网期间等待系统网络回调；恢复后执行下一次重试。等待仍受最大重试次数、网络等待时间和总超时限制。
- 文件请求体可重复打开；断点下载重试时会根据磁盘上的最新长度重新发送 Range。

## 完整超时控制

```kotlin
val client = XDNetworkClient.Builder()
    .connectTimeoutMillis(10_000)
    .readTimeoutMillis(15_000)
    .writeTimeoutMillis(15_000)
    .callTimeoutMillis(60_000)
    .build()
```

- 连接超时：建立 TCP/TLS 连接的最长等待时间。
- 读取超时：等待服务端响应数据的最长阻塞时间。
- 写入超时：单次请求体写入长期无进展时主动断开连接。
- 总超时：从调用开始到完成的总时间，包含并发排队、退避、网络恢复等待和文件传输；默认 `0` 表示不设置总时限。
- 超时统一抛出 `NetworkException.Timeout`。

## 请求 Tag 与分组取消

```kotlin
val request = NetworkRequest.Builder("video/detail")
    .get()
    .tag("video-detail")
    .build()

client.newCall(request).enqueue { /* ... */ }

// 页面销毁：取消同一 Client 内所有匹配 Tag 的排队或运行请求
client.cancelByTag("video-detail")

// 取消该 Client 的全部请求
client.cancelAll()
```

Tag 使用 `equals()` 比较，可以使用字符串、页面 ID 或业务对象。取消运行请求会断开底层连接；取消排队请求会在调度时立即返回 `NetworkException.Cancelled`。

## 网络状态监听

SDK 使用系统默认网络的 `ConnectivityManager.NetworkCallback`，不轮询：

```kotlin
val monitor = NetworkMonitor(applicationContext)

lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        monitor.status.collect { status ->
            println("可用：${status.isAvailable}")
            println("已验证联网：${status.isValidated}")
            println("按流量计费：${status.isMetered}")
            println("网络类型：${status.transports}")
        }
    }
}

// Application 或持有者销毁时
monitor.close()
```

`NetworkMonitor` 会自动注册回调，也可以传入 `autoStart = false` 后手动调用 `start()`/`stop()`。`close()` 会注销系统回调，避免泄漏。SDK Manifest 已自动合并 `ACCESS_NETWORK_STATE` 权限。

## 请求并发控制

```kotlin
val client = XDNetworkClient.Builder()
    .baseUrl("https://api.example.com/")
    .maxConcurrentRequests(16)
    .maxRequestsPerHost(4)
    .build()
```

- `maxConcurrentRequests` 控制整个 Client 同时运行的异步请求，默认 64。
- `maxRequestsPerHost` 控制同一协议、域名和端口的异步请求，默认 5。
- 超出上限的调用按照队列顺序等待，不会占用执行线程。
- `enqueue()` 和协程 `await()` 受并发队列管理；同步 `execute()` 由调用方线程自行控制。
- 取消正在执行的请求会断开连接；排队请求在被调度后会立即返回取消结果。

## 安全说明

- 默认遵循 Android Network Security Config；示例和生产环境应使用 HTTPS。
- 日志拦截器只记录方法、URL、状态、耗时和大小，不记录 Token、Header 值或 Body。
- SDK 不进行不安全的证书信任或 Hostname 校验绕过。
- 默认只自动重试幂等方法；POST/PATCH 如需重试必须显式开启，并应配合服务端幂等键。
- Token 刷新回调运行在网络线程中，不能执行主线程 UI 操作。
- 磁盘缓存使用哈希文件名，但响应内容本身不加密。
