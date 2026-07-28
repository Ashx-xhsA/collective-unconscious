package com.cu.collective_unconscious;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import com.cu.collective_unconscious.domain.entities.Ticket;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.Role;
import com.cu.collective_unconscious.domain.entities.enums.TicketStatus;
import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.domain.entities.enums.WritingMode;
import com.cu.collective_unconscious.dtos.CreateContributionRequestDto;
import com.cu.collective_unconscious.repository.ContributionRepository;
import com.cu.collective_unconscious.repository.TicketRepository;
import com.cu.collective_unconscious.repository.UserRepository;
import com.cu.collective_unconscious.repository.WorkRepository;
import com.cu.collective_unconscious.services.ContributionService;
import com.cu.collective_unconscious.services.TicketService;

/**
 * Multi-threaded exactly-one tests for the ticket concurrency hardening (plan §6), against real
 * Postgres. N threads park on a {@link CountDownLatch} and fire at once to maximize interleaving,
 * then we assert the aggregate-root pessimistic lock turned the race into a clean outcome:
 * <b>zero concurrency-induced 5xx</b> (no {@link DataIntegrityViolationException} /
 * {@link ObjectOptimisticLockingFailureException} leaking), losers get clean business rejections
 * (409 = {@link IllegalStateException}), and ticket invariants hold (contiguous numbers, ≤1 ACTIVE,
 * participant cap exact). Each scenario is repeated over {@link #ROUNDS} fresh works.
 *
 * <p>Calls services directly (not HTTP): each service method is its own transaction, which is what
 * lets separate threads actually contend. The @Scheduled sweep is disabled under the test profile,
 * so nothing advances state behind our backs.
 */
@SpringBootTest
@ActiveProfiles("test")
class TicketConcurrencyIntegrationTest {

  // Kept modest for CI speed (and within the default Hikari pool of 10). Bump locally when
  // harvesting the résumé "held across R randomized interleavings" number.
  private static final int ROUNDS = 50;

  @Autowired TicketService ticketService;
  @Autowired ContributionService contributionService;
  @Autowired WorkRepository workRepository;
  @Autowired UserRepository userRepository;
  @Autowired TicketRepository ticketRepository;
  @Autowired ContributionRepository contributionRepository;

  @BeforeEach
  void clean() {
    ticketRepository.deleteAll();
    contributionRepository.deleteAll();
    workRepository.deleteAll();
    userRepository.deleteAll();
  }

  // --- 1. Concurrent draws (FreeTurn): no ticket_number collision, no 500 --------------------

  @Test
  void concurrentDraws_freeTurn_contiguousNumbers_noTwoInARow_no500() {
    int n = 8;
    long totalServerErr = 0;
    for (int round = 0; round < ROUNDS; round++) {
      Work work = persistWork(TurnStyle.FREETURN, null);
      List<User> users = persistUsers(n);

      List<Runnable> tasks = new ArrayList<>();
      for (User u : users) {
        tasks.add(() -> ticketService.drawTicket(work.getId(), u, null));
      }
      List<Throwable> results = runConcurrently(tasks);

      totalServerErr += serverErrs(results);
      assertThat(serverErrs(results)).as("no concurrency 5xx in round %s", round).isZero();
      // Distinct users each differ from the immediately-preceding holder, so all N draw cleanly.
      assertThat(successes(results)).isEqualTo(n);

      List<Ticket> queue = ticketRepository.findByWorkWithHolderOrderByTicketNumberAsc(work);
      assertThat(queue).hasSize(n);
      // Contiguous 1..N (the whole point: max+1 under the lock never collides).
      assertThat(queue).extracting(Ticket::getTicketNumber)
          .containsExactlyElementsOf(contiguous(n));
      // No holder appears twice in a row.
      for (int i = 1; i < queue.size(); i++) {
        assertThat(queue.get(i).getHolder().getId())
            .isNotEqualTo(queue.get(i - 1).getHolder().getId());
      }
    }
    assertThat(totalServerErr).as("cumulative concurrency 5xx across %s rounds", ROUNDS).isZero();
  }

