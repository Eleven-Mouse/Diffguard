package com.diffguard.output;

import com.diffguard.model.ReviewResult;

/**
 * 委托给 ReviewReportPrinter 的兼容层。
 * 保留此类以确保现有调用点无需修改。
 */
public class ConsoleFormatter {

    public static void printReport(ReviewResult result) {
        ReviewReportPrinter.printReport(result);
    }
}
