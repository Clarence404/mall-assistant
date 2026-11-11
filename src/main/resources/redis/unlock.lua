-- 第 10 章：释放分布式锁（state.RedisConversationLocks 调用）：只有值还是自己当初写进去的令牌，才删。
--
-- 为什么不能直接 DEL：A 占住会话后卡了很久，标记过了租期自动消失，B 接着占住了（写进 B 的令牌）；这时 A 回来直接 DEL，删掉的是 B 的标记，
-- C 又能进来——同一个会话同时有两个请求在答。「比较再删除」分两条命令发，中间同样可能被插进来，所以写成脚本。
--
-- KEYS[1]  标记的键
-- ARGV[1]  自己的令牌
-- 返回     1 = 删了，0 = 已经不是自己的（或已经过期），什么都没做
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
