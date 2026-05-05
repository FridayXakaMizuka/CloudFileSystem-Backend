# /auth/ip-info 后端实现指南

## 问题说明

当前 `/auth/ip-info` 接口返回 `127.0.0.1`，原因是：
- 前端通过 Vite 代理发送请求
- 后端直接读取 `req.ip`，得到的是代理服务器的 IP（127.0.0.1）
- 需要从 HTTP 请求头中获取真实的客户端 IP

## 正确的 IP 获取方法

### Node.js / Express 实现

```javascript
/**
 * 获取客户端真实 IP 地址
 * 优先级：X-Forwarded-For > X-Real-IP > req.ip
 */
function getClientIP(req) {
  // 1. 从 X-Forwarded-For 获取（经过多层代理时可能有多个 IP，取第一个）
  const forwarded = req.headers['x-forwarded-for'];
  if (forwarded) {
    // X-Forwarded-For 格式: "client, proxy1, proxy2"
    const ips = forwarded.split(',').map(ip => ip.trim());
    return ips[0]; // 第一个是客户端真实 IP
  }
  
  // 2. 从 X-Real-IP 获取（Nginx 等反向代理常用）
  const realIP = req.headers['x-real-ip'];
  if (realIP) {
    return realIP;
  }
  
  // 3. 从 req.connection.remoteAddress 获取
  if (req.connection && req.connection.remoteAddress) {
    return req.connection.remoteAddress;
  }
  
  // 4. 从 req.ip 获取（Express 自动处理）
  return req.ip || req.socket.remoteAddress;
}

/**
 * IP 地理位置查询路由
 */
const express = require('express');
const router = express.Router();
const axios = require('axios');

router.get('/auth/ip-info', async (req, res) => {
  try {
    // 1. 获取客户端真实 IP
    const clientIP = getClientIP(req);
    
    console.log('客户端 IP:', clientIP);
    console.log('请求头:', {
      'x-forwarded-for': req.headers['x-forwarded-for'],
      'x-real-ip': req.headers['x-real-ip'],
      'remote-address': req.connection?.remoteAddress
    });
    
    // 2. 如果是本地回环地址，尝试获取公网 IP
    let queryIP = clientIP;
    if (clientIP === '127.0.0.1' || clientIP === '::1' || clientIP === 'localhost') {
      // 本地开发环境，使用外部服务获取公网 IP
      try {
        const publicIPResponse = await axios.get('https://api.ipify.org?format=json');
        queryIP = publicIPResponse.data.ip;
        console.log('检测到本地 IP，使用公网 IP:', queryIP);
      } catch (error) {
        console.warn('无法获取公网 IP，使用默认值');
        queryIP = '未知';
      }
    }
    
    // 3. 查询 IP 地理位置
    let location = '其它';
    
    if (queryIP && queryIP !== '未知') {
      try {
        // 使用高德地图 API（国内推荐）
        const amapResponse = await axios.get('https://restapi.amap.com/v3/ip', {
          params: {
            key: process.env.AMAP_API_KEY, // 在高德开放平台申请
            ip: queryIP
          }
        });
        
        const amapData = amapResponse.data;
        
        if (amapData.status === '1') {
          location = formatLocation(amapData);
        } else {
          console.warn('高德 API 返回错误:', amapData.info);
        }
      } catch (error) {
        console.error('IP 地理位置查询失败:', error.message);
      }
    }
    
    // 4. 返回结果
    res.json({
      code: 200,
      success: true,
      message: '获取 IP 信息成功',
      data: {
        ip: queryIP,
        location: location
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

/**
 * 格式化地理位置
 */
function formatLocation(amapData) {
  const province = amapData.province;
  const city = amapData.city;
  const country = amapData.country;
  
  // 国外 IP
  if (country && country !== '中国') {
    return translateCountry(country);
  }
  
  // 中国 IP
  if (!province) return '其它';
  
  // 直辖市
  const municipalities = ['北京市', '上海市', '天津市', '重庆市'];
  if (municipalities.includes(province)) {
    return province;
  }
  
  // 有城市信息
  if (city && city !== []) {
    return `${province} ${city}`;
  }
  
  // 只有省份
  return province;
}

/**
 * 翻译国家名称
 */
function translateCountry(country) {
  const countryMap = {
    'United States': '美国',
    'USA': '美国',
    'United Kingdom': '英国',
    'UK': '英国',
    'Japan': '日本',
    'South Korea': '韩国',
    'Canada': '加拿大',
    'Australia': '澳大利亚',
    'Germany': '德国',
    'France': '法国',
    'Singapore': '新加坡',
  };
  
  return countryMap[country] || country || '其它';
}

module.exports = router;
```

