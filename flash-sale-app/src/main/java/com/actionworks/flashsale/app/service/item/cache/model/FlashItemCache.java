package com.actionworks.flashsale.app.service.item.cache.model;

import com.actionworks.flashsale.domain.model.entity.FlashItem;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 秒杀商品缓存对象
 */
@Data
@Accessors(chain = true)
public class FlashItemCache {
    /**
     * 秒杀品是否存在
     */
    protected boolean exist;
    /**
     * 秒杀品对象
     */
    private FlashItem flashItem;
    /**
     * 本地数据版本
     */
    private Long version;
    /**
     * 是否稍后再试 表示缓存正在更新中
     */
    private boolean later;

    public FlashItemCache with(FlashItem flashActivity) {
        this.exist = true;
        this.flashItem = flashActivity;
        return this;
    }


    public FlashItemCache withVersion(Long version) {
        this.version = version;
        return this;
    }

    public FlashItemCache tryLater() {
        this.later = true;
        return this;
    }

    public FlashItemCache notExist() {
        this.exist = false;
        return this;
    }
}
