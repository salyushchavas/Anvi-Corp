package com.anvicorp.api.repository;

import com.anvicorp.api.entity.ScreeningAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ScreeningAnswerRepository extends JpaRepository<ScreeningAnswer, UUID> {

    /** Pulls answers + their question rows in a single query for the staff results view. */
    @Query("""
            select a from ScreeningAnswer a
              left join fetch a.question q
             where a.screening.id = :screeningId
             order by q.orderIndex asc
            """)
    List<ScreeningAnswer> findByScreeningIdWithQuestion(UUID screeningId);
}
