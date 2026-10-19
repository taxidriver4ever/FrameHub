local t = redis.call('TIME')
local now = tonumber(t[1]) + tonumber(t[2]) / 1000000
local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local stored = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
local tokens = tonumber(stored[1]) or capacity
local updated = tonumber(stored[2]) or now
tokens = math.min(capacity, tokens + math.max(0, now - updated) * rate)
local allowed = 0
if tokens >= 1 then tokens = tokens - 1; allowed = 1 end
redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'updated', tostring(now))
redis.call('EXPIRE', KEYS[1], math.ceil(capacity / rate) * 2 + 1)
return allowed
