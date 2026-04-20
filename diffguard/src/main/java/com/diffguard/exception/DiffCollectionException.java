package com.diffguard.exception;

/**
 * Git 差异采集异常。
 * 携带引用信息和仓库路径等上下文。
 */
public class DiffCollectionException extends DiffGuardException {

    private final String fromRef;
    private final String toRef;
    private final String repositoryPath;

    public DiffCollectionException(String message) {
        super(message);
        this.fromRef = null;
        this.toRef = null;
        this.repositoryPath = null;
    }

    public DiffCollectionException(String message, Throwable cause) {
        super(message, cause);
        this.fromRef = null;
        this.toRef = null;
        this.repositoryPath = null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DiffCollectionException");
        if (repositoryPath != null) {
            sb.append(" [repo=").append(repositoryPath).append("]");
        }
        if (fromRef != null) {
            sb.append(" [").append(fromRef).append("..").append(toRef).append("]");
        }
        sb.append(": ").append(getMessage());
        return sb.toString();
    }
}
