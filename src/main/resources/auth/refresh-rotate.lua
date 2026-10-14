local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local expiry = tonumber(redis.call('HGET', KEYS[1], 'expires') or '0')
if expiry <= now or redis.call('HGET', KEYS[1], 'revoked') ~= '0' then return -1 end
local generation = redis.call('GET', KEYS[2])
if not generation or redis.call('HGET', KEYS[1], 'generation') ~= generation then return -1 end
if redis.call('HGET', KEYS[1], 'seen:' .. ARGV[1]) ~= '1' then return -1 end
if redis.call('HGET', KEYS[1], 'current') ~= ARGV[1] then
    redis.call('HSET', KEYS[1], 'revoked', '1')
    return -2
end
if redis.call('HEXISTS', KEYS[1], 'seen:' .. ARGV[2]) == 1 then return -3 end
redis.call('HSET', KEYS[1], 'current', ARGV[2], 'seen:' .. ARGV[2], '1')
return expiry
