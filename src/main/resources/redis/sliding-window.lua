-- 第 10 章：滑动窗口限流（state.RedisRateLimiter 调用）。算法和第 8 章 SlidingWindowLimiter 一模一样，只是队列换成了 Redis 的有序集合：
--   成员 = 每一次调用（名字带随机串，同一毫秒的两次不会互相覆盖），分数 = 调用的时间（毫秒）
--
-- 为什么必须写成 Lua 脚本：下面是「先删、再数、够了就加」三步。分三条命令从 Java 发，两个实例可能同时数到 19（上限 20），然后都加，变成 21 次。
-- Redis 执行一段脚本时不会插进别的命令（单线程、脚本整体原子），三步就成了一步。
--
-- KEYS[1]  这个调用方的有序集合，如 mall-assistant:rate:chat:user:10001
-- ARGV[1]  现在（毫秒）
-- ARGV[2]  窗口长度（毫秒）
-- ARGV[3]  窗口内最多几次
-- ARGV[4]  这一次的成员名
-- 返回     1 = 放行（已记下这一次），0 = 拒绝（不记，被拒的不占名额）
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

-- ① 扔掉窗口外的：时间早于或等于「现在 - 窗口」（ZREMRANGEBYSCORE 的两端都包含，和内存版的 !isAfter(cutoff) 一致）
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
-- ② 数一数窗口里还有几次，够了就拒
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then
  return 0
end
-- ③ 记下这一次；整个键一个窗口后过期（这个调用方不再来，键自己消失，不用像内存版那样定期清）
redis.call('ZADD', KEYS[1], now, ARGV[4])
redis.call('PEXPIRE', KEYS[1], window)
return 1
