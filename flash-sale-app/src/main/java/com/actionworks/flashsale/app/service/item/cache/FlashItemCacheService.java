package com.actionworks.flashsale.app.service.item.cache;

import com.actionworks.flashsale.app.service.item.cache.model.FlashItemCache;
import com.actionworks.flashsale.cache.DistributedCacheService;
import com.actionworks.flashsale.domain.model.entity.FlashItem;
import com.actionworks.flashsale.domain.service.FlashItemDomainService;
import com.actionworks.flashsale.lock.DistributedLock;
import com.actionworks.flashsale.lock.DistributedLockFactoryService;
import com.alibaba.fastjson.JSON;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.actionworks.flashsale.app.model.constants.CacheConstants.FIVE_MINUTES;
import static com.actionworks.flashsale.app.model.constants.CacheConstants.ITEM_CACHE_KEY;
import static com.actionworks.flashsale.util.StringUtil.link;

@Service
public class FlashItemCacheService {
    private final static Logger logger = LoggerFactory.getLogger(FlashItemCacheService.class);
    /**
     * 秒杀品的本地缓存
     */
    private final static Cache<Long, FlashItemCache> flashItemLocalCache = CacheBuilder.newBuilder().initialCapacity(10).concurrencyLevel(5).expireAfterWrite(10, TimeUnit.SECONDS).build();
    private static final String UPDATE_ITEM_CACHE_LOCK_KEY = "UPDATE_ITEM_CACHE_LOCK_KEY_";
    private final Lock localCacleUpdatelock = new ReentrantLock();

    @Resource
    private DistributedCacheService distributedCacheService;

    @Resource
    private FlashItemDomainService flashItemDomainService;

    @Resource
    private DistributedLockFactoryService distributedLockFactoryService;

    /**
     * 获取秒杀品的缓存
     * @param itemId 秒杀品ID
     * @param version 客户端持有的缓存版本号
     * @return 秒杀品缓存
     */
    public FlashItemCache getCachedItem(Long itemId, Long version) {
        // 1. 从本地缓存中查找
        FlashItemCache flashItemCache = flashItemLocalCache.getIfPresent(itemId);
        if (flashItemCache != null) {
            // 本地缓存中存在 ，如果version==null 直接return
            if (version == null) {
                logger.info("itemCache|命中本地缓存|{}", itemId);
                return flashItemCache;
            }
            // 如果version不为空，判断本地缓存中的version是否和传入的version一致，如果一致直接return
            if (version.equals(flashItemCache.getVersion()) || version < flashItemCache.getVersion()) {
                logger.info("itemCache|命中本地缓存|{}", itemId, version);
                return flashItemCache;
            }
            // 如果传入的version大于本地缓存中的version，说明本地缓存中的数据已经过期，需要从redis中获取

        }
        return getLatestDistributedCache(itemId);
    }

    private FlashItemCache getLatestDistributedCache(Long itemId) {
        logger.info("itemCache|读取远程缓存|{}", itemId);
        FlashItemCache distributedFlashItemCache = distributedCacheService.getObject(buildItemCacheKey(itemId), FlashItemCache.class);
        if (distributedFlashItemCache == null) {
            distributedFlashItemCache = tryToUpdateItemCacheByLock(itemId);
        }
        if (distributedFlashItemCache != null && !distributedFlashItemCache.isLater()) {
            boolean isLockSuccess = localCacleUpdatelock.tryLock();
            if (isLockSuccess) {
                try {
                    flashItemLocalCache.put(itemId, distributedFlashItemCache);
                    logger.info("itemCache|本地缓存已更新|{}", itemId);
                } finally {
                    localCacleUpdatelock.unlock();
                }
            }
        }
        return distributedFlashItemCache;
    }

    public FlashItemCache tryToUpdateItemCacheByLock(Long itemId) {
        logger.info("itemCache|更新远程缓存|{}", itemId);
        DistributedLock lock = distributedLockFactoryService.getDistributedLock(UPDATE_ITEM_CACHE_LOCK_KEY + itemId);
        try {
            boolean isLockSuccess = lock.tryLock(1, 5, TimeUnit.SECONDS);
            if (!isLockSuccess) {
                return new FlashItemCache().tryLater();
            }
            FlashItemCache distributedFlashItemCache = distributedCacheService.getObject(buildItemCacheKey(itemId), FlashItemCache.class);
            if (distributedFlashItemCache != null) {
                return distributedFlashItemCache;
            }
            FlashItem flashItem = flashItemDomainService.getFlashItem(itemId);
            FlashItemCache flashItemCache;
            if (flashItem == null) {
                flashItemCache = new FlashItemCache().notExist();
            } else {
                flashItemCache = new FlashItemCache().with(flashItem).withVersion(System.currentTimeMillis());
            }
            distributedCacheService.put(buildItemCacheKey(itemId), JSON.toJSONString(flashItemCache), FIVE_MINUTES);
            logger.info("itemCache|远程缓存已更新|{}", itemId);
            return flashItemCache;
        } catch (InterruptedException e) {
            logger.error("itemCache|远程缓存更新失败|{}", itemId);
            return new FlashItemCache().tryLater();
        } finally {
            lock.unlock();
        }
    }

    private String buildItemCacheKey(Long itemId) {
        return link(ITEM_CACHE_KEY, itemId);
    }
}
