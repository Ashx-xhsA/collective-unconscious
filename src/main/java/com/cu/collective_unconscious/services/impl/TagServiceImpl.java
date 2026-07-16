package com.cu.collective_unconscious.services.impl;

import com.cu.collective_unconscious.domain.entities.Tag;
import com.cu.collective_unconscious.repository.TagRepository;
import com.cu.collective_unconscious.services.TagService;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {
  private final TagRepository tagRepository;

  @Override
  public List<Tag> getTags() {
    return tagRepository.findAll();
  }

  @Transactional
  @Override
  // take a set of tag names
  public List<Tag> createTags(Set<String> tagNames) {
    List<Tag> existingTags = tagRepository.findByNameIn(tagNames);
    Set<String> existingTagNames = existingTags.stream()
            .map(Tag::getName)
            .collect(Collectors.toSet());
    List<Tag> newTags = tagNames.stream()
            .filter(name -> !existingTagNames.contains(name))
            .map(name-> Tag.builder()
                    .name(name)
                    .works(new HashSet<>())
                    .build()
            ).toList();
    List<Tag> savedTags = new ArrayList<>();
    if(!newTags.isEmpty()){
      savedTags = tagRepository.saveAll(newTags);
    }
    savedTags.addAll(existingTags);
    return savedTags;
  }

  @Transactional
  @Override
  public void deleteTag(UUID id) {
    tagRepository.findById(id).ifPresent(tag->{
      if (tag.getWorkCount() != 0){
        throw new IllegalStateException("can not delete tag with posts");
      }
      tagRepository.deleteById(id);
    });
  }
}
