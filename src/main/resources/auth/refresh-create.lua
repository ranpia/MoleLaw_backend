local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local expiry = tonumber(ARGV[3])
local generation = redis.call('GET', KEYS[2])
if not generation or generation ~= ARGV[4] then return -1 end
if expiry <= now or redis.call('EXISTS', KEYS[1]) == 1 then return -2 end
redis.call('SET', KEYS[2], generation, 'PX', math.max(expiry - now, redis.call('PTTL', KEYS[2])))
redis.call('HSET', KEYS[1], 'current', ARGV[1], 'seen:' .. ARGV[1], '1', 'expires', ARGV[3], 'generation', generation, 'revoked', '0')
redis.call('PEXPIREAT', KEYS[1], expiry)
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)
redis.call('ZADD', KEYS[3], expiry, ARGV[2])
local latest = redis.call('ZREVRANGE', KEYS[3], 0, 0, 'WITHSCORES')
redis.call('PEXPIREAT', KEYS[3], tonumber(latest[2]))
return 1
