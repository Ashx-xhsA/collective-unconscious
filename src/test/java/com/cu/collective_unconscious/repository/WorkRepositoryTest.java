package com.cu.collective_unconscious.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
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
 * Slice test for {@link WorkRepository} against real Postgres. Pins the two listing queries used
 * by the works feed: only PUBLIC works are returned (UNLISTED stays hidden), the tag filter keeps
 * only works carrying the given tag, and in both cases each work's tags are eagerly fetched so the
 * mapper can read them after the session closes (the LEFT JOIN FETCH that avoids N+1).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class WorkRepositoryTest {

  @Autowired WorkRepository workRepository;
  @Autowired TestEntityManager em;

  private User author;

  private User newAuthor() {
    return em.persist(User.builder()
        .email(UUID.randomUUID() + "@b.com")
        .username("u" + UUID.randomUUID().toString().substring(0, 8))
        .password("hashed")
        .role(Role.USER)
        .build());
  }

  private Work persistWork(Visibility visibility, Tag... tags) {
    if (author == null) {
      author = newAuthor();
    }
    Set<Tag> tagSet = new HashSet<>();
    for (Tag t : tags) {
      tagSet.add(t);
    }
    return em.persist(Work.builder()
        .title("A Work")
        .preface("preface")
        .workType(WorkType.NOVEL)
        .turnStyle(TurnStyle.ROBINROUND)
        .writingMode(WritingMode.DEFAULT)
        .visibility(visibility)
        .createdAuthor(author)
        .tags(tagSet)
        .build());
  }

  private Tag persistTag(String name) {
    return em.persist(Tag.builder().name(name).build());
  }

  // --- findByVisibilityWithTags -------------------------------------------

  @Test
  void findByVisibilityWithTags_returnsOnlyPublicWorks() {
    persistWork(Visibility.PUBLIC);
    persistWork(Visibility.PUBLIC);
    persistWork(Visibility.UNLISTED);
    em.flush();
    em.clear();

    List<Work> publicWorks = workRepository.findByVisibilityWithTags(Visibility.PUBLIC);

    assertThat(publicWorks).hasSize(2)
        .allMatch(w -> w.getVisibility() == Visibility.PUBLIC);
  }

  @Test
  void findByVisibilityWithTags_eagerlyFetchesTags() {
    Tag tag = persistTag("fantasy");
    persistWork(Visibility.PUBLIC, tag);
    em.flush();
    em.clear();

    List<Work> works = workRepository.findByVisibilityWithTags(Visibility.PUBLIC);

    // Session is cleared; if tags weren't fetched this would throw LazyInitializationException.
    assertThat(works).hasSize(1);
    assertThat(works.get(0).getTags()).extracting(Tag::getName).containsExactly("fantasy");
  }

  @Test
  void findByVisibilityWithTags_returnsEmpty_whenNoPublicWorks() {
    persistWork(Visibility.UNLISTED);
    em.flush();
    em.clear();

    assertThat(workRepository.findByVisibilityWithTags(Visibility.PUBLIC)).isEmpty();
  }

  // --- findByVisibilityAndTagWithTags -------------------------------------

  @Test
  void findByVisibilityAndTagWithTags_returnsOnlyWorksCarryingThatTag() {
    Tag fantasy = persistTag("fantasy");
    Tag horror = persistTag("horror");
    persistWork(Visibility.PUBLIC, fantasy);
    persistWork(Visibility.PUBLIC, horror);
    em.flush();
    em.clear();

    Tag fantasyRef = em.find(Tag.class, fantasy.getId());
    List<Work> works = workRepository.findByVisibilityAndTagWithTags(Visibility.PUBLIC, fantasyRef);

    assertThat(works).hasSize(1);
    assertThat(works.get(0).getTags()).extracting(Tag::getName).contains("fantasy");
  }

  @Test
  void findByVisibilityAndTagWithTags_excludesUnlistedWorks_evenWithMatchingTag() {
    Tag fantasy = persistTag("fantasy");
    persistWork(Visibility.UNLISTED, fantasy);
    em.flush();
    em.clear();

    Tag fantasyRef = em.find(Tag.class, fantasy.getId());
    List<Work> works = workRepository.findByVisibilityAndTagWithTags(Visibility.PUBLIC, fantasyRef);

    assertThat(works).isEmpty();
  }

  @Test
  void findByVisibilityAndTagWithTags_isEmpty_whenNoWorkHasTheTag() {
    Tag fantasy = persistTag("fantasy");
    Tag unused = persistTag("unused");
    persistWork(Visibility.PUBLIC, fantasy);
    em.flush();
    em.clear();

    Tag unusedRef = em.find(Tag.class, unused.getId());
    assertThat(workRepository.findByVisibilityAndTagWithTags(Visibility.PUBLIC, unusedRef))
        .isEmpty();
  }

  // --- findWithTagsById ----------------------------------------------------

  @Test
  void findWithTagsById_returnsWork_eagerlyFetchingTags() {
    Tag tag = persistTag("fantasy");
    Work work = persistWork(Visibility.UNLISTED, tag); // visibility irrelevant for direct-by-id read
    em.flush();
    em.clear();

    var found = workRepository.findWithTagsById(work.getId());

    assertThat(found).isPresent();
    assertThat(found.get().getTags()).extracting(Tag::getName).containsExactly("fantasy");
  }

  @Test
  void findWithTagsById_returnsEmpty_whenNoSuchWork() {
    assertThat(workRepository.findWithTagsById(UUID.randomUUID())).isEmpty();
  }
}
