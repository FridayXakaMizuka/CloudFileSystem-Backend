# 🚀 全局请求头拦截器 - 使用指南

## ✅ 已完成配置

全局 Fetch 拦截器已经启用，**所有接口都会自动携带安全请求头**，无需修改任何现有代码！

---

## 📋 工作原理

```
你的代码调用 fetch()
    ↓
拦截器捕获请求
    ↓
自动添加安全请求头
    ├─ X-Device-Fingerprint
    ├─ X-Forwarded-Proto
    ├─ X-Client-Type
    ├─ X-Client-Identifier
    ├─ X-Client-Platform
    ├─ X-Browser-Type / X-Electron-Version
    ├─ X-App-Version (移动端)
    └─ X-Client-IP
    ↓
发送到后端
```

---

## 🎯 使用方法

### **无需任何修改！**

你现有的所有 `fetch` 调用都会自动添加安全请求头。

**示例：**

```javascript
// 你的代码（无需修改）
const response = await fetch('/profile/get_all', {
  method: 'POST',
  credentials: 'include',
  headers: {
    'Authorization': `Bearer ${token}`
  }
})

// 实际发送的请求（自动添加）
Headers:
  Authorization: Bearer eyJhbGci...
  X-Device-Fingerprint: a3f5b2c1d4e6...
  X-Forwarded-Proto: https
  X-Client-Type: browser
  X-Client-Identifier: browser-chrome-windows
  X-Client-Platform: windows
  X-Browser-Type: chrome
  X-Client-IP: 203.0.113.42
```

---

## 🔍 验证效果

### **方法 1：浏览器开发者工具**

1. 打开浏览器（Chrome/Edge/Firefox）
2. 按 `F12` 打开开发者工具
3. 切换到 **Network** 标签
4. 执行任意操作（如登录、获取个人信息）
5. 点击请求，查看 **Headers** → **Request Headers**

你应该看到：

```
Request Headers:
  Authorization: Bearer ...
  X-Device-Fingerprint: a3f5b2c1d4e6...
  X-Forwarded-Proto: https
  X-Client-Type: browser
  X-Client-Identifier: browser-chrome-windows
  X-Client-Platform: windows
  X-Browser-Type: chrome
  X-Client-IP: 203.0.113.42
```

---

### **方法 2：控制台日志**

在开发环境下，拦截器会自动打印请求和响应日志：

```
[FetchInterceptor] ✅ Fetch 拦截器已启用
[FetchInterceptor] 所有 fetch 请求将自动添加安全请求头
[FetchInterceptor] 📤 请求: {method: "POST", url: "/profile/get_all", headers: {...}}
[FetchInterceptor] 📥 响应: {status: 200, url: "/profile/get_all"}
```

---

## ⚙️ 配置选项

### **跳过特定URL的拦截**

如果某些外部API不需要安全请求头，可以在 `fetchInterceptor.js` 中配置：

```javascript
const skipPatterns = [
  'https://api.ipify.org',      // IP查询API
  'https://api64.ipify.org',    // IP查询API
  'https://example.com/api',    // 其他外部API
]
```

---

### **禁用拦截器（调试用）**

如果需要临时禁用拦截器：

```javascript
import { restoreOriginalFetch } from '@/utils/fetchInterceptor'

// 恢复原始 fetch
restoreOriginalFetch()
```

---

## 📊 自动添加的请求头

| 请求头 | 说明 | 示例值 | 环境 |
|--------|------|--------|------|
| `X-Device-Fingerprint` | 设备指纹 | `a3f5b2c1...` | 全部 |
| `X-Forwarded-Proto` | 协议标识 | `https` | 全部 |
| `X-Client-Type` | 客户端类型 | `browser` | 全部 |
| `X-Client-Identifier` | 详细标识 | `browser-chrome-windows` | 全部 |
| `X-Client-Platform` | 操作系统 | `windows` | 全部 |
| `X-Browser-Type` | 浏览器类型 | `chrome` | Browser |
| `X-Electron-Version` | Electron版本 | `28.0.0` | Electron |
| `X-App-Version` | 应用版本 | `1.0.0` | Mobile |
| `X-Client-IP` | IP地址 | `203.0.113.42` | 全部 |

