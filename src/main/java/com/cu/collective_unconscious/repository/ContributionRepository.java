package com.cu.collective_unconscious.repository;

import com.cu.collective_unconscious.domain.entities.Contribution;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContributionRepository extends JpaRepository<Contribution, UUID> {

  // JOIN FETCH c.work: load each draft AND its work in ONE query (kills N+1).
  @Query("SELECT c FROM Contribution c JOIN FETCH c.work "
      + "WHERE c.author = :author AND c.draft = :draft")
  List<Contribution> findDraftsWithWork(@Param("author") User author,
                                        @Param("draft") boolean draft);

  // Null means "nothing submitted yet for this work" -> the caller starts at 1.
  @Query("SELECT MAX(c.sequenceNumber) FROM Contribution c "
      + "WHERE c.work = :work AND c.draft = false")
  Integer findMaxSequenceNumber(@Param("work") Work work);

  // Cursor pagination (§3.4): sequenceNumber is append-only per work, so "give me the ones after
  // N" via a Pageable limit beats OFFSET — no re-counting skipped rows, no reshuffle on insert.
  @Query("SELECT c FROM Contribution c JOIN FETCH c.author JOIN FETCH c.work "
      + "WHERE c.work = :work AND c.draft = false "
      + "AND (:afterSequence IS NULL OR c.sequenceNumber > :afterSequence) "
      + "ORDER BY c.sequenceNumber ASC")
  List<Contribution> findSubmittedAfterSequence(@Param("work") Work work,
                                                 @Param("afterSequence") Integer afterSequence,
                                                 Pageable pageable);

  // Single-chapter direct read (AO3-style). Excludes drafts so a private draft's content/author
  // can never leak through this public endpoint just because its id is known.
  @Query("SELECT c FROM Contribution c JOIN FETCH c.author JOIN FETCH c.work "
      + "WHERE c.id = :id AND c.draft = false")
  Optional<Contribution> findSubmittedById(@Param("id") UUID id);
}
