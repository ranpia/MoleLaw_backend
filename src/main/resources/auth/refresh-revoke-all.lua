redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
return 1
