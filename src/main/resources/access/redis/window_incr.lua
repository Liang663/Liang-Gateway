local start = redis.call('HGET', KEYS[1], 'start')
if not start then
  return redis.error_reply('missing_window')
end
start = tonumber(start)
local now = tonumber(ARGV[1])
local duration = tonumber(ARGV[2])
local incr = tonumber(ARGV[3])
local n = math.floor((now - start) / duration)
if n >= 1 then
  start = start + n * duration
  redis.call('HSET', KEYS[1], 'start', start, 'used', 0)
end
local used = redis.call('HINCRBY', KEYS[1], 'used', incr)
return {tostring(start), tostring(used)}
