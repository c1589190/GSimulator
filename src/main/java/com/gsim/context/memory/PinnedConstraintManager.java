package com.gsim.context.memory;

import com.gsim.util.IdGenerator;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * PinnedConstraint 管理器 — 硬约束的添加和查询。
 */
public class PinnedConstraintManager {

    private final PinnedConstraintStore store;

    public PinnedConstraintManager(PinnedConstraintStore store) {
        this.store = store;
    }

    /**
     * 添加硬约束。
     */
    public PinnedConstraint addPin(String branchId, String text, String sourceNodeId, String createdBy) {
        String id = IdGenerator.generate("pin");
        PinnedConstraint pin = new PinnedConstraint(
                id, branchId, text, sourceNodeId, Instant.now(), createdBy
        );
        store.add(pin);
        return pin;
    }

    /**
     * 获取指定分支的硬约束。
     */
    public List<PinnedConstraint> getPins(String branchId) {
        return store.findByBranch(branchId);
    }

    /**
     * 获取分支及其父链相关的所有硬约束。
     */
    public List<PinnedConstraint> getPinsForChain(String branchId, Set<String> parentBranchIds) {
        parentBranchIds.add(branchId);
        return store.findByBranches(parentBranchIds);
    }

    /**
     * 删除硬约束。
     */
    public boolean removePin(String pinId) {
        return store.remove(pinId);
    }

    /**
     * 列出所有硬约束。
     */
    public List<PinnedConstraint> listAll() {
        return store.loadAll();
    }
}
