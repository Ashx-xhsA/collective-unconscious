package com.cu.collective_unconscious.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import com.cu.collective_unconscious.domain.entities.Contribution;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.Role;
import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.domain.entities.enums.WritingMode;

/**
 * Slice test for {@link ContributionRepository} against real Postgres. Pins {@code
 * findDraftsWithWork}: it returns only the given author's rows, filters strictly on the draft
 * flag, and JOIN FETCHes each contribution's work so the mapper can read {@code work.getTitle()}
 * after the session closes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ContributionRepositoryTest {

  @Autowired ContributionRepository contributionRepository;
  @Autowired TestEntityManager em;

  private User newUser() {
    return em.persist(User.builder()
        .email(UUID.randomUUID() + "@b.com")
        .username("u" + UUID.randomUUID().toString().substring(0, 8))
        .password("hashed")
        .role(Role.USER)
        .build());
  }

  private Work newWork(User author) {
    return em.persist(Work.builder()
        .title("A Work")
        .preface("preface")
        .workType(WorkType.NOVEL)
        .turnStyle(TurnStyle.ROBINROUND)
        .writingMode(WritingMode.DEFAULT)
        .visibility(Visibility.PUBLIC)
        .createdAuthor(author)
        .build());
  }

  private Contribution newContribution(Work work, User author, boolean draft) {
    return em.persist(Contribution.builder()
        .work(work)
        .author(author)
        .content("some content")
        .draft(draft)
        .build());
  }

  @Test
  void findDraftsWithWork_returnsOnlyDraftsForThatAuthor() {
    User author = newUser();
    Work work = newWork(author);
    newContribution(work, author, true);
    newContribution(work, author, true);
    em.flush();
    em.clear();

    List<Contribution> drafts = contributionRepository.findDraftsWithWork(author, true);

    assertThat(drafts).hasSize(2).allMatch(Contribution::isDraft);
  }

  @Test
  void findDraftsWithWork_excludesSubmittedContributions() {
    User author = newUser();
    Work work = newWork(author);
    newContribution(work, author, true);  // draft
    newContribution(work, author, false); // submitted
    em.flush();
    em.clear();

    List<Contribution> drafts = contributionRepository.findDraftsWithWork(author, true);

    assertThat(drafts).hasSize(1).allMatch(Contribution::isDraft);
  }

  @Test
  void findDraftsWithWork_excludesOtherAuthorsDrafts() {
    User author = newUser();
    User other = newUser();
    Work work = newWork(author);
    newContribution(work, author, true);
    newContribution(work, other, true);
    em.flush();
    em.clear();

    List<Contribution> drafts = contributionRepository.findDraftsWithWork(author, true);

    assertThat(drafts).hasSize(1);
  }

  @Test
  void findDraftsWithWork_eagerlyFetchesWork() {
    User author = newUser();
    Work work = newWork(author);
    newContribution(work, author, true);
    em.flush();
    em.clear();

    List<Contribution> drafts = contributionRepository.findDraftsWithWork(author, true);

    // Session cleared: reading the JOIN FETCHed work must not raise LazyInitializationException.
    assertThat(drafts).hasSize(1);
    assertThat(drafts.get(0).getWork().getTitle()).isEqualTo("A Work");
  }

  @Test
  void findDraftsWithWork_returnsEmpty_whenAuthorHasNoDrafts() {
    User author = newUser();
    em.flush();
    em.clear();

    assertThat(contributionRepository.findDraftsWithWork(author, true)).isEmpty();
  }

  // --- findMaxSequenceNumber ---------------------------------------------------

  private Contribution newSubmittedContribution(Work work, User author, int sequenceNumber) {
    return em.persist(Contribution.builder()
        .work(work)
        .author(author)
        .content("some content")
        .draft(false)
        .sequenceNumber(sequenceNumber)
        .build());
  }

  @Test
  void findMaxSequenceNumber_returnsNull_whenNoSubmittedContributions() {
    User author = newUser();
    Work work = newWork(author);
    newContribution(work, author, true); // draft only, no sequence number
    em.flush();
    em.clear();

    Work workRef = em.find(Work.class, work.getId());
    assertThat(contributionRepository.findMaxSequenceNumber(workRef)).isNull();
  }

  @Test
  void findMaxSequenceNumber_returnsHighestAmongSubmittedForThatWork() {
    User author = newUser();
    Work work = newWork(author);
    newSubmittedContribution(work, author, 1);
    newSubmittedContribution(work, author, 2);
    newContribution(work, author, true); // draft, should not affect the max
    em.flush();
    em.clear();

    Work workRef = em.find(Work.class, work.getId());
    assertThat(contributionRepository.findMaxSequenceNumber(workRef)).isEqualTo(2);
  }

  @Test
  void findMaxSequenceNumber_isScopedToTheGivenWork() {
    User author = newUser();
    Work workA = newWork(author);
    Work workB = newWork(author);
    newSubmittedContribution(workA, author, 5);
    newSubmittedContribution(workB, author, 1);
    em.flush();
    em.clear();

    Work workBRef = em.find(Work.class, workB.getId());
    assertThat(contributionRepository.findMaxSequenceNumber(workBRef)).isEqualTo(1);
  }

  // --- findSubmittedAfterSequence (cursor pagination) --------------------------

  @Test
  void findSubmittedAfterSequence_returnsOnlySubmitted_orderedBySequence() {
    User author = newUser();
    Work work = newWork(author);
    newSubmittedContribution(work, author, 2);
    newSubmittedContribution(work, author, 1);
    newContribution(work, author, true); // draft, must be excluded
    em.flush();
    em.clear();

    Work workRef = em.find(Work.class, work.getId());
    List<Contribution> page = contributionRepository.findSubmittedAfterSequence(
        workRef, null, PageRequest.of(0, 20));

    assertThat(page).extracting(Contribution::getSequenceNumber).containsExactly(1, 2);
  }

  @Test
  void findSubmittedAfterSequence_afterSequence_returnsOnlyLaterOnes() {
    User author = newUser();
    Work work = newWork(author);
    newSubmittedContribution(work, author, 1);
    newSubmittedContribution(work, author, 2);
    newSubmittedContribution(work, author, 3);
    em.flush();
    em.clear();

    Work workRef = em.find(Work.class, work.getId());
    List<Contribution> page = contributionRepository.findSubmittedAfterSequence(
        workRef, 1, PageRequest.of(0, 20));

    assertThat(page).extracting(Contribution::getSequenceNumber).containsExactly(2, 3);
  }

  @Test
  void findSubmittedAfterSequence_respectsLimit() {
    User author = newUser();
    Work work = newWork(author);
    for (int i = 1; i <= 5; i++) {
      newSubmittedContribution(work, author, i);
    }
    em.flush();
    em.clear();

    Work workRef = em.find(Work.class, work.getId());
    List<Contribution> page = contributionRepository.findSubmittedAfterSequence(
        workRef, null, PageRequest.of(0, 2));

    assertThat(page).extracting(Contribution::getSequenceNumber).containsExactly(1, 2);
  }

  @Test
  void findSubmittedAfterSequence_isScopedToTheGivenWork() {
    User author = newUser();
    Work workA = newWork(author);
    Work workB = newWork(author);
    newSubmittedContribution(workA, author, 1);
    newSubmittedContribution(workB, author, 1);
    em.flush();
    em.clear();

    Work workARef = em.find(Work.class, workA.getId());
    List<Contribution> page = contributionRepository.findSubmittedAfterSequence(
        workARef, null, PageRequest.of(0, 20));

    assertThat(page).hasSize(1);
  }

  @Test
  void findSubmittedAfterSequence_eagerlyFetchesAuthor() {
    User author = newUser();
    Work work = newWork(author);
    newSubmittedContribution(work, author, 1);
    em.flush();
    em.clear();

    Work workRef = em.find(Work.class, work.getId());
    List<Contribution> page = contributionRepository.findSubmittedAfterSequence(
        workRef, null, PageRequest.of(0, 20));

    // Session cleared: reading the author after this must not throw LazyInitializationException.
    assertThat(page.get(0).getAuthor().getUsername()).isEqualTo(author.getUsername());
  }

  // --- findSubmittedById ------------------------------------------------------

  @Test
  void findSubmittedById_returnsSubmittedContribution() {
    User author = newUser();
    Work work = newWork(author);
    Contribution submitted = newSubmittedContribution(work, author, 1);
    em.flush();
    em.clear();

    assertThat(contributionRepository.findSubmittedById(submitted.getId())).isPresent();
  }

  @Test
  void findSubmittedById_excludesDraftContribution() {
    User author = newUser();
    Work work = newWork(author);
    Contribution draft = newContribution(work, author, true);
    em.flush();
    em.clear();

    assertThat(contributionRepository.findSubmittedById(draft.getId())).isEmpty();
  }

  @Test
  void findSubmittedById_returnsEmpty_whenNoSuchId() {
    assertThat(contributionRepository.findSubmittedById(UUID.randomUUID())).isEmpty();
  }
}
