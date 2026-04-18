package com.diffguard.review;

import com.diffguard.exception.DiffGuardException;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.ReviewResult;

import java.nio.file.Path;
import java.util.List;

/**
 * 统一审查引擎接口。
 * <p>
 * 所有审查模式（Simple / Pipeline / Multi-Agent）均实现此接口，
 * 消费方（CLI、Webhook）通过 {@link ReviewEngineFactory} 获取具体实例。
 */
public interface ReviewEngine extends AutoCloseable {

    /**
     * 执行代码审查。
     *
     * @param diffEntries 待审查的差异文件列表
     * @param projectDir  项目根目录
     * @return 审查结果
     * @throws DiffGuardException 审查过程中的业务异常
     */
    ReviewResult review(List<DiffFileEntry> diffEntries, Path projectDir) throws DiffGuardException;

    @Override
    void close();
}
