package io.tythee;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 通用对象池实现
 * @param <T> 池中对象的类型
 */
public class ObjectPool<T> {
    private final Queue<T> pool; // 对象池队列
    private final ObjectFactory<T> factory; // 对象创建工厂
    private final ObjectResetter<T> resetter; // 对象重置器

    /**
     * 对象池构造函数
     * @param factory 对象创建工厂
     * @param resetter 对象重置器
     */
    public ObjectPool(ObjectFactory<T> factory, ObjectResetter<T> resetter) {
        this.pool = new ConcurrentLinkedQueue<>();
        this.factory = factory;
        this.resetter = resetter;
    }

    /**
     * 从池中借用一个对象
     * @return 池中的对象
     */
    public T borrow() {
        T obj = pool.poll();
        if (obj == null) {
            obj = factory.create(); // 如果池为空，则创建新对象
        }
        return obj;
    }

    /**
     * 将对象归还到池中
     * @param obj 需要归还的对象
     */
    public void returnObject(T obj) {
        if (obj != null) {
            resetter.reset(obj); // 重置对象状态
            pool.offer(obj); // 将对象放回池中
        }
    }

    /**
     * 对象创建工厂接口
     * @param <T> 对象类型
     */
    public interface ObjectFactory<T> {
        T create();
    }

    /**
     * 对象重置器接口
     * @param <T> 对象类型
     */
    public interface ObjectResetter<T> {
        void reset(T obj);
    }
}