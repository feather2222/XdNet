# Changelog

本项目的所有重要变更都会记录在此文件中。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 2026-10-25

首个公开发布版本。

### 新增
- GET、HEAD、POST、PUT、PATCH、DELETE 全方法支持
- 同步调用、异步回调、挂起函数与协程取消
- JSON 自动序列化/反序列化（kotlinx.serialization）
- 请求/响应拦截器，并发安全的 Token 自动刷新
- 幂等请求指数退避重试，可配置重试状态码
- 内存 LRU 与磁盘缓存，四种请求缓存策略
- 上传/下载进度监听
- 基于 ConnectivityManager 的网络状态 StateFlow
- 全局与单 Host 并发控制
- 统一异常模型与脱敏日志
- 本地 HTTP Server 单元测试
