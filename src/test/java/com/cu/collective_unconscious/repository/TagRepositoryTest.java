package com.cu.collective_unconscious.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.cu.collective_unconscious.domain.entities.Tag;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.Role;
import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.domain.entities.enums.WritingMode;

/**
 * Slice test for {@link TagRepository} against the real Postgres schema. Verifies the derived
 * {@code findByNameIn} lookup used by get-or-create, the unique/not-null column constraints, and
 * the {@code @Formula} workCount subquery that the delete guard depends on.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TagRepositoryTest {

  @Autowired TagRepository tagRepository;
  @Autowired TestEntityManager em;

  // --- findByNameIn --------------------------------------------------------

  @Test
  void findByNameIn_returnsOnlyTheMatchingTags() {
    tagRepository.save(Tag.builder().name("fantasy").build());
    tagRepository.save(Tag.builder().name("horror").build());
    tagRepository.save(Tag.builder().name("romance").build());

    List<Tag> found = tagRepository.findByNameIn(Set.of("fantasy", "romance", "ghost"));

    assertThat(found).extracting(Tag::getName)
        .containsExactlyInAnyOrder("fantasy", "romance");
  }

  @Test
  void findByNameIn_emptySet_returnsEmpty() {
    tagRepository.save(Tag.builder().name("fantasy").build());

    assertThat(tagRepository.findByNameIn(Set.of())).isEmpty();
  }

  @Test
  void findByNameIn_noMatch_returnsEmpty() {
    tagRepository.save(Tag.builder().name("fantasy").build());

    assertThat(tagRepository.findByNameIn(Set.of("nope"))).isEmpty();
  }

  // --- constraints ---------------------------------------------------------

  @Test
  void save_duplicateName_violatesUniqueConstraint() {
    tagRepository.saveAndFlush(Tag.builder().name("fantasy").build());

    assertThatThrownBy(() -> tagRepository.saveAndFlush(Tag.builder().name("fantasy").build()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void save_nullName_violatesNotNullConstraint() {
    assertThatThrownBy(() -> tagRepository.saveAndFlush(Tag.builder().name(null).build()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // --- workCount @Formula --------------------------------------------------

  @Test
  void workCount_isZero_forTagWithNoWorks() {
    Tag saved = tagRepository.saveAndFlush(Tag.builder().name("fantasy").build());
    em.clear();

    Tag reloaded = tagRepository.findById(saved.getId()).orElseThrow();
    assertThat(reloaded.getWorkCount()).isZero();
  }

  @Test
  void workCount_countsWorksThatCarryTheTag() {
    Tag tag = tagRepository.saveAndFlush(Tag.builder().name("fantasy").build());
    persistWorkWithTag(tag);
    persistWorkWithTag(tag);
    em.flush();
    em.clear();

    Tag reloaded = tagRepository.findById(tag.getId()).orElseThrow();
    assertThat(reloaded.getWorkCount()).isEqualTo(2);
  }

  // --- helpers -------------------------------------------------------------

  private void persistWorkWithTag(Tag tag) {
    User author = em.persist(User.builder()
        .email(UUID.randomUUID() + "@b.com")
        .username("u" + UUID.randomUUID().toString().substring(0, 8))
        .password("hashed")
        .role(Role.USER)
        .build());

    Set<Tag> tags = new HashSet<>();
    tags.add(em.find(Tag.class, tag.getId()));
    em.persist(Work.builder()
        .title("A Work")
        .preface("preface")
        .workType(WorkType.NOVEL)
        .turnStyle(TurnStyle.ROBINROUND)
        .writingMode(WritingMode.DEFAULT)
        .visibility(Visibility.PUBLIC)
        .createdAuthor(author)
        .tags(tags)
        .build());
  }
}
