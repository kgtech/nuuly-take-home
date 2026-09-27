-- KEYS[1] = idem:{key}; ARGV = op, sku, hash, status, ct, body, created (epoch millis), ttl millis.
-- One atomic write of the fields and the TTL (a timeout between two commands can't leave a key without expiry).
redis.call('HSET', KEYS[1], 'op', ARGV[1], 'sku', ARGV[2], 'hash', ARGV[3], 'status', ARGV[4], 'ct', ARGV[5], 'body', ARGV[6], 'created', ARGV[7])
redis.call('PEXPIRE', KEYS[1], ARGV[8])
return 1