  // --- 2. Concurrent draws hitting maxParticipants: exactly k join, rest clean 409 -----------

  @Test
  void concurrentDraws_maxParticipants_exactlyKJoin_restCleanly409() {
    int k = 3;
    int n = 8; // n > k contenders
    long totalServerErr = 0;
    for (int round = 0; round < ROUNDS; round++) {
      Work work = persistWork(TurnStyle.ROBINROUND, k);
      List<User> users = persistUsers(n);

      List<Runnable> tasks = new ArrayList<>();
      for (User u : users) {
        tasks.add(() -> ticketService.drawTicket(work.getId(), u, null));
      }
      List<Throwable> results = runConcurrently(tasks);

      totalServerErr += serverErrs(results);
      assertThat(serverErrs(results)).as("no concurrency 5xx in round %s", round).isZero();
      assertThat(successes(results)).as("exactly k joined in round %s", round).isEqualTo(k);
      assertThat(conflicts(results)).as("the rest got clean 409 in round %s", round).isEqualTo(n - k);
      assertThat(ticketRepository.countDistinctHoldersByWork(work))
          .as("participant cap held in round %s", round).isEqualTo(k);
    }
    assertThat(totalServerErr).as("cumulative concurrency 5xx across %s rounds", ROUNDS).isZero();
  }

  // --- 3. Submit vs timeout cleanup: no 500, ≤1 ACTIVE ---------------------------------------

