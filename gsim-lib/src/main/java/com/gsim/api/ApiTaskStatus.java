package com.gsim.api;

/**
 * 任务状态枚举。
 *
 * <p>定义任务从创建到完成的整个生命周期状态：
 * <ul>
 *   <li>PENDING — 已创建等待执行</li>
 *   <li>RUNNING — 正在执行中</li>
 *   <li>DONE — 执行成功完成</li>
 *   <li>FAILED — 执行失败</li>
 *   <li>CANCELLED — 已被取消</li>
 * </ul>
 */
public enum ApiTaskStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    CANCELLED
}
