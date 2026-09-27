-- KEYS[1] = stock:{skuId}; ARGV[1] = quantity, ARGV[2] = version, ARGV[3] = ttl millis, ARGV[4] = "1" only if present.
-- Writes only a newer version (DESIGN-V2 §3); returns 1 when it wrote, 0 otherwise.
local current = redis.call('HGET', KEYS[1], 'v')
if not current then
  if ARGV[4] == '1' then return 0 end
elseif tonumber(current) >= tonumber(ARGV[2]) then
  return 0
end
redis.call('HSET', KEYS[1], 'q', ARGV[1], 'v', ARGV[2])
if ARGV[4] ~= '1' then
  redis.call('PEXPIRE', KEYS[1], ARGV[3]) -- the TTL is set by a populate (a read); a refresh keeps the remaining TTL
end
return 1
