#!/bin/bash

# RSA密钥随机性测试脚本
# 用于验证每次调用 /auth/rsa-key 是否生成不同的密钥对

echo "======================================"
echo "RSA密钥随机性测试"
echo "======================================"
echo ""

# 测试配置
SESSION_ID="test-randomness-$(date +%s)"
BASE_URL="http://localhost:8080"
TEST_COUNT=5

echo "测试参数:"
echo "  SessionId: $SESSION_ID"
echo "  测试次数: $TEST_COUNT"
echo "  Base URL: $BASE_URL"
echo ""
echo "======================================"
echo ""

# 存储每次返回的公钥
declare -a PUBLIC_KEYS

# 连续调用多次
for i in $(seq 1 $TEST_COUNT); do
    echo "[$i/$TEST_COUNT] 调用 /auth/rsa-key..."
    
    RESPONSE=$(curl -s -X POST "$BASE_URL/auth/rsa-key" \
        -H "Content-Type: application/json" \
        -d "{\"sessionId\": \"$SESSION_ID\"}")
    
    # 提取公钥
    PUBLIC_KEY=$(echo "$RESPONSE" | grep -o '"publicKey":"[^"]*"' | cut -d'"' -f4)
    
    if [ -z "$PUBLIC_KEY" ]; then
        echo "  ❌ 失败: 无法获取公钥"
        echo "  响应: $RESPONSE"
        exit 1
    fi
    
    PUBLIC_KEYS[$i]="$PUBLIC_KEY"
    
    # 显示公钥的前50个字符
    SHORT_KEY="${PUBLIC_KEY:0:50}..."
    echo "  ✅ 成功: $SHORT_KEY"
    
    # 等待100毫秒，避免太快
    sleep 0.1
done

echo ""
echo "======================================"
echo "检查结果"
echo "======================================"
echo ""

# 检查是否有重复的公钥
ALL_UNIQUE=true
for i in $(seq 1 $TEST_COUNT); do
    for j in $(seq $((i+1)) $TEST_COUNT); do
        if [ "${PUBLIC_KEYS[$i]}" = "${PUBLIC_KEYS[$j]}" ]; then
            echo "❌ 发现重复: 第$i次和第$j次的公钥相同"
            ALL_UNIQUE=false
        fi
    done
done

if [ "$ALL_UNIQUE" = true ]; then
    echo "✅ 所有公钥都是唯一的，随机化正常工作"
else
    echo "❌ 存在重复的公钥，随机化可能失效"
fi

echo ""
echo "======================================"
echo "Redis检查"
echo "======================================"
echo ""

# 检查Redis中的密钥对
echo "检查Redis中的密钥对..."
REDIS_VALUE=$(redis-cli GET "rsa:key:$SESSION_ID")

if [ -z "$REDIS_VALUE" ]; then
    echo "❌ Redis中未找到密钥对"
else
    echo "✅ Redis中存在密钥对"
    echo ""
    echo "密钥对内容（前200字符）:"
    echo "${REDIS_VALUE:0:200}..."
fi

echo ""
echo "======================================"
echo "测试完成"
echo "======================================"
