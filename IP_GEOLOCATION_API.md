# IP 地理位置接口设计

## 接口信息

**接口路径：** `/auth/ip-info`

**请求方式：** `GET`

**认证要求：** 需要 JWT Token（通过 Cookie 或 Authorization Header）

---

## 功能说明

获取当前请求的公网 IP 地址及其地理位置信息。

---

## 请求示例

### 使用 Cookie 认证
```bash
curl -X GET "https://your-domain.com/auth/ip-info" \
  -H "Content-Type: application/json" \
  -b "sessionId=xxx; jwt_token=yyy"
```

### 使用 Authorization Header
```bash
curl -X GET "https://your-domain.com/auth/ip-info" \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json"
```

---

## 响应格式

### 成功响应

```json
{
  "code": 200,
  "success": true,
  "message": "获取 IP 信息成功",
  "data": {
    "ip": "203.0.113.45",
    "location": "北京市"
  }
}
```

### 字段说明

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `ip` | string | 公网 IPv4 或 IPv6 地址 | `"203.0.113.45"` |
| `location` | string | IP 属地，格式化后的地理位置 | 见下方格式说明 |

### Location 格式规范

根据 IP 归属地，返回以下格式之一：

1. **直辖市**：`北京市`、`上海市`、`天津市`、`重庆市`
2. **地级市**：`湖南省 长沙市`、`广东省 深圳市`、`浙江省 杭州市`
3. **省份（无城市信息）**：`湖南省`、`广东省`
4. **国外**：`美国`、`日本`、`英国`、`澳大利亚` 等国家名称
5. **未知/其他**：`其它`

---

## 错误响应

### 未认证
```json
{
  "code": 401,
  "success": false,
  "message": "未授权访问",
  "data": null
}
```

### 服务器错误
```json
{
  "code": 500,
  "success": false,
  "message": "获取 IP 信息失败",
  "data": null
}
```

---

## 后端实现要点

### 1. 获取客户端 IP

```javascript
// Node.js / Express 示例
function getClientIP(req) {
  // 优先从 X-Forwarded-For 获取（经过代理/负载均衡）
  const forwarded = req.headers['x-forwarded-for'];
  if (forwarded) {
    // X-Forwarded-For 可能包含多个 IP，取第一个
    return forwarded.split(',')[0].trim();
  }
  
  // 从 X-Real-IP 获取
  const realIP = req.headers['x-real-ip'];
  if (realIP) {
    return realIP;
  }
  
  // 直接获取远程地址
  return req.ip || req.connection.remoteAddress;
}
```

### 2. IP 地理位置查询

推荐使用以下服务之一：

#### 方案 A：高德地图 IP 定位 API（国内推荐）

```javascript
const axios = require('axios');

async function getIPLocation(ip) {
  const apiKey = 'YOUR_AMAP_API_KEY';
  const url = `https://restapi.amap.com/v3/ip?key=${apiKey}&ip=${ip}`;
  
  try {
    const response = await axios.get(url);
    const data = response.data;
    
    if (data.status === '1') {
      return formatLocation(data);
    }
    
    return { ip, location: '其它' };
  } catch (error) {
    console.error('IP 定位失败:', error);
    return { ip, location: '其它' };
  }
}

function formatLocation(amapData) {
  const province = amapData.province;
  const city = amapData.city;
  const country = amapData.country;
  
  // 国外 IP
  if (country && country !== '中国') {
    return country;
  }
  
  // 直辖市
  const municipalities = ['北京市', '上海市', '天津市', '重庆市'];
  if (municipalities.includes(province)) {
    return province;
  }
  
  // 有城市信息
  if (city && city !== []) {
    return `${province} ${city}`;
  }
  
  // 只有省份信息
  if (province) {
    return province;
  }
  
  return '其它';
}
```

#### 方案 B：百度地图 IP 定位 API

```javascript
async function getIPLocationBaidu(ip) {
  const apiKey = 'YOUR_BAIDU_API_KEY';
  const url = `https://api.map.baidu.com/location/ip?ak=${apiKey}&ip=${ip}&coor=bd09ll`;
  
  try {
    const response = await axios.get(url);
    const data = response.data;
    
    if (data.status === 0) {
      const address = data.content.address_detail;
      return formatBaiduLocation(address);
    }
    
    return { ip, location: '其它' };
  } catch (error) {
    console.error('IP 定位失败:', error);
    return { ip, location: '其它' };
  }
}
```

#### 方案 C：IP2Location 数据库（离线方案）

```javascript
const IP2Location = require('ip2location-nodejs');

