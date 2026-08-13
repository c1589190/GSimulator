/**
 * WorldInformation 内存模型 -- 世界状态的单一数据源。
 *
 * <p>磁盘布局：worlds/{worldId}/nodes/nXXXX.json（增量快照），启动时加载为
 * 一个 {@link com.gsim.worldinfo.WorldInformation} 实例。
 */
package com.gsim.worldinfo;
