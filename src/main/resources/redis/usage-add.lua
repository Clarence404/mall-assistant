-- 第 10 章：Token 总账记一轮（state.RedisUsageStore 调用）。一天的账按维度分成几个哈希，字段是「成员|项目」：
--   usage:{日期}:caller        字段 user:10001|turns、user:10001|in、user:10001|out、user:10001|estimated
--   usage:{日期}:intent        字段 POLICY|turns ……
--   usage:{日期}:conversation  字段 user:10001/会话 ID|turns ……
--   usage:{日期}:total         字段 all|turns ……（合计，省得汇总时把所有调用方加一遍）
-- 另有两个有序集合按 Token 排名（/actuator/tokens 的「用得最多的」）：usage:{日期}:caller-rank、usage:{日期}:conversation-rank
--
-- 为什么用一段脚本：一轮要改六七个键，分开发命令，汇总时可能读到「调用方加上了、意图还没加」的半截账；而且要好几次网络往返。
-- 脚本在 Redis 里一次执行完，别的命令插不进来。
--
-- KEYS[1..6]  caller、intent、conversation、caller-rank、conversation-rank、total
-- ARGV[1]     调用方
-- ARGV[2]     意图
-- ARGV[3]     会话维度的键；空字符串 = 没有会话
-- ARGV[4..7]  这一轮的 turns、输入、输出、是否估算（1 / 0）
-- ARGV[8]     这些键多久后过期（毫秒）：只需要今天的，留两天足够
-- 返回        记完之后这个调用方当天的 Token（输入 + 输出）
local function add(hash, member)
  redis.call('HINCRBY', hash, member .. '|turns', ARGV[4])
  redis.call('HINCRBY', hash, member .. '|in', ARGV[5])
  redis.call('HINCRBY', hash, member .. '|out', ARGV[6])
  redis.call('HINCRBY', hash, member .. '|estimated', ARGV[7])
end

local tokens = tonumber(ARGV[5]) + tonumber(ARGV[6])
add(KEYS[1], ARGV[1])
add(KEYS[2], ARGV[2])
add(KEYS[6], 'all')
redis.call('ZINCRBY', KEYS[4], tokens, ARGV[1])
if ARGV[3] ~= '' then
  add(KEYS[3], ARGV[3])
  redis.call('ZINCRBY', KEYS[5], tokens, ARGV[3])
end
for i = 1, 6 do
  redis.call('PEXPIRE', KEYS[i], ARGV[8])
end
-- ZSCORE 返回的是字符串，tonumber 之后 Redis 把 Lua 的数字转成整数回复
return tonumber(redis.call('ZSCORE', KEYS[4], ARGV[1]))
