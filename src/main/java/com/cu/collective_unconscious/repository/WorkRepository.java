package com.cu.collective_unconscious.repository;

import com.cu.collective_unconscious.domain.entities.Tag;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkRepository extends JpaRepository<Work, UUID> {

  // Direct-by-id read: not visibility-gated (that filter only applies to the /works listing).
  @Query("SELECT w FROM Work w LEFT JOIN FETCH w.tags WHERE w.id = :id")
  Optional<Work> findWithTagsById(@Param("id") UUID id);

  // LEFT JOIN FETCH w.tags: load each work's tags in the same query (no N+1).
  // DISTINCT collapses the duplicate work rows a collection fetch produces.
  @Query("SELECT DISTINCT w FROM Work w LEFT JOIN FETCH w.tags "
      + "WHERE w.visibility = :visibility")
  List<Work> findByVisibilityWithTags(@Param("visibility") Visibility visibility);

  // :tag MEMBER OF w.tags filters "works that have this tag" via a subquery, so it
  // does NOT reuse the fetch join — every matching work still gets ALL its tags loaded.
  @Query("SELECT DISTINCT w FROM Work w LEFT JOIN FETCH w.tags "
      + "WHERE w.visibility = :visibility AND :tag MEMBER OF w.tags")
  List<Work> findByVisibilityAndTagWithTags(@Param("visibility") Visibility visibility,
                                            @Param("tag") Tag tag);
}