package com.cu.collective_unconscious.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cu.collective_unconscious.domain.entities.Tag;
import com.cu.collective_unconscious.repository.TagRepository;
import com.cu.collective_unconscious.services.impl.TagServiceImpl;

import jakarta.persistence.EntityNotFoundException;

/**
 * Pure unit test for {@link TagServiceImpl}. The repository is mocked, so this locks in the
 * service's business rules in isolation: get-or-create de-duplication (never persist a name that
 * already exists), the not-found lookup contract, and the "refuse to delete a tag still attached
 * to works" guard — all without touching a database.
 */
@ExtendWith(MockitoExtension.class)
class TagServiceImplTest {

  @Mock TagRepository tagRepository;
  @InjectMocks TagServiceImpl service;

  private Tag tag(String name) {
    return Tag.builder().id(UUID.randomUUID()).name(name).build();
  }

  // --- getTags -------------------------------------------------------------

  @Test
  void getTags_delegatesToRepositoryFindAll() {
    List<Tag> all = List.of(tag("a"), tag("b"));
    when(tagRepository.findAll()).thenReturn(all);

    assertThat(service.getTags()).isEqualTo(all);
  }

  // --- createTags: happy path ---------------------------------------------

  @Test
  void createTags_allNew_savesEveryName_andReturnsThem() {
    when(tagRepository.findByNameIn(anySet())).thenReturn(List.of());
    // Real JPA saveAll returns a fresh mutable list; the service then addAll()s onto it.
    when(tagRepository.saveAll(anyIterableOfTags()))
        .thenAnswer(inv -> new ArrayList<Tag>(inv.getArgument(0)));

    List<Tag> result = service.createTags(Set.of("fantasy", "sci-fi"));

    assertThat(result).extracting(Tag::getName)
        .containsExactlyInAnyOrder("fantasy", "sci-fi");
    verify(tagRepository).saveAll(anyIterableOfTags());
  }

  @Test
  void createTags_allExisting_savesNothing_andReturnsExisting() {
    Tag existing = tag("fantasy");
    when(tagRepository.findByNameIn(Set.of("fantasy"))).thenReturn(List.of(existing));

    List<Tag> result = service.createTags(Set.of("fantasy"));

    assertThat(result).containsExactly(existing);
    // Nothing new to persist — saveAll must not be called at all.
    verify(tagRepository, never()).saveAll(any());
  }

  @Test
  void createTags_mixed_persistsOnlyTheNewOnes_andReturnsBoth() {
    Tag existing = tag("fantasy");
    when(tagRepository.findByNameIn(Set.of("fantasy", "horror")))
        .thenReturn(List.of(existing));
    when(tagRepository.saveAll(anyIterableOfTags()))
        .thenAnswer(inv -> new ArrayList<Tag>(inv.getArgument(0)));

    List<Tag> result = service.createTags(Set.of("fantasy", "horror"));

    // Only "horror" is new -> saveAll receives exactly that one.
    ArgumentCaptor<List<Tag>> saved = tagListCaptor();
    verify(tagRepository).saveAll(saved.capture());
    assertThat(saved.getValue()).extracting(Tag::getName).containsExactly("horror");

    // The returned list contains both the newly-created and the pre-existing tag.
    assertThat(result).extracting(Tag::getName)
        .containsExactlyInAnyOrder("fantasy", "horror");
  }

  @Test
  void createTags_emptySet_savesNothing_returnsEmpty() {
    when(tagRepository.findByNameIn(Set.of())).thenReturn(List.of());

    assertThat(service.createTags(Set.of())).isEmpty();
    verify(tagRepository, never()).saveAll(any());
  }

  // --- getTagById ----------------------------------------------------------

  @Test
  void getTagById_returnsTag_whenPresent() {
    UUID id = UUID.randomUUID();
    Tag t = Tag.builder().id(id).name("fantasy").build();
    when(tagRepository.findById(id)).thenReturn(Optional.of(t));

    assertThat(service.getTagById(id)).isSameAs(t);
  }

  @Test
  void getTagById_throwsNotFound_whenMissing() {
    UUID id = UUID.randomUUID();
    when(tagRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getTagById(id))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining("Tag not found");
  }

  // --- deleteTag -----------------------------------------------------------

  @Test
  void deleteTag_removesTag_whenNotAttachedToAnyWork() {
    UUID id = UUID.randomUUID();
    Tag unused = Tag.builder().id(id).name("fantasy").workCount(0).build();
    when(tagRepository.findById(id)).thenReturn(Optional.of(unused));

    service.deleteTag(id);

    verify(tagRepository).deleteById(id);
  }

  @Test
  void deleteTag_rejects_whenTagStillAttachedToWorks() {
    UUID id = UUID.randomUUID();
    Tag inUse = Tag.builder().id(id).name("fantasy").workCount(3).build();
    when(tagRepository.findById(id)).thenReturn(Optional.of(inUse));

    assertThatThrownBy(() -> service.deleteTag(id))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("can not delete");
    // Guard must fire before any delete happens.
    verify(tagRepository, never()).deleteById(any());
  }

  @Test
  void deleteTag_isNoOp_whenTagDoesNotExist() {
    UUID id = UUID.randomUUID();
    when(tagRepository.findById(id)).thenReturn(Optional.empty());

    service.deleteTag(id); // must not throw

    verify(tagRepository, never()).deleteById(any());
  }

  // --- helpers -------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static List<Tag> anyIterableOfTags() {
    return any(List.class);
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<List<Tag>> tagListCaptor() {
    return ArgumentCaptor.forClass(List.class);
  }
}
