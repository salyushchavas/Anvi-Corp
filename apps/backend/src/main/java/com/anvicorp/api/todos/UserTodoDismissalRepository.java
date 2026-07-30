package com.anvicorp.api.todos;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.UUID;

@Repository
public interface UserTodoDismissalRepository
        extends JpaRepository<UserTodoDismissal, UserTodoDismissal.PK> {

    @Query("SELECT d.todoKey FROM UserTodoDismissal d WHERE d.userId = :userId")
    Set<String> findTodoKeysForUser(@Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM UserTodoDismissal d "
            + "WHERE d.userId = :userId AND d.todoKey = :todoKey")
    int deleteByUserAndKey(@Param("userId") UUID userId,
                           @Param("todoKey") String todoKey);
}
