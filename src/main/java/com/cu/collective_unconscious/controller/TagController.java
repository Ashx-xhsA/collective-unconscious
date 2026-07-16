package com.cu.collective_unconscious.controller;

import com.cu.collective_unconscious.domain.entities.Tag;
import com.cu.collective_unconscious.dtos.CreateTagsRequest;
import com.cu.collective_unconscious.dtos.TagResponse;
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

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping(path = "/api/v1/tags")
@RequiredArgsConstructor
public class TagController {

  private final TagService tagService;
  private final TagMapper tagMapper;

  @GetMapping
  public ResponseEntity<List<TagResponse>> getAllTags(){
    List<Tag> tags = tagService.getTags();
    List<TagResponse> tagResponses = tags.stream().map(tagMapper::toTagResponse).toList();
    return ResponseEntity.ok(tagResponses);

  }
  @PostMapping
  public ResponseEntity<List<TagResponse>> createTags(@RequestBody CreateTagsRequest createTagsRequest){
    List<Tag> savedTags = tagService.createTags(createTagsRequest.getNames());
    List<TagResponse> createdTagResponses = savedTags.stream().map(tag -> tagMapper.toTagResponse(tag)).toList();
    return new ResponseEntity<>(
            createdTagResponses,
            HttpStatus.CREATED
    );
  }
  @DeleteMapping(path = "/{id}")
  public ResponseEntity<Void> deleteTas(@PathVariable UUID id){
    tagService.deleteTag(id);
    return ResponseEntity.noContent().build();

  }
}