### Nginx 配置（如果使用 Nginx 反向代理）

```nginx
server {
    listen 80;
    server_name your-domain.com;
    
    location / {
        proxy_pass http://localhost:8835;
        
        # 传递真实 IP
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Host $host;
    }
}
```

## Vite 代理配置

确保 Vite 配置中传递了正确的请求头：

```javascript
// vite.config.js
export default defineConfig({
  server: {
    proxy: {
      '/auth': {
        target: BACKEND_URL,
        changeOrigin: true,
        secure: false,
        onProxyReq: (proxyReq, req, res) => {
          // 传递原始请求的 IP 信息
          proxyReq.setHeader('X-Forwarded-For', req.headers['x-forwarded-for'] || req.ip);
          proxyReq.setHeader('X-Real-IP', req.headers['x-real-ip'] || req.ip);
          proxyReq.setHeader('X-Forwarded-Proto', 'https');
          proxyReq.setHeader('X-Forwarded-Host', req.headers.host);
        }
      }
    }
  }
});
```

## 测试

### 本地测试

```bash
# 访问接口
curl http://localhost:8835/auth/ip-info \
  -H "X-Forwarded-For: 203.0.113.45" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 预期响应
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

### 浏览器测试

1. 打开浏览器开发者工具
2. 访问 `https://192.168.31.187:2311/profile`
3. 查看 Network 标签中的 `/auth/ip-info` 请求
4. 检查响应中的 IP 是否为您的公网 IP

## 环境变量配置

创建 `.env` 文件：

```env
# 高德地图 API Key（免费申请：https://lbs.amap.com/）
AMAP_API_KEY=your_amap_api_key_here
```

## 备选 IP 地理位置服务

### 1. 百度地图 API

```javascript
const baiduResponse = await axios.get('https://api.map.baidu.com/location/ip', {
  params: {
    ak: process.env.BAIDU_API_KEY,
    ip: queryIP,
    coor: 'bd09ll'
  }
});
```

### 2. IP2Location（离线数据库）

```javascript
const IP2Location = require('ip2location-nodejs');
const ip2loc = new IP2Location.IP2Location();
ip2loc.open('./data/IP2LOCATION-LITE-DB1.BIN');

const result = ip2loc.getAll(queryIP);
location = result.countryLong === 'China' 
  ? formatChinaLocation(result.region, result.city)
  : result.countryLong;
```

### 3. ipapi.co（无需 API Key，但有频率限制）

```javascript
const ipapiResponse = await axios.get(`https://ipapi.co/${queryIP}/json/`);
location = formatIpApiLocation(ipapiResponse.data);
```

## 注意事项

1. **本地开发环境**：
   - 如果客户端 IP 是 `127.0.0.1`，需要额外调用外部服务获取公网 IP
   - 可以使用 `https://api.ipify.org` 或 `https://ifconfig.me`

2. **生产环境**：
   - 确保反向代理（Nginx/Apache）正确配置了 `X-Forwarded-For` 头
   - 不要信任用户直接发送的 `X-Forwarded-For` 头（可能被伪造）

3. **隐私保护**：
   - 不要在日志中记录完整的 IP 地址
   - 考虑对 IP 进行脱敏处理

4. **性能优化**：
   - 缓存 IP 地理位置查询结果（Redis，24 小时过期）
   - 异步查询，避免阻塞主线程

## 完整示例（带缓存）

```javascript
const redis = require('redis');
const client = redis.createClient();

async function getCachedIPLocation(ip) {
  // 尝试从缓存获取
  const cached = await client.get(`ip_location:${ip}`);
  if (cached) {
    return JSON.parse(cached);
  }
  
  // 查询 API
  const locationInfo = await queryIPLocation(ip);
  
  // 存入缓存（24 小时）
  await client.setEx(`ip_location:${ip}`, 86400, JSON.stringify(locationInfo));
  
  return locationInfo;
}
```
