-- 第 10 章：分布式的并发名额（state.RedisConcurrencyLimiter 调用）。相当于所有实例共用一个信号量。
--   有序集合里每个成员是一个被拿走的名额（名字是随机的名额 ID），分数是它的租期到期时间（毫秒）
--
-- 为什么要租期：进程内的 Semaphore，拿名额的线程死了，JVM 也一起没了，名额跟着消失；分布式的名额，拿它的实例挂了（kill -9、断网），
-- 没人会来还——不设租期，名额就一个个漏光，最后谁也进不来。所以每个名额最多占 lease 这么久，过期的在下一次有人来拿时收回。
--
-- KEYS[1]  名额集合，如 mall-assistant:slots:chat
-- ARGV[1]  现在（毫秒）
-- ARGV[2]  最多几个名额
-- ARGV[3]  这个名额的 ID
-- ARGV[4]  租期（毫秒）
-- 返回     1 = 拿到了，0 = 满了
local now = tonumber(ARGV[1])

-- ① 收回租期已经到了的（拿着它的实例挂了，或者这一轮比租期还长）
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
-- ② 满了就拒
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then
  return 0
end
-- ③ 拿一个：分数是到期时间；整个集合一个租期后过期（没人用时自己消失）
redis.call('ZADD', KEYS[1], now + tonumber(ARGV[4]), ARGV[3])
redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[4]))
return 1