  @Test
  void concurrentSubmitVsExpirySweep_no500_atMostOneActive() {
    long totalServerErr = 0;
    for (int round = 0; round < ROUNDS; round++) {
      Work work = persistWork(TurnStyle.FREETURN, null);
      User a = persistUsers(1).get(0);
      ticketService.drawTicket(work.getId(), a, null); // #1 ACTIVE (a)
      UUID draftId = createDraft(work.getId(), a);

      // Force a's ACTIVE ticket already expired, so submit (fencing) races the cleanup sweep.
      Ticket active = ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE).orElseThrow();
      active.setExpiresAt(LocalDateTime.now().minusMinutes(1));
      ticketRepository.save(active);

      List<Throwable> results = runConcurrently(List.of(
          () -> contributionService.submit(draftId, a),
          () -> ticketService.advanceWork(work.getId())));

      totalServerErr += serverErrs(results);
      assertThat(serverErrs(results)).as("no concurrency 5xx in round %s", round).isZero();
      // The expired ticket makes submit's fencing reject cleanly; the sweep expires it. Either
      // ordering yields the same clean split — never a lost update or 500.
      assertThat(successes(results) + conflicts(results)).isEqualTo(2);
      assertThat(ticketRepository.countByWorkAndStatus(work, TicketStatus.ACTIVE))
          .as("at most one ACTIVE in round %s", round).isLessThanOrEqualTo(1);
    }
    assertThat(totalServerErr).as("cumulative concurrency 5xx across %s rounds", ROUNDS).isZero();
  }

  // --- 4. Concurrent submit on the same work -------------------------------------------------

  @Test
  void concurrentDoubleSubmit_sameActive_exactlyOneSucceeds_otherClean409() {
    long totalServerErr = 0;
    for (int round = 0; round < ROUNDS; round++) {
      Work work = persistWork(TurnStyle.FREETURN, null);
      User a = persistUsers(1).get(0);
      ticketService.drawTicket(work.getId(), a, null); // #1 ACTIVE (a), valid (future TTL)
      UUID draftId = createDraft(work.getId(), a);

      // Same user double-clicks submit on the same ACTIVE ticket.
      List<Throwable> results = runConcurrently(List.of(
          () -> contributionService.submit(draftId, a),
          () -> contributionService.submit(draftId, a)));

      totalServerErr += serverErrs(results);
      assertThat(serverErrs(results)).as("no concurrency 5xx in round %s", round).isZero();
      assertThat(successes(results)).as("exactly one submit wins in round %s", round).isEqualTo(1);
      assertThat(conflicts(results)).as("the other is a clean 409 in round %s", round).isEqualTo(1);
    }
    assertThat(totalServerErr).as("cumulative concurrency 5xx across %s rounds", ROUNDS).isZero();
  }

  @Test
  void submit_byNonActiveHolder_isFencedTo409_notServerError() {
    Work work = persistWork(TurnStyle.FREETURN, null);
    List<User> users = persistUsers(2);
    User a = users.get(0);
    User b = users.get(1);
    ticketService.drawTicket(work.getId(), a, null); // a holds the ACTIVE ticket
    UUID bDraft = createDraft(work.getId(), b);       // b writes a draft but isn't up

    // b is not the active holder -> fencing rejects with a business 409, not a 5xx.
    assertThat(catchThrowable(() -> contributionService.submit(bDraft, b)))
        .isInstanceOf(IllegalStateException.class);
  }

  // --- concurrency harness ---------------------------------------------------------------------

  // Run all tasks "at once": each thread parks on a shared gate, then fires together. Returns each
  // task's throwable (null == success), positionally.
  private List<Throwable> runConcurrently(List<Runnable> tasks) {
    int n = tasks.size();
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch ready = new CountDownLatch(n);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Throwable>> futures = new ArrayList<>(n);
    for (Runnable task : tasks) {
      futures.add(pool.submit(() -> {
        ready.countDown();
        go.await();
        try {
          task.run();
          return null;
        } catch (Throwable t) {
          return t;
        }
      }));
    }
    try {
      ready.await();   // everyone parked at the gate
      go.countDown();  // release simultaneously
      List<Throwable> out = new ArrayList<>(n);
      for (Future<Throwable> f : futures) {
        out.add(f.get());
      }
      return out;
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      pool.shutdownNow();
    }
  }

  // Clean business rejection (mapped to 4xx by ErrorController): 409 IllegalState / 403 AccessDenied.
  private static boolean isClientReject(Throwable t) {
    return t instanceof IllegalStateException || t instanceof AccessDeniedException;
  }

  private static long successes(List<Throwable> results) {
    return results.stream().filter(Objects::isNull).count();
  }

  private static long conflicts(List<Throwable> results) {
    return results.stream().filter(t -> t != null && isClientReject(t)).count();
  }

  // The 5xx bucket we must drive to zero: optimistic-lock / constraint violations / anything else.
  private static long serverErrs(List<Throwable> results) {
    return results.stream().filter(t -> t != null && !isClientReject(t)).count();
  }

  private static Throwable catchThrowable(Runnable r) {
    try {
      r.run();
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  private static List<Long> contiguous(int n) {
    List<Long> nums = new ArrayList<>(n);
    for (long i = 1; i <= n; i++) {
      nums.add(i);
    }
    return nums;
  }

  // --- fixtures --------------------------------------------------------------------------------

  private List<User> persistUsers(int count) {
    List<User> users = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      users.add(userRepository.save(User.builder()
          .email(UUID.randomUUID() + "@b.com")
          .username("u" + UUID.randomUUID().toString().substring(0, 8))
          .password("hashed")
          .role(Role.USER)
          .build()));
    }
    return users;
  }

  private Work persistWork(TurnStyle turnStyle, Integer maxParticipants) {
    User creator = persistUsers(1).get(0); // separate from the drawers; never a ticket holder
    return workRepository.save(Work.builder()
        .title("The Long Story")
        .preface("preface")
        .workType(WorkType.ARTICLE) // no chapter title required
        .turnStyle(turnStyle)
        .writingMode(WritingMode.DEFAULT)
        .visibility(Visibility.PUBLIC)
        .createdAuthor(creator)
        .maxParticipants(maxParticipants)
        .build());
  }

  private UUID createDraft(UUID workId, User author) {
    return contributionService.createDraft(
        workId, CreateContributionRequestDto.builder().content("x").build(), author).getId();
  }
}