// 初始化数据库
const ip2loc = new IP2Location.IP2Location();
ip2loc.open('./data/IP2LOCATION-LITE-DB1.BIN');

function getIPLocationOffline(ip) {
  const result = ip2loc.getAll(ip);
  
  if (result.countryShort === 'CN') {
    // 中国 IP
    return formatChinaLocation(result.region, result.city);
  } else {
    // 国外 IP
    return result.countryLong;
  }
}
```

### 3. 完整的路由实现

```javascript
const express = require('express');
const router = express.Router();
const { verifyToken } = require('../middleware/auth');

router.get('/auth/ip-info', verifyToken, async (req, res) => {
  try {
    // 1. 获取客户端 IP
    const ip = getClientIP(req);
    
    // 2. 查询地理位置
    const locationInfo = await getIPLocation(ip);
    
    // 3. 返回结果
    res.json({
      code: 200,
      success: true,
      message: '获取 IP 信息成功',
      data: {
        ip: locationInfo.ip,
        location: locationInfo.location
      }
    });
  } catch (error) {
    console.error('获取 IP 信息失败:', error);
    res.status(500).json({
      code: 500,
      success: false,
      message: '获取 IP 信息失败',
      data: null
    });
  }
});

module.exports = router;
```

---

## 注意事项

### 1. 性能优化

- **缓存策略**：同一 IP 的地理位置可以缓存一段时间（如 24 小时）
- **异步查询**：IP 查询应该是异步的，避免阻塞主线程
- **超时处理**：设置合理的超时时间（如 3-5 秒）

```javascript
// Redis 缓存示例
const redis = require('redis');
const client = redis.createClient();

async function getCachedIPLocation(ip) {
  // 尝试从缓存获取
  const cached = await client.get(`ip_location:${ip}`);
  if (cached) {
    return JSON.parse(cached);
  }
  
  // 查询 API
  const location = await getIPLocation(ip);
  
  // 存入缓存（24 小时过期）
  await client.setEx(`ip_location:${ip}`, 86400, JSON.stringify(location));
  
  return location;
}
```

### 2. 隐私保护

- **不要记录用户 IP**：除非必要，不要在日志中记录完整的 IP 地址
- **数据脱敏**：展示给用户时可以考虑部分隐藏 IP（如 `203.0.113.*`）

### 3. 异常处理

- **API 限流**：如果调用第三方 API，注意频率限制
- **降级策略**：当 API 不可用时，返回默认值（如 `"其它"`）
- **IPv6 支持**：确保能够正确处理 IPv6 地址

### 4. Vite 代理配置

如果前端开发时使用 Vite，需要添加代理配置：

```javascript
// vite.config.js
export default defineConfig({
  server: {
    proxy: {
      '/auth/ip-info': {
        target: BACKEND_URL,
        changeOrigin: true,
        secure: false,
        onProxyReq: (proxyReq, req, res) => {
          proxyReq.setHeader('X-Forwarded-Proto', 'https');
          proxyReq.setHeader('X-Forwarded-Host', req.headers.host);
        }
      }
    }
  }
});
```

---

## 测试

### 本地测试

```bash
# 测试本地 IP
curl http://localhost:8835/auth/ip-info \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 预期响应
{
  "code": 200,
  "success": true,
  "message": "获取 IP 信息成功",
  "data": {
    "ip": "127.0.0.1",
    "location": "其它"
  }
}
```

### 生产环境测试

```bash
# 测试公网 IP
curl https://your-domain.com/auth/ip-info \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 预期响应（示例）
{
  "code": 200,
  "success": true,
  "message": "获取 IP 信息成功",
  "data": {
    "ip": "203.0.113.45",
    "location": "北京市"
  }
}
```

---

## 相关资源

- [高德地图 IP 定位 API 文档](https://lbs.amap.com/api/webservice/guide/api/ipconfig)
- [百度地图 IP 定位 API 文档](https://lbsyun.baidu.com/index.php?title=webapi/ip-api)
- [IP2Location 官方网站](https://www.ip2location.com/)
- [MaxMind GeoIP2](https://dev.maxmind.com/geoip/geolocate-an-ip/)
