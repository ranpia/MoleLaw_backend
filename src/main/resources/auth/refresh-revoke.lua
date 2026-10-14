if redis.call('HGET', KEYS[1], 'seen:' .. ARGV[1]) == '1' then
    redis.call('HSET', KEYS[1], 'revoked', '1')
end
return 1
