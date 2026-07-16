package com.cu.collective_unconscious.repository;

import com.cu.collective_unconscious.domain.entities.Tag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface TagRepository extends JpaRepository<Tag, UUID> {


  List<Tag> findByNameIn(Set<String> names);
}
