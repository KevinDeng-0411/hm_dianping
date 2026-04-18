package com.hmdp.utils;

public interface Ilock {
    boolean tryLock(long timeoutSec);
    void unLock();
}
