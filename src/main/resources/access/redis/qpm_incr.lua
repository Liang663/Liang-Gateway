local v = redis.call('INCR', KEYS[1])
if v == 1 then
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
end
return v
