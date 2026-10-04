---
id: CACHE-001
title: Redis maxmemory eviction raises the cache miss rate
---
# Redis maxmemory eviction raises the cache miss rate

Redis memory reached maxmemory and the allkeys-lru eviction policy started removing hot keys, which raised the cache miss rate and pushed load onto the database.
Fix: set TTLs on large keys, split the oversized session hash, and raise the instance size. Alert when used_memory exceeds 80%.
