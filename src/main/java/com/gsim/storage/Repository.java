package com.gsim.storage;

import java.util.List;
import java.util.Optional;

/**
 * 通用仓储接口。
 */
public interface Repository<T, ID> {
    void save(T entity);
    Optional<T> findById(ID id);
    List<T> findAll();
    void deleteById(ID id);
    boolean existsById(ID id);
}
