-- ========== 接收参数 ==========
local voucherId = ARGV[1]   -- 第1个参数：优惠券ID
local userId = ARGV[2]      -- 第2个参数：用户ID
local id = ARGV[3]          -- 第3个参数：订单ID

-- ========== 定义Key ==========
local stockKey = 'seckill:stock:' .. voucherId    -- 库存key，如 seckill:stock:5
local orderKey = 'seckill:order:' .. voucherId    -- 已购用户集合key，如 seckill:order:5
-- 判断库存是否充足
--redis.call('get', stockKey) → 执行Redis命令 GET seckill:stock:5，获取库存值
--tonumber(...) → 把字符串转成数字（Redis返回的是字符串 "100"）
--如果库存 <= 0 → 直接返回 1（表示库存不足）
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end
-- 判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end
-- 扣减库存
redis.call('incrby', stockKey, -1)
-- 将userId存入当前优惠券的set集合
redis.call('sadd', orderKey, userId)
-- 将下单数据保存到消息队列中
redis.call("xadd", 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', id)
return 0
