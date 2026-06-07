package com.mizuka.cloudfilesystem.exception;

/**
 * 乐观锁异常
 * 当版本号不匹配时抛出
 */
public class OptimisticLockException extends RuntimeException {
    
    public OptimisticLockException(String message) {
        super(message);
    }
    
    public OptimisticLockException(String message, Throwable cause) {
        super(message, cause);
    }
}