---

## 🎯 覆盖的接口范围

### **✅ 自动覆盖所有接口**

- ✅ `/auth/*` - 认证相关
- ✅ `/profile/*` - 个人资料
- ✅ `/file/*` - 文件操作
- ✅ `/transfer/*` - 传输管理
- ✅ 所有其他使用 `fetch` 的接口

### **❌ 跳过的接口**

- ❌ 外部IP查询API（`api.ipify.org`）
- ❌ 其他配置为跳过的外部API

---

## 💡 优势

### **1. 零代码修改**

✅ 无需修改任何现有的 `fetch` 调用  
✅ 无需在每个文件中导入工具函数  
✅ 新写的代码自动生效  

---

### **2. 统一管理**

✅ 所有请求头在一个地方配置  
✅ 易于维护和更新  
✅ 一致的请求头策略  

---

### **3. 灵活性**

✅ 可以配置跳过特定URL  
✅ 可以随时禁用/启用  
✅ 支持开发和生产环境  

---

### **4. 可观测性**

✅ 开发环境自动打印日志  
✅ 便于调试和问题排查  
✅ 清晰的请求/响应记录  

---

## ⚠️ 注意事项

### **1. 异步请求头**

`X-Client-IP` 和移动端的 `X-App-Version` 是异步获取的，第一个请求可能没有这些请求头。

**影响**：后端应该容忍这些请求头为空。

---

### **2. 文件上传**

拦截器会自动处理文件上传，不会设置错误的 `Content-Type`。

```javascript
// 你的代码
const formData = new FormData()
formData.append('file', file)

await fetch('/file/upload', {
  method: 'POST',
  body: formData
})

// 实际发送（正确）
Headers:
  Content-Type: multipart/form-data; boundary=----WebKitFormBoundary...
  X-Device-Fingerprint: ...
  ...
```

---

### **3. 性能影响**

拦截器只会增加微小的开销（< 10ms），对用户体验几乎没有影响。

---

## 🔧 技术细节

### **实现原理**

1. **保存原始 fetch**：在模块加载时保存 `window.fetch`
2. **重写 fetch**：用增强版本替换 `window.fetch`
3. **添加请求头**：调用 `createSecureFetchOptions` 自动添加
4. **调用原始 fetch**：使用增强后的选项调用原始 fetch

---

### **兼容性**

✅ 支持所有现代浏览器  
✅ 支持 Web/Electron/Android/iOS  
✅ 不影响其他库（axios等）  

---

## 📞 故障排查

### **问题 1：请求头没有添加**

**检查**：
1. 确认 `main.js` 中导入了拦截器
2. 查看控制台是否有错误日志
3. 确认 `requestHeaders.js` 正常工作

**解决**：
```javascript
// 在 main.js 中确认
import './utils/fetchInterceptor'
```

---

### **问题 2：某些请求不需要请求头**

**解决**：在 `fetchInterceptor.js` 中添加跳过规则：

```javascript
const skipPatterns = [
  'https://external-api.com',  // 添加需要跳过的URL
]
```

---

### **问题 3：想查看实际发送的请求头**

**解决**：打开浏览器开发者工具 → Network 标签 → 查看请求详情

---

## 🎉 总结

### **已完成**

✅ 创建全局 Fetch 拦截器  
✅ 在 `main.js` 中启用  
✅ 自动为所有请求添加安全请求头  
✅ 支持跳过特定URL  
✅ 开发环境自动打印日志  

### **下一步**

1. ✅ 测试验证请求头是否正确发送
2. ⏳ 后端实现 Filter 接收请求头
3. ⏳ 实现设备管理功能

---

**就这么简单！所有接口现在都会自动携带安全请求头！** 🚀

---

**最后更新**: 2026-05-02  
**状态**: ✅ 已启用
