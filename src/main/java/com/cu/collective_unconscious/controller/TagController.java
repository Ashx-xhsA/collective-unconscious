package com.cu.collective_unconscious.controller;

import com.cu.collective_unconscious.domain.entities.Tag;
import com.cu.collective_unconscious.dtos.CreateTagsRequest;
import com.cu.collective_unconscious.dtos.TagDto;
import com.cu.collective_unconscious.mappers.TagMapper;
import com.cu.collective_unconscious.services.TagService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping(path = "/api/v1/tags")
@RequiredArgsConstructor
public class TagController {

  private final TagService tagService;
  private final TagMapper tagMapper;

  @GetMapping
  public ResponseEntity<List<TagDto>> getAllTags(){
    List<Tag> tags = tagService.getTags();
    List<TagDto> tagRespons = tags.stream().map(tagMapper::toDto).toList();
    return ResponseEntity.ok(tagRespons);

  }
  @PostMapping
  public ResponseEntity<List<TagDto>> createTags(@Valid @RequestBody CreateTagsRequest createTagsRequest){
    List<Tag> savedTags = tagService.createTags(createTagsRequest.getNames());
    List<TagDto> createdTagRespons = savedTags.stream().map(tag -> tagMapper.toDto(tag)).toList();
    return new ResponseEntity<>(
            createdTagRespons,
            HttpStatus.CREATED
    );
  }
  @DeleteMapping(path = "/{id}")
  public ResponseEntity<Void> deleteTas(@PathVariable UUID id){
    tagService.deleteTag(id);
    return ResponseEntity.noContent().build();

  }
}
