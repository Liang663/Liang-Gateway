local start = redis.call('HGET', KEYS[1], 'start')
if not start then
  return redis.error_reply('missing_window')
end
start = tonumber(start)
local used = tonumber(redis.call('HGET', KEYS[1], 'used') or '0')
local now = tonumber(ARGV[1])
local duration = tonumber(ARGV[2])
local n = math.floor((now - start) / duration)
if n >= 1 then
  start = start + n * duration
  used = 0
  redis.call('HSET', KEYS[1], 'start', start, 'used', used)
end
return {tostring(start), tostring(used)}
